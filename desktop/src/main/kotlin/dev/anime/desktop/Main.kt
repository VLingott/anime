package dev.anime.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import dev.anime.search.Details
import dev.anime.cache.CacheManager
import dev.anime.search.PlaybackService
import dev.anime.store.FileRecentsStore
import dev.anime.util.AppLog
import dev.anime.source.AniListSource
import dev.anime.source.SourceContext
import dev.anime.source.TvmazeEpisodesSource
import dev.anime.source.buildHttpClient
import dev.anime.ui.App
import dev.anime.ui.CoreHandles
import dev.anime.model.SubtitleTrack
import dev.anime.ui.Deps
import dev.anime.ui.ImageHost
import dev.anime.ui.PlayerHost
import dev.anime.ui.PlayerResolution
import dev.anime.ui.PlayerSession
import dev.anime.ui.UpdateChecker
import dev.anime.util.GitHubRateLimit
import dev.anime.util.UpdateCheckGate
import dev.anime.util.isNewerVersion
import dev.anime.web.HlsProxy
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private const val UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"


private object Core {
    val proxyToken: String = java.util.UUID.randomUUID().toString().replace("-", "")

    val proxyPort: Int = java.net.ServerSocket(
        0,
        0,
        java.net.InetAddress.getByName("127.0.0.1"),
    ).use { it.localPort }

    val cacheDir: java.io.File = java.io.File(
        System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"),
        "anime",
    )
    val ctx: SourceContext by lazy { SourceContext(buildHttpClient(UA)) }
    val aniList: AniListSource by lazy { AniListSource(cacheDir) }
    val tvmaze: TvmazeEpisodesSource by lazy { TvmazeEpisodesSource() }
    val details: Details by lazy { Details(ctx) }
    val hls: HlsProxy by lazy {
        HlsProxy(
            ctx,
            dir = System.getProperty("java.io.tmpdir") + "/anime-hls-cache",
            urlFor = { abs ->
                "/api/hls?u=" + java.net.URLEncoder.encode(abs, Charsets.UTF_8) + "&k=" + proxyToken
            },
        )
    }
    val playback: PlaybackService by lazy {
        PlaybackService(
            ctx, details,
            urlTransform = { url ->
                "http://127.0.0.1:$proxyPort$url" +
                    (if (url.contains('?')) "&" else "?") + "k=" + proxyToken
            },
            health = dev.anime.search.ProviderHealth(java.io.File(System.getProperty("java.io.tmpdir"), "anime-provider-health.json")),
            cacheDir = cacheDir,
        )
    }

    private var proxy: Proxy? = null

    fun startProxy() {
        if (proxy != null) return
        proxy = Proxy().also {
            runCatching { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                .onFailure { error -> AppLog.e("hls", "Could not start the local HLS proxy", error) }
        }
    }

    class Proxy : NanoHTTPD("127.0.0.1", proxyPort) {
        override fun serve(session: IHTTPSession): Response {
            if (!session.uri.startsWith("/api/hls") ||
                session.parameters["k"]?.firstOrNull() != proxyToken
            ) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            val u = session.parameters["u"]?.firstOrNull()
            val link = session.parameters["link"]?.firstOrNull()
            val result = runBlocking {
                runCatching {
                    if (!link.isNullOrBlank()) hls.openLinkFile(link) else hls.handleFile(u.orEmpty())
                }.onFailure { AppLog.w("hls", "Proxy request failed", it) }.getOrNull()
            } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "gone")
            return when (result) {
                is HlsProxy.Result.Playlist -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", result.text)
                is HlsProxy.Result.FileData -> newFixedLengthResponse(
                    Response.Status.OK, result.contentType, result.file.inputStream(), result.file.length(),
                )
            }
        }
    }
}

