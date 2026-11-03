package dev.anime.app

import android.app.Activity
import android.content.Context
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import android.net.Uri
import android.util.Rational
import android.util.TypedValue
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import dev.anime.ui.App
import dev.anime.ui.BackHost
import dev.anime.ui.CoreHandles
import dev.anime.model.SubtitleTrack
import dev.anime.ui.Deps
import dev.anime.ui.ImageHost
import dev.anime.cache.CacheManager
import dev.anime.store.FileRecentsStore
import dev.anime.util.AppLog
import dev.anime.ui.PlayerAudioTrack
import dev.anime.ui.PlayerSubtitleTrack
import dev.anime.ui.PlayerResolution
import dev.anime.ui.PlayerHost
import dev.anime.ui.PlayerSession
import dev.anime.ui.UpdateChecker
import dev.anime.ui.UpdatePhase
import dev.anime.ui.UpdateUiState
import dev.anime.util.GitHubRateLimit
import dev.anime.util.UpdateCheckGate
import dev.anime.util.isNewerVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val playerHost by lazy { AndroidPlayerHost(this) }
    private val updateChecker by lazy { AndroidUpdateChecker(this, BuildConfig.VERSION_NAME) }

    internal fun playerHostForDeps(): AndroidPlayerHost = playerHost

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playerHost.isActive && !isInPictureInPictureMode) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build(),
                )
            }.onFailure { AppLog.w("player", "Could not enter picture-in-picture", it) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerHost.setPictureInPicture(isInPictureInPictureMode)
        if (!isInPictureInPictureMode && playerHost.isActive) playerHost.applyImmersive()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        runCatching { updateChecker.checkForUpdates() }
            .onFailure { AppLog.w("update", "Resume update check failed", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        AppLog.init(java.io.File(filesDir, "anime.log"))
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("crash", "Uncaught on ${thread.name}", error)
            previousCrashHandler?.uncaughtException(thread, error)
        }
        AppCore.init(applicationContext)
        ProgressStore.init(applicationContext)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                runCatching { AppCore.playback.probe() }
                    .onFailure { AppLog.w("provider", "Provider health probe failed", it) }
                kotlinx.coroutines.delay(15 * 60_000L)
            }
        }
        setContent { App(androidDeps(this, updateChecker)) }
    }
}

private fun androidDeps(activity: MainActivity, updateChecker: AndroidUpdateChecker): Deps = Deps(
    core = CoreHandles(
        ctx = AppCore.ctx,
        aniList = AppCore.aniList,
        tvmaze = AppCore.tvmaze,
        details = AppCore.details,
        playback = AppCore.playback,
        cacheDir = java.io.File(activity.filesDir, "anime-cache"),
    ),
    player = activity.playerHostForDeps(),
    images = AndroidImages,
    progress = ProgressStore,
    openUrl = { url ->
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { AppLog.w("link", "Could not open $url", it) }
    },
    back = AndroidBackHost,
    recents = FileRecentsStore(java.io.File(activity.filesDir, "recents.json")),
    appVersion = BuildConfig.VERSION_NAME,
    updateChecker = updateChecker,
    clearCache = {
        CacheManager.clear(java.io.File(activity.filesDir, "anime-cache"))
    },
    cacheBytes = {
        CacheManager.size(java.io.File(activity.filesDir, "anime-cache"))
    },
)

private object AndroidBackHost : BackHost {
    @Composable
    override fun Back(onBack: () -> Unit) {
        BackHandler(onBack = onBack)
    }
}

