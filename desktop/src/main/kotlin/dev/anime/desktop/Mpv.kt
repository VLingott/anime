package dev.anime.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.anime.ui.PlayerAudioTrack
import dev.anime.util.AppLog
import dev.anime.ui.PlayerResolution
import dev.anime.ui.PlayerSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private interface LibC : Library {
    fun setlocale(category: Int, locale: String): Pointer?

    companion object {
        val instance: LibC by lazy { Native.load("c", LibC::class.java) }
    }
}

internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_free(ptr: Pointer)
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_error_string(code: Int): String
    fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(rc: Pointer, cb: UpdateFn?, userdata: Pointer?)
    fun mpv_render_context_update(rc: Pointer): Long
    fun mpv_render_context_render(rc: Pointer, params: Pointer): Int
    fun mpv_render_context_free(rc: Pointer)

    interface UpdateFn : Callback {
        fun invoke(ctx: Pointer?)
    }

    companion object {
        val lib: LibMpv by lazy {
            runCatching { LibC.instance.setlocale(1, "C") }
            var failure: Throwable? = null
            for (name in listOf("mpv", "libmpv.so.2", "libmpv.so.1", "libmpv.so")) {
                try {
                    return@lazy Native.load(name, LibMpv::class.java)
                } catch (t: Throwable) {
                    failure = t
                }
            }
            throw IllegalStateException("libmpv not found", failure)
        }
    }
}

private const val EVENT_END_FILE = 7
private const val EVENT_FILE_LOADED = 8
private const val END_REASON_EOF = 0
private const val END_REASON_ERROR = 4
private const val UPDATE_FRAME = 1L
private const val PARAM_API_TYPE = 1
private const val PARAM_SW_SIZE = 17
private const val PARAM_SW_FORMAT = 18
private const val PARAM_SW_STRIDE = 19
private const val PARAM_SW_POINTER = 20