private object DesktopProgress : dev.anime.ui.ProgressStore {
    override fun resumeMs(id: Long, season: Int, episode: Int) = Progress.resumeMs(id, season, episode)
    override fun save(id: Long, season: Int, episode: Int, positionMs: Long, durationMs: Long) =
        Progress.save(id, season, episode, positionMs, durationMs)
    override fun fraction(id: Long, season: Int, episode: Int) = Progress.fraction(id, season, episode)
    override fun last(id: Long) = Progress.last(id)
    override fun titleLanguage() = Progress.titleLanguage()
    override fun saveTitleLanguage(value: Int) = Progress.saveTitleLanguage(value)
    override fun removeSeries(id: Long) = Progress.removeSeries(id)
    override fun audioPreference(id: Long) = Progress.audioPreference(id)
    override fun saveAudioPreference(id: Long, label: String?) = Progress.saveAudioPreference(id, label)
    override fun defaultAudioLanguage() = Progress.defaultAudioLanguage()
    override fun saveDefaultAudioLanguage(value: String) = Progress.saveDefaultAudioLanguage(value)
    override fun hideAdultContent() = Progress.hideAdultContent()
    override fun saveHideAdultContent(value: Boolean) = Progress.saveHideAdultContent(value)
}

private object DesktopImages : ImageHost {
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 256
        },
    )

    @Composable
    override fun Image(url: String?, modifier: Modifier, contentScale: ContentScale) {
        var bitmap by remember(url) { mutableStateOf(url?.let { cache[it] }) }
        LaunchedEffect(url) {
            if (url == null || bitmap != null) return@LaunchedEffect
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(url).openStream().use { it.readBytes() }
                    ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
                }.onFailure { AppLog.w("images", "Could not load image", it) }.getOrNull()
            }?.also { cache[url] = it }
        }
        val bmp = bitmap
        if (bmp == null) {
            androidx.compose.foundation.layout.Box(modifier.background(Color(0xFF151515)))
        } else {
            Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = contentScale)
        }
    }
}