private object AndroidImages : ImageHost {
    @Composable
    override fun Image(url: String?, modifier: Modifier, contentScale: ContentScale) {
        AsyncImage(model = url, contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}

internal class AndroidPlayerHost(private val activity: Activity) : PlayerHost {
    var isActive: Boolean = false
        private set
    private val pipFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val pictureInPicture = pipFlow

    fun setPictureInPicture(value: Boolean) {
        pipFlow.value = value
    }

    override fun onScreenShown() {
        isActive = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyImmersive()
    }

    override fun onScreenHidden() {
        isActive = false
        pipFlow.value = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        insets().show(WindowInsetsCompat.Type.systemBars())
    }

    override fun setFullscreen(fullscreen: Boolean) {
        applyImmersive()
    }

    fun applyImmersive() {
        val controller = insets()
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun insets() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    override suspend fun probeResolutions(url: String): List<PlayerResolution> = withContext(Dispatchers.IO) {
        runCatching {
            val master = Uri.parse(url).getQueryParameter("u") ?: return@runCatching emptyList()
            val result = AppCore.hls.handle(master)
            val text = (result as? dev.anime.web.HlsProxy.Result.Playlist)?.text ?: return@runCatching emptyList()
            parseResolutions(text).filter { it.id != "auto" }
        }.onFailure { AppLog.w("player", "Resolution probe failed", it) }
            .getOrDefault(emptyList())
    }

    @Composable
    override fun Video(
        url: String,
        subtitles: List<SubtitleTrack>,
        resumeMs: Long,
        onProgress: (Long, Long) -> Unit,
        onError: (String?) -> Unit,
        onSession: (PlayerSession?) -> Unit,
    ) {
        val context = LocalContext.current
        val currentOnError by rememberUpdatedState(onError)
        val currentOnProgress by rememberUpdatedState(onProgress)
        val currentOnSession by rememberUpdatedState(onSession)
        val player = remember {
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
        val session = remember(player) { AndroidSession(player) }
        val mediaSession = remember(player) { MediaSession.Builder(context, player).build() }
        val view = LocalView.current

        LaunchedEffect(player, session.playing) {
            view.keepScreenOn = session.playing
        }

        DisposableEffect(Unit) {
            currentOnSession(session)
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    AppLog.e("player", "ExoPlayer error", error)
                    currentOnError(error.message)
                }

                override fun onTracksChanged(tracks: Tracks) = session.refresh()

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    session.refresh()
                    if (!isPlaying && player.duration > 0 && player.currentPosition > 0) {
                        currentOnProgress(player.currentPosition, player.duration)
                    }
                }
            }
            player.addListener(listener)
            onDispose {
                view.keepScreenOn = false
                if (player.duration > 0) currentOnProgress(player.currentPosition, player.duration)
                player.removeListener(listener)
                mediaSession.release()
                player.release()
            }
        }

        LaunchedEffect(url) {
            repeat(2) { attempt ->
                val options = withContext(Dispatchers.IO) {
                    runCatching {
                        val master = Uri.parse(url).getQueryParameter("u") ?: return@runCatching emptyList()
                        val result = AppCore.hls.handle(master)
                        val text = (result as? dev.anime.web.HlsProxy.Result.Playlist)?.text ?: return@runCatching emptyList()
                        parseResolutions(text)
                    }.onFailure { AppLog.w("player", "Resolution probe failed", it) }
                        .getOrDefault(emptyList())
                }
                if (options.isNotEmpty()) {
                    session.updateResolutions(options)
                    return@LaunchedEffect
                }
                if (attempt == 0) delay(1_500)
            }
        }

        LaunchedEffect(url, subtitles) {
            val previous = if (player.mediaItemCount > 0) player.currentPosition else resumeMs
            val primary = HlsMediaSource.Factory(HlsDataSourceFactory(AppCore.hls))
                .createMediaSource(MediaItem.fromUri(url))
            val media = if (subtitles.isEmpty()) {
                primary
            } else {
                // Subtitles are plain files fetched directly; a subtitle failure
                // must never take the video stream down with it.
                val subtitleDataSource = DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                val subSources = subtitles.mapIndexed { index, track ->
                    val configuration = MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                        .setMimeType(track.mime ?: subtitleMimeType(track.url))
                        .apply {
                            track.label?.let { setLabel(it) }
                            track.language?.let { setLanguage(it) }
                            if (track.default || index == 0) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        }
                        .build()
                    SingleSampleMediaSource.Factory(subtitleDataSource)
                        .setTreatLoadErrorsAsEndOfStream(true)
                        .createMediaSource(configuration, C.TIME_UNSET)
                }
                MergingMediaSource(primary, *subSources.toTypedArray())
            }
            player.setMediaSource(media)
            player.prepare()
            if (previous > 0) player.seekTo(previous)
            player.playWhenReady = true
        }

        LaunchedEffect(player) {
            var sinceSave = 0L
            while (true) {
                delay(250)
                session.refresh()
                sinceSave += 250
                if (sinceSave >= 3_000 && player.duration > 0 && player.currentPosition > 0) {
                    sinceSave = 0
                    currentOnProgress(player.currentPosition, player.duration)
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                    val sp = when (ProgressStore.subtitleSize()) {
                        0 -> 14f
                        2 -> 22f
                        else -> 18f
                    }
                    subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private class AndroidSession(private val player: ExoPlayer) : PlayerSession {
    override var playing by mutableStateOf(false)
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
    override var subtitleTracks by mutableStateOf<List<PlayerSubtitleTrack>>(emptyList())
        private set
    override var activeSubtitleId by mutableStateOf<String?>(null)
        private set
    override var ended by mutableStateOf(false)
        private set

    private data class Override(val group: TrackGroup, val index: Int)
    private var overrides: Map<String, Override> = emptyMap()
    private var subtitleOverrides: Map<String, Override> = emptyMap()
    private var subtitlesUserDisabled = false
    private var subtitlesAutoSelected = false

    fun refresh() {
        playing = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
        ended = player.playbackState == Player.STATE_ENDED
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration.coerceAtLeast(0L)
        refreshTracks()
        refreshSubtitles()
    }

    private fun refreshSubtitles() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val tracks = mutableListOf<PlayerSubtitleTrack>()
        val map = mutableMapOf<String, Override>()
        var active: String? = null
        groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val id = group.mediaTrackGroup.id + ":sub:" + index
                val format = group.getTrackFormat(index)
                val label = format.label
                    ?: languageName(format.language)
                    ?: "Subtitle " + (index + 1)
                val selected = group.isTrackSelected(index)
                if (selected) active = id
                tracks += PlayerSubtitleTrack(
                    id = id,
                    label = label,
                    detail = format.sampleMimeType?.substringAfterLast('/')?.uppercase(),
                    selected = selected,
                )
                map[id] = Override(group.mediaTrackGroup, index)
            }
        }
        subtitleOverrides = map
        if (tracks != subtitleTracks) subtitleTracks = tracks
        activeSubtitleId = active

    }

    override fun selectSubtitle(id: String) {
        // Track snapshots can go stale between rebuilds; never let a bad one
        // interrupt playback.
        runCatching {
            val parameters = player.trackSelectionParameters.buildUpon()
            if (id == "off") {
                subtitlesUserDisabled = true
                parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                player.trackSelectionParameters = parameters.build()
                activeSubtitleId = null
                subtitleTracks = subtitleTracks.map { it.copy(selected = false) }
                return
            }
            val target = subtitleOverrides[id] ?: return
            val live = player.currentTracks.groups.any { group ->
                group.mediaTrackGroup.id == target.group.id && target.index < group.length
            }
            if (!live) {
                refreshSubtitles()
                return
            }
            subtitlesUserDisabled = false
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(target.group, target.index))
            player.trackSelectionParameters = parameters.build()
            activeSubtitleId = id
            subtitleTracks = subtitleTracks.map { it.copy(selected = it.id == id) }
        }.onFailure { AppLog.w("player", "Subtitle track selection failed", it) }
    }

    private fun refreshTracks() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val tracks = mutableListOf<PlayerAudioTrack>()
        val map = mutableMapOf<String, Override>()
        var active: String? = null
        groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val id = group.mediaTrackGroup.id + ":" + index
                val format = group.getTrackFormat(index)
                val label = format.label
                    ?: languageName(format.language)
                    ?: "Track " + (index + 1)
                val codec = format.sampleMimeType?.substringAfterLast('/')?.uppercase()
                val channels = format.channelCount.takeIf { it > 0 }?.let { it.toString() + "ch" }
                val detail = listOfNotNull(codec, channels).joinToString(" ").ifBlank { null }
                val selected = group.isTrackSelected(index)
                if (selected) active = id
                tracks += PlayerAudioTrack(id, label, detail, selected)
                map[id] = Override(group.mediaTrackGroup, index)
            }
        }
        overrides = map
        if (tracks != audioTracks) audioTracks = tracks
        activeAudioId = active
    }

    override fun playPause() {
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        this.positionMs = positionMs
    }

    override var resolutions by mutableStateOf<List<PlayerResolution>>(emptyList())
        private set
    override var activeResolutionId by mutableStateOf<String?>("auto")
        private set

    fun updateResolutions(options: List<PlayerResolution>) {
        if (options != resolutions) resolutions = options
    }

    override fun selectResolution(id: String) {
        if (resolutions.size <= 1) return
        activeResolutionId = id
        val bitrate = if (id == "auto") {
            Int.MAX_VALUE
        } else {
            id.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoBitrate(bitrate)
            .build()
        resolutions = resolutions.map { it.copy(selected = it.id == id) }
    }

    override fun selectAudio(id: String) {
        val target = overrides[id] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.group, target.index))
            .build()
        activeAudioId = id
        audioTracks = audioTracks.map { it.copy(selected = it.id == id) }
    }
}

