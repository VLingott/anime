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
import dev.anime.search.PlaybackService
import dev.anime.store.FileRecentsStore
import dev.anime.source.AniListSource
import dev.anime.source.SourceContext
import dev.anime.source.TvmazeEpisodesSource
import dev.anime.source.buildHttpClient
import dev.anime.ui.App
import dev.anime.ui.CoreHandles
import dev.anime.ui.Deps
import dev.anime.ui.ImageHost
import dev.anime.ui.PlayerHost
import dev.anime.ui.PlayerResolution
import dev.anime.ui.PlayerSession
import dev.anime.ui.UpdateChecker
import dev.anime.ui.isNewerVersion
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
private const val PORT = 18471

private object Core {
    val cacheDir: java.io.File = java.io.File(
        System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"),
        "anime",
    )
    val ctx: SourceContext by lazy { SourceContext(buildHttpClient(UA)) }
    val aniList: AniListSource by lazy { AniListSource(cacheDir) }
    val tvmaze: TvmazeEpisodesSource by lazy { TvmazeEpisodesSource() }
    val details: Details by lazy { Details(ctx) }
    val hls: HlsProxy by lazy { HlsProxy(ctx, dir = System.getProperty("java.io.tmpdir") + "/anime-hls-cache") }
    val playback: PlaybackService by lazy {
        PlaybackService(
            ctx, details,
            urlTransform = { url -> "http://127.0.0.1:$PORT$url" },
            health = dev.anime.search.ProviderHealth(java.io.File(System.getProperty("java.io.tmpdir"), "anime-provider-health.json")),
            cacheDir = cacheDir,
        )
    }

    private var proxy: Proxy? = null

    fun startProxy() {
        if (proxy != null) return
        proxy = Proxy().also { runCatching { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) } }
    }

    class Proxy : NanoHTTPD("127.0.0.1", PORT) {
        override fun serve(session: IHTTPSession): Response {
            if (!session.uri.startsWith("/api/hls")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            val u = session.parameters["u"]?.firstOrNull()
            val link = session.parameters["link"]?.firstOrNull()
            val result = runBlocking {
                runCatching { if (!link.isNullOrBlank()) hls.openLink(link) else hls.handle(u.orEmpty()) }.getOrNull()
            } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "gone")
            return when (result) {
                is HlsProxy.Result.Playlist -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", result.text)
                is HlsProxy.Result.Binary -> newFixedLengthResponse(
                    Response.Status.OK, result.contentType, result.bytes.inputStream(), result.bytes.size.toLong(),
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
    override fun audioPreference(id: Long) = Progress.audioPreference(id)
    override fun saveAudioPreference(id: Long, label: String?) = Progress.saveAudioPreference(id, label)
}

private object DesktopImages : ImageHost {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    @Composable
    override fun Image(url: String?, modifier: Modifier, contentScale: ContentScale) {
        var bitmap by remember(url) { mutableStateOf(url?.let { cache[it] }) }
        LaunchedEffect(url) {
            if (url == null || bitmap != null) return@LaunchedEffect
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(url).openStream().use { it.readBytes() }
                    ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
                }.getOrNull()
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
        }.getOrDefault(emptyList())
    }

    @Composable
    override fun Video(
        url: String,
        resumeMs: Long,
        onProgress: (Long, Long) -> Unit,
        onError: () -> Unit,
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
                    onError = {
                        failure = "Playback failed."
                        currentError()
                    },
                    onEnded = { },
                )
            }.onSuccess {
                engine = it
                currentSession(it)
            }.onFailure {
                println("[mpv] engine: " + it)
                failure = "Cannot start playback: " + (it.message ?: "libmpv not found")
                currentError()
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
    override fun checkForUpdates() {
        Thread {
            runCatching {
                val connection = java.net.URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "Anime/$currentVersion")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val prefs = java.util.prefs.Preferences.userRoot().node("dev/anime/desktop/updates")
                if (!isNewerVersion(currentVersion, tag)) {
                    prefs.remove("attempted_tag")
                    return@runCatching
                }
                if (prefs.get("attempted_tag", null) == tag) return@runCatching
                prefs.put("attempted_tag", tag)
                val page = root["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                java.awt.Desktop.getDesktop().browse(java.net.URI(page))
            }
        }.apply { isDaemon = true; name = "anime-update-check" }.start()
    }
}

fun main() {
    val appVersion = System.getProperty("anime.version") ?: "1.0.0"
    Core.startProxy()
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.delay(20_000)
        while (true) {
            runCatching { Core.playback.probe() }
            kotlinx.coroutines.delay(15 * 60_000L)
        }
    }
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 860.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Anime",
            state = windowState,
        ) {
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
                    updateChecker = DesktopUpdateChecker(appVersion),
                    openUrl = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
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