private class DesktopPlayerHost(
    private val onFullscreen: (Boolean) -> Unit,
) : PlayerHost {
    override val pictureInPicture = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onScreenShown() = Unit
    override fun onScreenHidden() = Unit

    override fun setFullscreen(fullscreen: Boolean) {
        onFullscreen(fullscreen)
    }

    override suspend fun probeResolutions(url: String): List<PlayerResolution> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = java.net.URI(url).toURL().openConnection()
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            val text = connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            val out = LinkedHashMap<String, PlayerResolution>()
            for (line in text.lineSequence()) {
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1) ?: continue
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1) ?: continue
                out[bandwidth] = PlayerResolution(bandwidth, height + "p")
            }
            out.values.toList()
        }.onFailure { AppLog.w("player", "Resolution probe failed", it) }
            .getOrDefault(emptyList())
    }

    @Composable
    override fun Video(
        url: String,
        resumeMs: Long,
        onProgress: (Long, Long) -> Unit,
        onError: (String?) -> Unit,
        onSession: (PlayerSession?) -> Unit,
    ) {
        var frame by remember(url) { mutableStateOf<org.jetbrains.skia.Image?>(null) }
        var size by remember(url) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        var engine by remember(url) { mutableStateOf<MpvEngine?>(null) }
        var failure by remember(url) { mutableStateOf<String?>(null) }
        val currentProgress by rememberUpdatedState(onProgress)
        val currentError by rememberUpdatedState(onError)
        val currentSession by rememberUpdatedState(onSession)

        LaunchedEffect(url, size) {
            val w = size.width
            val h = size.height
            if (w <= 0 || h <= 0) return@LaunchedEffect
            if (failure != null) return@LaunchedEffect
            val running = engine
            if (running != null) {
                running.resize(w, h)
                return@LaunchedEffect
            }
            runCatching {
                MpvEngine(
                    url = url,
                    startMs = resumeMs,
                    widgetWidth = w,
                    widgetHeight = h,
                    onFrame = { frame = it },
                    onProgress = { position, duration -> currentProgress(position, duration) },
                    onError = { reason ->
                        failure = "Playback failed."
                        currentError(reason)
                    },
                    onEnded = { },
                )
            }.onSuccess {
                engine = it
                currentSession(it)
            }.onFailure {
                failure = "Cannot start playback: " + (it.message ?: "libmpv not found")
                currentError(it.message ?: "libmpv not found")
            }
        }

        DisposableEffect(url) {
            onDispose {
                engine?.close()
                engine = null
            }
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(Color.Black)
                .onSizeChanged { size = it },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            val image = frame
            if (image != null) {
                Image(
                    bitmap = image.toComposeImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                androidx.compose.material3.Text(
                    failure ?: "Loading stream…",
                    color = Color(0xFF9B9B96),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private const val UPDATE_REPO = "VLingott/anime"

private class DesktopUpdateChecker(private val currentVersion: String) : UpdateChecker {
    private val stateFlow = kotlinx.coroutines.flow.MutableStateFlow<dev.anime.ui.UpdateUiState?>(null)
    override val state: kotlinx.coroutines.flow.StateFlow<dev.anime.ui.UpdateUiState?> = stateFlow
    private val prefs = java.util.prefs.Preferences.userRoot().node("dev/anime/desktop/updates")
    private val statusFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val status: kotlinx.coroutines.flow.StateFlow<String?> = statusFlow
    private val gate = UpdateCheckGate(
        lastCheckAt = prefs.getLong("last_check_ms", 0L),
        blockedUntil = prefs.getLong("rate_limited_until_ms", 0L),
    )

    override fun checkForUpdates(manual: Boolean) {
        when (val allowed = gate.tryAcquire()) {
            is UpdateCheckGate.Result.Denied -> {
                val message = if (allowed.github) {
                    "GitHub rate limit reached. " + retryText(allowed.retryAt)
                } else {
                    "Update checks are limited to once per minute. " + retryText(allowed.retryAt)
                }
                statusFlow.value = message
                stateFlow.value?.let { current ->
                    if (current.phase == dev.anime.ui.UpdatePhase.ERROR) {
                        stateFlow.value = current.copy(error = message)
                    }
                }
                return
            }
            is UpdateCheckGate.Result.Allowed -> prefs.putLong("last_check_ms", allowed.at)
        }
        statusFlow.value = "Checking for updates…"
        Thread {
            runCatching {
                val connection = java.net.URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "Anime/$currentVersion")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val code = connection.responseCode
                val now = System.currentTimeMillis()
                if (code == 403 || code == 429) {
                    val retryAt = GitHubRateLimit.retryAt(connection, now) ?: (now + 60_000L)
                    gate.blockUntil(retryAt)
                    prefs.putLong("rate_limited_until_ms", retryAt)
                    val message = "GitHub rate limit reached. " + retryText(retryAt)
                    statusFlow.value = message
                    if (manual) {
                        stateFlow.value = dev.anime.ui.UpdateUiState("", dev.anime.ui.UpdatePhase.ERROR, error = message)
                    }
                    return@runCatching
                }
                if (code !in 200..299) error("GitHub returned HTTP $code")
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (connection.getHeaderField("X-RateLimit-Remaining")?.trim()?.toIntOrNull() == 0) {
                    GitHubRateLimit.retryAt(connection, now)?.let { retryAt ->
                        gate.blockUntil(retryAt)
                        prefs.putLong("rate_limited_until_ms", retryAt)
                    }
                }
                val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: error("Release has no tag")
                if (!isNewerVersion(currentVersion, tag)) {
                    prefs.remove("dismissed_tag")
                    statusFlow.value = "Anime is up to date."
                    stateFlow.value = null
                    return@runCatching
                }
                if (!manual && prefs.get("dismissed_tag", null) == tag) {
                    statusFlow.value = "Anime is up to date."
                    stateFlow.value = null
                    return@runCatching
                }
                val version = tag.removePrefix("v")
                val assets = root["assets"]?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()
                val asset = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.contains("linux") == true }
                    ?: error("Release $tag has no Linux build")
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: error("Release $tag has no Linux download URL")
                val dir = java.io.File(
                    System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"),
                    "anime/apps/$version",
                )
                val binary = java.io.File(dir, "Anime/bin/Anime")
                if (!binary.isFile) {
                    stateFlow.value = dev.anime.ui.UpdateUiState(version, dev.anime.ui.UpdatePhase.DOWNLOADING)
                    val archive = java.io.File(
                        System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"),
                        "anime/updates/anime-linux-$version.tar.gz",
                    )
                    archive.parentFile?.mkdirs()
                    val download = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    download.connectTimeout = 10_000
                    download.readTimeout = 60_000
                    download.setRequestProperty("User-Agent", "Anime/$currentVersion")
                    val total = download.contentLengthLong
                    download.inputStream.use { input ->
                        archive.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) {
                                    stateFlow.value = dev.anime.ui.UpdateUiState(
                                        version,
                                        dev.anime.ui.UpdatePhase.DOWNLOADING,
                                        progress = copied.toFloat() / total,
                                    )
                                }
                            }
                        }
                    }
                    dir.mkdirs()
                    val proc = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", dir.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    proc.inputStream.use { it.readBytes() }
                    if (proc.waitFor() != 0 || !binary.isFile) error("Could not unpack the update")
                    binary.setExecutable(true)
                }
                statusFlow.value = "Update available."
                stateFlow.value = dev.anime.ui.UpdateUiState(version, dev.anime.ui.UpdatePhase.READY)
            }.onFailure { error ->
                AppLog.w("update", "Desktop update check failed", error)
                statusFlow.value = "Update check failed."
                stateFlow.value = dev.anime.ui.UpdateUiState(
                    version = "",
                    phase = dev.anime.ui.UpdatePhase.ERROR,
                    error = error.message ?: "Could not check for updates.",
                )
            }
        }.apply { isDaemon = true; name = "anime-update-check" }.start()
    }

    private fun retryText(until: Long): String {
        val seconds = ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
        return if (seconds >= 60L) "Try again in ${(seconds + 59L) / 60L} min." else "Try again in ${seconds}s."
    }

    override fun install() {
        val update = stateFlow.value ?: return
        if (update.phase != dev.anime.ui.UpdatePhase.READY) return
        val dir = java.io.File(
            System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"),
            "anime/apps/${update.version}",
        )
        val binary = java.io.File(dir, "Anime/bin/Anime")
        if (!binary.isFile) return
        prefs.put("dismissed_tag", "v${update.version}")
        runCatching { ProcessBuilder(binary.absolutePath).start() }
            .onFailure { AppLog.e("update", "Could not restart into the update", it) }
        kotlin.system.exitProcess(0)
    }

    override fun dismiss() {
        stateFlow.value?.version?.takeIf { it.isNotBlank() }?.let { prefs.put("dismissed_tag", "v$it") }
        stateFlow.value = null
    }
}