internal class MpvEngine(
    private val url: String,
    startMs: Long,
    private val widgetWidth: Int,
    private val widgetHeight: Int,
    private val onFrame: (Image) -> Unit,
    private val onProgress: (Long, Long) -> Unit,
    private val onError: (String) -> Unit,
    private val onEnded: () -> Unit,
) : AutoCloseable, PlayerSession {

    private val lib = LibMpv.lib
    private val handle: Pointer = lib.mpv_create() ?: error("mpv_create() failed")

    override var playing by mutableStateOf(true)
        private set
    override var buffering by mutableStateOf(true)
        private set
    override var positionMs by mutableStateOf(0L)
        private set
    override var durationMs by mutableStateOf(0L)
        private set
    override var audioTracks by mutableStateOf<List<PlayerAudioTrack>>(emptyList())
        private set
    override var activeAudioId by mutableStateOf<String?>(null)
        private set
    override var resolutions by mutableStateOf<List<PlayerResolution>>(emptyList())
        private set
    override var activeResolutionId by mutableStateOf<String?>("auto")
        private set

    private lateinit var renderThread: Thread
    private lateinit var eventThread: Thread

    private val swSize = Memory(8)
    private val swFormat = Memory(8).apply { setString(0, "rgb0") }
    private val swStride = Memory(8)
    private val params = Memory(16L * 5)

    @Volatile private var errorSent = false
    @Volatile private var closed = false
    @Volatile private var ended = false
    private val lock = Object()
    private var pendingFrame = false
    @Volatile private var pendingSeekMs = 0L
    private val startSeconds = (startMs / 1000.0).coerceAtLeast(0.0)

    private val recentFrames = ArrayDeque<Image>()

    private fun publishFrame(image: Image) {
        synchronized(recentFrames) {
            recentFrames.addLast(image)
            while (recentFrames.size > 4) recentFrames.removeFirst().close()
        }
        onFrame(image)
    }

    private val updateCallback = object : LibMpv.UpdateFn {
        override fun invoke(ctx: Pointer?) {
            synchronized(lock) {
                pendingFrame = true
                lock.notifyAll()
            }
        }
    }

    @Volatile private var reqWidth = widgetWidth.coerceAtLeast(2)
    @Volatile private var reqHeight = widgetHeight.coerceAtLeast(2)

    init {
        setOption("config", "no")
        setOption("vo", "libmpv")
        setOption("hwdec", "auto-safe")
        setOption("force-window", "no")
        setOption("idle", "yes")
        setOption("keep-open", "no")
        setOption("terminal", "no")
        setOption("input-terminal", "no")
        setOption("input-default-bindings", "no")
        setOption("input-vo-keyboard", "no")
        setOption("osc", "no")
        setOption("osd-level", "0")
        setOption("load-scripts", "no")
        setOption("audio-client-name", "Anime")
        setOption("sub-auto", "no")
        setOption("audio-file-auto", "no")
        setOption("cache", "yes")
        setOption("cache-pause", "no")
        setOption("demuxer-max-bytes", "192MiB")
        setOption("demuxer-max-back-bytes", "64MiB")
        setOption("demuxer-readahead-secs", "30")
        setOption("video-sync", "audio")
        if (startSeconds > 0.0) setOption("start", startSeconds.toString())

        val rc = lib.mpv_initialize(handle)
        check(rc >= 0) { "mpv_initialize: " + lib.mpv_error_string(rc) }

        renderThread = Thread({ renderLoop() }, "mpv-render").apply { isDaemon = true }.also { it.start() }
        eventThread = Thread({ eventLoop() }, "mpv-events").apply { isDaemon = true }.also { it.start() }
        Thread({ loadResolutions() }, "mpv-resolutions").apply { isDaemon = true }.start()
    }

    private fun setOption(name: String, value: String) {
        lib.mpv_set_option_string(handle, name, value)
    }

    private fun getProperty(name: String): String? {
        val ptr = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            ptr.getString(0)
        } finally {
            lib.mpv_free(ptr)
        }
    }

    private fun command(vararg args: String): Int {
        val mem = Memory(Native.POINTER_SIZE.toLong() * (args.size + 1))
        val strings = args.map { arg ->
            val bytes = arg.toByteArray(Charsets.UTF_8)
            Memory((bytes.size + 1).toLong()).also { m ->
                m.write(0, bytes, 0, bytes.size)
                m.setByte(bytes.size.toLong(), 0)
            }
        }
        strings.forEachIndexed { i, ptr -> mem.setPointer(Native.POINTER_SIZE.toLong() * i, ptr) }
        mem.setPointer(Native.POINTER_SIZE.toLong() * strings.size, null)
        val rc = lib.mpv_command(handle, mem)
        return rc
    }

    private fun loadFile() {
        command("loadfile", url, "replace")
    }

    private fun renderLoop() {
        var renderContext: Pointer? = null
        var renderW = 0
        var renderH = 0
        var rawBuffer: Memory? = null
        var buffer: Pointer? = null
        var stride = 0
        var info: ImageInfo? = null
        var forceRender = true

        fun setupSize(width: Int, height: Int) {
            renderW = width
            renderH = height
            stride = ((width * 4 + 63) / 64) * 64
            val raw = Memory(stride.toLong() * height + 64L)
            val base = Pointer.nativeValue(raw)
            val offset = (64L - (base % 64L)) % 64L
            rawBuffer = raw
            buffer = raw.share(offset)
            info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB)
            forceRender = true
        }

        fun createContext() {
            val apiType = Memory(4).apply { setString(0, "sw") }
            val params = Memory(16L * 2).apply {
                setInt(0, PARAM_API_TYPE)
                setPointer(8, apiType)
                setInt(16, 0)
                setPointer(24, null)
            }
            val res = PointerByReference()
            val rc = lib.mpv_render_context_create(res, handle, params)
            if (rc < 0) {
                reportError("render context: " + lib.mpv_error_string(rc))
                return
            }
            val created = res.value
            renderContext = created
            lib.mpv_render_context_set_update_callback(created, updateCallback, null)
        }

        try {
            createContext()
            setupSize(reqWidth, reqHeight)
            loadFile()

            while (!closed) {
                synchronized(lock) {
                    while (!pendingFrame && !closed) lock.wait(250)
                    pendingFrame = false
                }
                if (closed) break
                if (reqWidth != renderW || reqHeight != renderH) setupSize(reqWidth, reqHeight)
                val ctx = renderContext ?: continue
                val flags = lib.mpv_render_context_update(ctx)
                if (flags and UPDATE_FRAME == 0L && !forceRender) continue
                forceRender = false

                val b = buffer ?: continue
                val imageInfo = info ?: continue
                swSize.setInt(0, renderW)
                swSize.setInt(4, renderH)
                swStride.setLong(0, stride.toLong())
                params.setInt(0, PARAM_SW_SIZE); params.setPointer(8, swSize)
                params.setInt(16, PARAM_SW_FORMAT); params.setPointer(24, swFormat)
                params.setInt(32, PARAM_SW_STRIDE); params.setPointer(40, swStride)
                params.setInt(48, PARAM_SW_POINTER); params.setPointer(56, b)
                params.setInt(64, 0); params.setPointer(72, null)
                val rc = lib.mpv_render_context_render(ctx, params)
                if (rc < 0) continue
                val bytes = b.getByteArray(0, stride * renderH)
                publishFrame(Image.makeRaster(imageInfo, bytes, stride))
            }
        } catch (t: Throwable) {
            if (!closed) reportError(t.message ?: "render failed")
        } finally {
            renderContext?.let { rc ->
                lib.mpv_render_context_set_update_callback(rc, null, null)
                lib.mpv_render_context_free(rc)
            }
            renderContext = null
            rawBuffer = null
            buffer = null
        }
    }

    private fun eventLoop() {
        var lastTick = 0L
        var lastProgress = 0L
        try {
            while (!closed) {
                val event = lib.mpv_wait_event(handle, 0.2) ?: break
                val eventId = event.getInt(0)
                when (eventId) {
                    EVENT_END_FILE -> {
                        val data = event.getPointer(16)
                        val reason = data?.getInt(0) ?: END_REASON_EOF
                        if (reason == END_REASON_ERROR) {
                            reportError("playback failed")
                        } else if (reason == END_REASON_EOF && !ended) {
                            ended = true
                            playing = false
                            onEnded()
                        }
                    }
                    EVENT_FILE_LOADED -> {
                        buffering = false
                        val target = pendingSeekMs
                        if (target > 0) {
                            pendingSeekMs = 0
                            command("seek", (target / 1000.0).toString(), "absolute")
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastTick >= 250) {
                    lastTick = now
                    if (closed) break
                    readProperties()
                    if (now - lastProgress >= 3000 && durationMs > 0 && positionMs > 0) {
                        lastProgress = now
                        onProgress(positionMs, durationMs)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun readProperties() {
        val position = getProperty("time-pos")?.toDoubleOrNull()
        if (position != null) {
            positionMs = (position * 1000).roundToLong()
            if (buffering && !ended) buffering = false
        }
        val duration = getProperty("duration")?.toDoubleOrNull()
        if (duration != null && duration > 0) durationMs = (duration * 1000).roundToLong()
        val paused = getProperty("pause")
        if (paused != null) playing = paused == "no" && !ended
        val cachePause = getProperty("paused-for-cache")
        if (cachePause == "yes") buffering = true
        updateTracks()
    }

    private fun updateTracks() {
        val json = getProperty("track-list") ?: return
        val list = runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull != "audio") return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val language = o["lang"]?.jsonPrimitive?.contentOrNull
                val title = o["title"]?.jsonPrimitive?.contentOrNull
                val codec = o["codec"]?.jsonPrimitive?.contentOrNull?.uppercase()
                val channels = o["channels"]?.jsonPrimitive?.contentOrNull
                val label = languageName(language) ?: title ?: "Track $id"
                val detail = listOfNotNull(codec, channels?.let { "${it}ch" }).joinToString(" ").ifBlank { null }
                PlayerAudioTrack(
                    id = id,
                    label = label,
                    detail = detail,
                    selected = o["selected"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
        }.getOrNull() ?: return

        if (list != audioTracks) {
            audioTracks = list
        }
        activeAudioId = list.firstOrNull { it.selected }?.id
    }

    private fun loadResolutions() {
        var text: String? = null
        repeat(2) { attempt ->
            text = runCatching {
                val connection = java.net.URI(url).toURL().openConnection()
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text?.contains("#EXT-X-STREAM-INF") == true) return@repeat
            if (attempt == 0) Thread.sleep(1_500)
        }
        val body = text ?: return
        if (!body.contains("#EXT-X-STREAM-INF")) return

        val found = LinkedHashMap<String, String>()
        var bandwidth: Long? = null
        var label: String? = null
        for (line in body.lineSequence()) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                label = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues?.get(2)?.let { it + "p" }
            } else if (line.isNotBlank() && !line.startsWith("#") && bandwidth != null) {
                val id = bandwidth.toString()
                found[id] = label ?: ((bandwidth ?: 0L) / 1000).toString() + " kbps"
                bandwidth = null
                label = null
            }
        }
        if (found.isEmpty()) return
        val options = ArrayList<PlayerResolution>(found.size + 1)
        options += PlayerResolution("auto", "Auto", selected = true)
        found.forEach { (id, quality) -> options += PlayerResolution(id, quality) }
        resolutions = options
    }

    override fun selectResolution(id: String) {
        if (closed || resolutions.size <= 1) return
        if (id == activeResolutionId) return
        runCatching {
            lib.mpv_set_property_string(handle, "hls-bitrate", if (id == "auto") "max" else id)
        }
        pendingSeekMs = positionMs
        activeResolutionId = id
        resolutions = resolutions.map { it.copy(selected = it.id == id) }
        ended = false
        command("loadfile", url, "replace")
    }

    private fun reportError(message: String) {
        if (closed || errorSent) return
        errorSent = true
        AppLog.e("mpv", message)
        onError(message)
    }

    override fun playPause() {
        if (closed) return
        val pause = if (playing) "yes" else "no"
        lib.mpv_set_property_string(handle, "pause", pause)
        playing = !playing
    }

    override fun seekTo(positionMs: Long) {
        if (closed) return
        ended = false
        command("seek", (positionMs / 1000.0).toString(), "absolute")
        this.positionMs = positionMs
    }

    override fun selectAudio(id: String) {
        if (closed) return
        lib.mpv_set_property_string(handle, "aid", id)
        activeAudioId = id
        audioTracks = audioTracks.map { it.copy(selected = it.id == id) }
    }

    fun resize(width: Int, height: Int) {
        if (closed) return
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        if (w == reqWidth && h == reqHeight) return
        reqWidth = w
        reqHeight = h
        synchronized(lock) {
            pendingFrame = true
            lock.notifyAll()
        }
    }

    override fun close() {
        if (closed) return
        if (durationMs > 0) onProgress(positionMs, durationMs)
        closed = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { renderThread.join(2500) }
        runCatching { eventThread.join(1000) }
        runCatching { lib.mpv_terminate_destroy(handle) }
    }
}

private fun languageName(code: String?): String? = when (code?.lowercase()) {
    null, "", "und" -> null
    "jpn", "ja" -> "Japanese"
    "eng", "en" -> "English"
    "spa", "es" -> "Spanish"
    "por", "pt" -> "Portuguese"
    "fre", "fra", "fr" -> "French"
    "ger", "deu", "de" -> "German"
    "ita", "it" -> "Italian"
    "rus", "ru" -> "Russian"
    "kor", "ko" -> "Korean"
    "chi", "zho", "zh" -> "Chinese"
    "hin", "hi" -> "Hindi"
    "ara", "ar" -> "Arabic"
    "tha", "th" -> "Thai"
    "vie", "vi" -> "Vietnamese"
    "ind", "id" -> "Indonesian"
    "tur", "tr" -> "Turkish"
    "pol", "pl" -> "Polish"
    "ukr", "uk" -> "Ukrainian"
    else -> code.uppercase()
}