private fun subtitleMimeType(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    return when {
        path.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ssa", ignoreCase = true) || path.endsWith(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
        else -> MimeTypes.TEXT_VTT
    }
}

private fun parseResolutions(text: String): List<PlayerResolution> {
    if (!text.contains("#EXT-X-STREAM-INF")) return emptyList()
    val found = LinkedHashMap<String, String>()
    var bandwidth: Long? = null
    var label: String? = null
    for (line in text.lineSequence()) {
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            label = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues?.get(2)?.let { it + "p" }
        } else if (line.isNotBlank() && !line.startsWith("#") && bandwidth != null) {
            found[bandwidth.toString()] = label ?: ((bandwidth ?: 0L) / 1000).toString() + " kbps"
            bandwidth = null
            label = null
        }
    }
    if (found.isEmpty()) return emptyList()
    return listOf(PlayerResolution("auto", "Auto", selected = true)) +
        found.map { (id, quality) -> PlayerResolution(id, quality) }
}

private const val UPDATE_REPO = "VLingott/anime"
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

internal class AndroidUpdateChecker(
    private val activity: MainActivity,
    private val currentVersion: String,
) : UpdateChecker {
    private val stateFlow = MutableStateFlow<UpdateUiState?>(null)
    override val state: StateFlow<UpdateUiState?> = stateFlow
    private val prefs = activity.getSharedPreferences("update-state", Context.MODE_PRIVATE)
    private val statusFlow = MutableStateFlow<String?>(null)
    override val status: StateFlow<String?> = statusFlow
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
                    if (current.phase == UpdatePhase.ERROR) stateFlow.value = current.copy(error = message)
                }
                return
            }
            is UpdateCheckGate.Result.Allowed -> {
                prefs.edit().putLong("last_check_ms", allowed.at).apply()
            }
        }
        statusFlow.value = "Checking for updates…"
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val connection = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "Anime/$currentVersion")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                val code = connection.responseCode
                val now = System.currentTimeMillis()
                if (code == 403 || code == 429) {
                    val retryAt = GitHubRateLimit.retryAt(connection, now) ?: (now + 60_000L)
                    gate.blockUntil(retryAt)
                    prefs.edit().putLong("rate_limited_until_ms", retryAt).apply()
                    val message = "GitHub rate limit reached. " + retryText(retryAt)
                    statusFlow.value = message
                    if (manual) stateFlow.value = UpdateUiState("", UpdatePhase.ERROR, error = message)
                    return@launch
                }
                if (code !in 200..299) error("GitHub returned HTTP $code")
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (connection.getHeaderField("X-RateLimit-Remaining")?.trim()?.toIntOrNull() == 0) {
                    GitHubRateLimit.retryAt(connection, now)?.let { retryAt ->
                        gate.blockUntil(retryAt)
                        prefs.edit().putLong("rate_limited_until_ms", retryAt).apply()
                    }
                }
                val root = Json.parseToJsonElement(text).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: error("Release has no tag")
                if (!isNewerVersion(currentVersion, tag)) {
                    prefs.edit().remove("dismissed_tag").remove("attempted_tag").apply()
                    statusFlow.value = "Anime is up to date."
                    stateFlow.value = null
                    return@launch
                }
                if (!manual && prefs.getString("dismissed_tag", null) == tag) {
                    statusFlow.value = "Anime is up to date."
                    stateFlow.value = null
                    return@launch
                }
                val versionLabel = tag.removePrefix("v")
                val apks = root["assets"]?.jsonArray?.mapNotNull { it.jsonObject }
                    ?.filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                    .orEmpty()
                val asset = apks.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.contains(versionLabel) == true }
                    ?: apks.firstOrNull()
                    ?: error("Release $tag has no APK")
                val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: error("Release $tag has no APK download URL")
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "anime-$versionLabel.apk")

                if (!apk.isFile || apk.length() == 0L) {
                    stateFlow.value = UpdateUiState(versionLabel, UpdatePhase.DOWNLOADING)
                    val download = URL(downloadUrl).openConnection() as HttpURLConnection
                    download.connectTimeout = 10_000
                    download.readTimeout = 30_000
                    download.setRequestProperty("User-Agent", "Anime/$currentVersion")
                    val total = download.contentLengthLong
                    download.inputStream.use { input ->
                        apk.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) {
                                    stateFlow.value = UpdateUiState(
                                        versionLabel,
                                        UpdatePhase.DOWNLOADING,
                                        progress = copied.toFloat() / total,
                                    )
                                }
                            }
                        }
                    }
                }

                @Suppress("DEPRECATION")
                val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
                val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    archive?.longVersionCode ?: 0L
                } else {
                    @Suppress("DEPRECATION")
                    (archive?.versionCode ?: 0).toLong()
                }
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    installed.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installed.versionCode.toLong()
                }
                if (newCode <= currentCode) {
                    prefs.edit().putString("dismissed_tag", tag).apply()
                    statusFlow.value = "Anime is up to date."
                    stateFlow.value = null
                    return@launch
                }
                @Suppress("DEPRECATION")
                val archiveSignatures = archive?.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
                @Suppress("DEPRECATION")
                val installedInfo = activity.packageManager.getPackageInfo(activity.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val installedSignatures = installedInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
                if (archiveSignatures.isNotEmpty() && installedSignatures.isNotEmpty() && archiveSignatures != installedSignatures) {
                    apk.delete()
                    statusFlow.value = "Update failed."
                    stateFlow.value = UpdateUiState(
                        versionLabel,
                        UpdatePhase.ERROR,
                        error = "The downloaded update is not signed by the same key.",
                    )
                    return@launch
                }
                statusFlow.value = "Update available."
                stateFlow.value = UpdateUiState(versionLabel, UpdatePhase.READY)
            }.onFailure { error ->
                AppLog.w("update", "Update check failed", error)
                statusFlow.value = "Update check failed."
                stateFlow.value = UpdateUiState(
                    version = "",
                    phase = UpdatePhase.ERROR,
                    error = error.message ?: "Could not check for updates.",
                )
            }
        }
    }

    private fun retryText(until: Long): String {
        val seconds = ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
        return if (seconds >= 60L) "Try again in ${(seconds + 59L) / 60L} min." else "Try again in ${seconds}s."
    }

    override fun install() {
        val update = stateFlow.value ?: return
        if (update.phase != UpdatePhase.READY) return
        val dir = File(activity.cacheDir, "updates")
        val apk = File(dir, "anime-${update.version}.apk")
        if (!apk.isFile) return
        prefs.edit().putString("dismissed_tag", "v${update.version}").apply()
        installUpdate(activity, apk)
        stateFlow.value = null
    }

    override fun dismiss() {
        stateFlow.value?.version?.takeIf { it.isNotBlank() }?.let { version ->
            prefs.edit().putString("dismissed_tag", "v$version").apply()
        }
        stateFlow.value = null
    }
}

private fun installUpdate(context: Context, apk: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "Allow installs for Anime to update", Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        AppLog.e("update", "Could not open the package installer", it)
        Toast.makeText(context, "Could not open the installer: ${it.message}", Toast.LENGTH_LONG).show()
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