fun main() {
    val appVersion = System.getProperty("anime.version") ?: "1.0.0"
    AppLog.init(java.io.File(Core.cacheDir, "anime.log"))
    CacheManager.ensure(Core.cacheDir)
    val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        AppLog.e("crash", "Uncaught on ${thread.name}", error)
        previousCrashHandler?.uncaughtException(thread, error)
    }
    Core.startProxy()
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.delay(20_000)
        while (true) {
            runCatching { Core.playback.probe() }
                .onFailure { AppLog.w("provider", "Provider health probe failed", it) }
            kotlinx.coroutines.delay(15 * 60_000L)
        }
    }
    val updateChecker = DesktopUpdateChecker(appVersion)
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 860.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Anime",
            state = windowState,
        ) {
            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        runCatching { updateChecker.checkForUpdates() }
                            .onFailure { AppLog.w("update", "Focus update check failed", it) }
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            App(
                Deps(
                    core = CoreHandles(Core.ctx, Core.aniList, Core.tvmaze, Core.details, Core.playback, Core.cacheDir),
                    player = DesktopPlayerHost { fullscreen ->
                        windowState.placement =
                            if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
                    },
                    images = DesktopImages,
                    progress = DesktopProgress,
                    appVersion = appVersion,
                    updateChecker = updateChecker,
                    clearCache = { CacheManager.clear(Core.cacheDir) },
                    cacheBytes = { CacheManager.size(Core.cacheDir) },
                    openUrl = { url ->
                        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                            .onFailure { AppLog.w("link", "Could not open $url", it) }
                    },
                    recents = FileRecentsStore(
                        java.io.File(
                            System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"),
                            "anime/recents.json",
                        ),
                    ),
                ),
            )
        }
    }
}
