package dev.anime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import dev.anime.model.DetailResult
import dev.anime.model.PlaySource
import dev.anime.model.SubtitleTrack
import dev.anime.model.SearchHit
import dev.anime.model.StreamResolution
import dev.anime.search.Details
import dev.anime.search.PlaybackService
import dev.anime.search.SeriesRepository
import dev.anime.store.RecentSeries
import dev.anime.util.AppLog
import dev.anime.util.absoluteEpisodeIndex
import dev.anime.util.cycleFilter
import dev.anime.util.nextEpisode
import dev.anime.store.RecentsStore
import dev.anime.source.AniListFilter
import dev.anime.source.AniListSource
import dev.anime.source.AniListTag
import dev.anime.source.TvmazeEpisode
import dev.anime.source.TvmazeEpisodesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CoreHandles(
    val ctx: dev.anime.source.SourceContext,
    val aniList: AniListSource,
    val tvmaze: TvmazeEpisodesSource,
    val details: Details,
    val playback: PlaybackService,
    cacheDir: java.io.File? = null,
) {
    val series: SeriesRepository = SeriesRepository(details, tvmaze, cacheDir)
    val subtitles: dev.anime.source.SubtitleService = dev.anime.source.SubtitleService(cacheDir)
}

data class PlayerResolution(
    val id: String,
    val label: String,
    val selected: Boolean = false,
)

data class PlayerResolutionOption(
    val label: String,
    val id: String?,
    val sourceIndex: Int,
    val selected: Boolean,
)

data class PlayerLanguage(
    val label: String,
    val trackId: String?,
    val sourceIndex: Int?,
    val selected: Boolean,
)

data class PlayerAudioTrack(
    val id: String,
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
)

data class PlayerSubtitleTrack(
    val id: String,
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val forced: Boolean = false,
)

interface PlayerSession {
    val playing: Boolean
    val buffering: Boolean
    val positionMs: Long
    val durationMs: Long
    val audioTracks: List<PlayerAudioTrack>
    val activeAudioId: String?
    val resolutions: List<PlayerResolution>
    val activeResolutionId: String?
    val subtitleTracks: List<PlayerSubtitleTrack> get() = emptyList()
    val activeSubtitleId: String? get() = null
    /** true once playback reached the end of the media. */
    val ended: Boolean get() = false

    fun playPause()
    fun seekTo(positionMs: Long)
    fun selectAudio(id: String)
    fun selectResolution(id: String) {}
    fun selectSubtitle(id: String) {}
}

interface PlayerHost {
    val pictureInPicture: StateFlow<Boolean>
    fun onScreenShown()
    fun onScreenHidden()
    fun setFullscreen(fullscreen: Boolean) {}

    @Composable
    fun Video(
        url: String,
        resumeMs: Long,
        onProgress: (positionMs: Long, durationMs: Long) -> Unit,
        onError: (reason: String?) -> Unit,
        onSession: (PlayerSession?) -> Unit = {},
    )

    suspend fun probeResolutions(url: String): List<PlayerResolution> = emptyList()
}

enum class UpdatePhase { CHECKING, DOWNLOADING, READY, ERROR }

data class UpdateUiState(
    val version: String,
    val phase: UpdatePhase,
    val progress: Float? = null,
    val error: String? = null,
)

interface UpdateChecker {
    val state: StateFlow<UpdateUiState?>
    val status: StateFlow<String?>
    fun checkForUpdates(manual: Boolean = false)
    fun install()
    fun dismiss()
}

object NoopUpdateChecker : UpdateChecker {
    private val empty = MutableStateFlow<UpdateUiState?>(null)
    private val noStatus = MutableStateFlow<String?>(null)
    override val state: StateFlow<UpdateUiState?> = empty
    override val status: StateFlow<String?> = noStatus
    override fun checkForUpdates(manual: Boolean) = Unit
    override fun install() = Unit
    override fun dismiss() = Unit
}

interface BackHost {
    @Composable
    fun Back(onBack: () -> Unit)
}

object NoBackHost : BackHost {
    @Composable
    override fun Back(onBack: () -> Unit) = Unit
}

interface ImageHost {
    @Composable
    fun Image(url: String?, modifier: Modifier, contentScale: ContentScale)
}

enum class TitleLanguage { ROMAJI, ENGLISH, NATIVE }

private val AudioLanguages = listOf("Japanese", "English", "German")

data class LastPlayed(val season: Int, val episode: Int, val positionMs: Long)

interface ProgressStore {
    fun resumeMs(id: Long, season: Int, episode: Int): Long
    fun save(id: Long, season: Int, episode: Int, positionMs: Long, durationMs: Long)
    fun fraction(id: Long, season: Int, episode: Int): Float?
    fun last(id: Long): LastPlayed? = null
    fun audioPreference(id: Long): String? = null
    fun saveAudioPreference(id: Long, label: String?) {}
    fun titleLanguage(): Int = 0
    fun saveTitleLanguage(value: Int) {}
    fun defaultAudioLanguage(): String = "Japanese"
    fun saveDefaultAudioLanguage(value: String) {}
    fun hideAdultContent(): Boolean = true
    fun saveHideAdultContent(value: Boolean) {}
    fun disabledProviders(): Set<String> = emptySet()
    fun saveDisabledProviders(value: Set<String>) {}
    fun autoplayNext(): Boolean = true
    fun saveAutoplayNext(value: Boolean) {}
    fun subtitleSize(): Int = 1
    fun saveSubtitleSize(value: Int) {}
    fun onlineSubtitles(): Boolean = true
    fun saveOnlineSubtitles(value: Boolean) {}
    fun subtitleLanguage(): String = "English"
    fun saveSubtitleLanguage(value: String) {}
    fun subtitlesEnabled(id: Long): Boolean? = null
    fun saveSubtitlesEnabled(id: Long, value: Boolean) {}
    fun removeSeries(id: Long) {}
}

data class Deps(
    val core: CoreHandles,
    val player: PlayerHost,
    val images: ImageHost,
    val progress: ProgressStore,
    val openUrl: (String) -> Unit = {},
    val back: BackHost = NoBackHost,
    val recents: RecentsStore? = null,
    val appVersion: String = "0.1",
    val updateChecker: UpdateChecker = NoopUpdateChecker,
    val clearCache: () -> Unit = {},
    val cacheBytes: () -> Long = { 0L },
)

data class SearchFilters(
    val query: String = "",
    val genres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val minScore: Int = 0,
    val formats: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val season: String? = null,
    val seasonYear: String = "",
    val sort: String = "SCORE_DESC",
    val showFilters: Boolean = false,
)

private val Colors = darkColorScheme(
    primary = Color(0xFFF5F5F2),
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF5F5F2),
    surface = Color(0xFF0C0C0C),
    onSurface = Color(0xFFF5F5F2),
    surfaceVariant = Color(0xFF151515),
    onSurfaceVariant = Color(0xFFC9C9C3),
    outline = Color(0xFF232323),
)

private val PlayerArgsSaver = listSaver<PlayerArgs?, String>(
    save = { args ->
        if (args == null) emptyList()
        else listOf(
            args.id.toString(),
            args.season.toString(),
            args.episode.toString(),
            args.label,
            args.title,
            args.cover.orEmpty(),
            args.score?.toString().orEmpty(),
            args.episodeTitle.orEmpty(),
            args.absoluteEpisode?.toString().orEmpty(),
        )
    },
    restore = { values ->
        if (values.size < 9) null
        else PlayerArgs(
            id = values[0].toLongOrNull() ?: return@listSaver null,
            season = values[1].toIntOrNull() ?: 1,
            episode = values[2].toIntOrNull() ?: 1,
            label = values[3],
            title = values[4],
            cover = values[5].ifBlank { null },
            score = values[6].toDoubleOrNull(),
            episodeTitle = values[7].ifBlank { null },
            absoluteEpisode = values[8].toIntOrNull(),
        )
    },
)

private data class PlayerArgs(
    val id: Long,
    val season: Int,
    val episode: Int,
    val label: String,
    val title: String,
    val cover: String?,
    val score: Double? = null,
    val episodeTitle: String? = null,
    val absoluteEpisode: Int? = null,
)

@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0C0C0C),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    when (state.phase) {
                        UpdatePhase.CHECKING -> "Checking for updates…"
                        UpdatePhase.DOWNLOADING -> "Downloading update…"
                        UpdatePhase.READY -> "Update ready"
                        UpdatePhase.ERROR -> "Update failed"
                    },
                    color = Color(0xFFF5F5F2),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Version ${state.version}",
                    color = Color(0xFF9B9B96),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                when (state.phase) {
                    UpdatePhase.DOWNLOADING -> {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 14.dp).height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.15f)),
                        ) {
                            val progress = state.progress
                            if (progress != null) {
                                Box(
                                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp)
                                        .background(Color(0xFFF5F5F2)),
                                )
                            }
                        }
                    }
                    UpdatePhase.ERROR -> {
                        state.error?.let {
                            Text(it, color = Color(0xFFF0A0A0), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    else -> Unit
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.phase == UpdatePhase.READY) "Later" else "Dismiss",
                        color = Color(0xFF9B9B96),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    when (state.phase) {
                        UpdatePhase.READY -> Text(
                            "Install",
                            color = Color(0xFF0A0A0A),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFFF5F5F2))
                                .clickable { onInstall() }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                        UpdatePhase.ERROR -> Text(
                            "Retry",
                            color = Color(0xFFF5F5F2),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFF151515))
                                .clickable { onRetry() }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
fun App(deps: Deps) {
    LaunchedEffect(Unit) { deps.updateChecker.checkForUpdates() }
    val updateState by deps.updateChecker.state.collectAsState()
    var filters by remember { mutableStateOf(SearchFilters()) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var player by rememberSaveable(stateSaver = PlayerArgsSaver) { mutableStateOf<PlayerArgs?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var titleLanguage by remember { mutableStateOf(TitleLanguage.entries.getOrElse(deps.progress.titleLanguage()) { TitleLanguage.ROMAJI }) }
    var hideAdult by remember { mutableStateOf(deps.progress.hideAdultContent()) }
    var disabledProviders by remember { mutableStateOf(deps.progress.disabledProviders()) }
    var autoplayNext by remember { mutableStateOf(deps.progress.autoplayNext()) }
    var subtitleSize by remember { mutableStateOf(deps.progress.subtitleSize()) }
    var onlineSubtitles by remember { mutableStateOf(deps.progress.onlineSubtitles()) }
    var subtitleLanguage by remember { mutableStateOf(deps.progress.subtitleLanguage()) }

    LaunchedEffect(Unit) {
        deps.core.playback.setDisabledProviders(disabledProviders)
        deps.core.playback.setAdultContentAllowed(!hideAdult)
    }

    if (updateState != null) {
        UpdateDialog(
            state = updateState!!,
            onInstall = deps.updateChecker::install,
            onRetry = { deps.updateChecker.checkForUpdates(manual = true) },
            onDismiss = deps.updateChecker::dismiss,
        )
    }

    MaterialTheme(colorScheme = Colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            val currentPlayer = player
            if (currentPlayer != null) {
                PlayerOverlay(
                    deps = deps,
                    args = currentPlayer,
                    titleLanguage = titleLanguage,
                    autoplayNext = autoplayNext,
                    onlineSubtitles = onlineSubtitles,
                    subtitleLanguage = subtitleLanguage,
                    subtitleSize = subtitleSize,
                    onClose = { player = null; refreshTick++ },
                    onPlayNext = { next -> player = next },
                )
            } else if (selected != null) {
                DetailScreen(
                    deps = deps,
                    id = selected!!,
                    refreshTick = refreshTick,
                    onBack = { selected = null },
                    onPlay = { season, ep, label, title, cover, score, episodeTitle, absoluteEpisode ->
                        player = PlayerArgs(selected!!, season, ep, label, title, cover, score, episodeTitle, absoluteEpisode)
                    },
                    titleLanguage = titleLanguage,
                    onCycleTitleLanguage = {
                        val next = TitleLanguage.entries[(titleLanguage.ordinal + 1) % TitleLanguage.entries.size]
                        titleLanguage = next
                        deps.progress.saveTitleLanguage(next.ordinal)
                    },
                    onGenre = { genre ->
                        filters = SearchFilters(genres = setOf(genre))
                        selected = null
                        searchMode = true
                    },
                    onTag = { tag ->
                        filters = SearchFilters(tags = setOf(tag))
                        selected = null
                        searchMode = true
                    },
                )
            } else if (searchMode) {
                SearchScreen(
                    deps = deps,
                    filters = filters,
                    includeAdult = !hideAdult,
                    titleLanguage = titleLanguage,
                    onFilters = { filters = it },
                    onOpen = { selected = it },
                    onBack = { searchMode = false },
                )
            } else {
                HomeScreen(
                    deps = deps,
                    refreshTick = refreshTick,
                    titleLanguage = titleLanguage,
                    onSetTitleLanguage = { next ->
                        titleLanguage = next
                        deps.progress.saveTitleLanguage(next.ordinal)
                    },
                    hideAdult = hideAdult,
                    onSetHideAdult = { value ->
                        hideAdult = value
                        deps.progress.saveHideAdultContent(value)
                        deps.core.playback.setAdultContentAllowed(!value)
                        if (value) {
                            filters = filters.copy(
                                genres = filters.genres - AniListSource.ADULT_GENRE,
                                tags = filters.tags.filterNot { it.equals(AniListSource.ADULT_GENRE, true) }.toSet(),
                                excludedTags = filters.excludedTags
                                    .filterNot { it.equals(AniListSource.ADULT_GENRE, true) }.toSet(),
                            )
                        }
                    },
                    disabledProviders = disabledProviders,
                    onToggleProvider = { id ->
                        val next = if (id in disabledProviders) disabledProviders - id else disabledProviders + id
                        disabledProviders = next
                        deps.progress.saveDisabledProviders(next)
                        deps.core.playback.setDisabledProviders(next)
                    },
                    autoplayNext = autoplayNext,
                    onSetAutoplayNext = { value ->
                        autoplayNext = value
                        deps.progress.saveAutoplayNext(value)
                    },
                    subtitleSize = subtitleSize,
                    onSetSubtitleSize = { value ->
                        subtitleSize = value
                        deps.progress.saveSubtitleSize(value)
                    },
                    onlineSubtitles = onlineSubtitles,
                    onSetOnlineSubtitles = { value ->
                        onlineSubtitles = value
                        deps.progress.saveOnlineSubtitles(value)
                    },
                    subtitleLanguage = subtitleLanguage,
                    onSetSubtitleLanguage = { value ->
                        subtitleLanguage = value
                        deps.progress.saveSubtitleLanguage(value)
                    },
                    onSearch = { searchMode = true },
                    onResume = { recent ->
                        val displayTitle = recentTitle(
                            recent,
                            titleLanguage,
                            deps.core.series.peek(recent.id)?.detail,
                        )
                        player = PlayerArgs(
                            id = recent.id,
                            season = recent.season,
                            episode = recent.episode,
                            label = "$displayTitle · S${recent.season} E${recent.episode}",
                            title = displayTitle,
                            cover = recent.cover,
                            score = recent.score,
                            episodeTitle = recent.episodeTitle,
                            absoluteEpisode = recent.absoluteEpisode ?: deps.core.series.peek(recent.id)?.seasons?.let {
                                absoluteEpisodeIndex(it, recent.season, recent.episode)
                            },
                        )
                    },
                )
            }
        }
    }
}

private enum class HomeSort(val label: String) {
    RECENT("Recently watched"),
    TITLE("Title"),
    RATING("Rating"),
}

private data class RecentEntry(
    val recent: RecentSeries,
    val title: String,
    val score: Double?,
    val newEpisodes: Boolean,
    val fullyWatched: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    deps: Deps,
    refreshTick: Int,
    titleLanguage: TitleLanguage,
    onSetTitleLanguage: (TitleLanguage) -> Unit,
    hideAdult: Boolean,
    onSetHideAdult: (Boolean) -> Unit,
    disabledProviders: Set<String>,
    onToggleProvider: (String) -> Unit,
    autoplayNext: Boolean,
    onSetAutoplayNext: (Boolean) -> Unit,
    subtitleSize: Int,
    onSetSubtitleSize: (Int) -> Unit,
    onlineSubtitles: Boolean,
    onSetOnlineSubtitles: (Boolean) -> Unit,
    subtitleLanguage: String,
    onSetSubtitleLanguage: (String) -> Unit,
    onSearch: () -> Unit,
    onResume: (RecentSeries) -> Unit,
) {
    var localTick by remember { mutableIntStateOf(0) }
    val recents = remember(refreshTick, localTick) { deps.recents?.list().orEmpty() }
    var sort by remember { mutableStateOf(HomeSort.RECENT) }
    var pendingRemoval by remember { mutableStateOf<RecentSeries?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val updateStatus by deps.updateChecker.status.collectAsState()
    var defaultAudio by remember { mutableStateOf(deps.progress.defaultAudioLanguage()) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showSettings) {
        if (showSettings) cacheBytes = withContext(Dispatchers.IO) { deps.cacheBytes() }
    }

    // Keep "new episode" markers fresh for airing shows without opening each one.
    var lastRecentRefresh by remember { mutableLongStateOf(0L) }
    LaunchedEffect(refreshTick, recents.map { it.id }) {
        val now = System.currentTimeMillis()
        if (recents.isEmpty() || now - lastRecentRefresh < 10 * 60_000L) return@LaunchedEffect
        lastRecentRefresh = now
        val changed = withContext(Dispatchers.IO) {
            var changed = false
            for (recent in recents.take(10)) {
                val view = runCatching { deps.core.series.load(recent.id, deps.core.ctx) }
                    .onFailure { AppLog.w("series", "Recent metadata refresh failed for " + recent.id, it) }
                    .getOrNull() ?: continue
                if (view.detail.status.equals("RELEASING", ignoreCase = true)) {
                    runCatching { deps.core.series.load(recent.id, deps.core.ctx, refresh = true) }
                        .onFailure { AppLog.w("series", "Recent episode refresh failed for " + recent.id, it) }
                    changed = true
                }
            }
            changed
        }
        if (changed) localTick += 1
    }

    val items = remember(recents, sort, titleLanguage, localTick) {
        recents.map { recent ->
            val cachedView = deps.core.series.peek(recent.id)
            val detail = cachedView?.detail
            val seasons = cachedView?.seasons
            val title = recentTitle(recent, titleLanguage, detail)
            val fraction = deps.progress.fraction(recent.id, recent.season, recent.episode)
                ?: recent.positionMs.toFloat().div(recent.durationMs.toFloat()).takeIf { recent.durationMs > 0 }
            val total = seasons?.sumOf { (_, episodes) -> episodes.count { it.number != null } } ?: 0
            val watched = recent.absoluteEpisode
                ?: seasons?.let { absoluteEpisodeIndex(it, recent.season, recent.episode) }
            val fullyWatched = total > 0 && watched == total && (fraction ?: 0f) >= 0.95f
            val newEpisodes = total > 0 && watched != null && total > watched
            RecentEntry(
                recent = recent,
                title = title,
                score = recent.score ?: detail?.averageScore,
                newEpisodes = newEpisodes,
                fullyWatched = fullyWatched,
            )
        }.sortedWith(
            when (sort) {
                HomeSort.RECENT -> compareBy<RecentEntry> { it.fullyWatched }
                    .thenByDescending { it.recent.updatedAt }
                HomeSort.TITLE -> compareBy<RecentEntry> { it.fullyWatched }
                    .thenBy { it.title.lowercase() }
                HomeSort.RATING -> compareBy<RecentEntry> { it.fullyWatched }
                    .thenByDescending { it.score ?: -1.0 }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF151515))
                    .border(1.dp, Color(0xFF232323), RoundedCornerShape(999.dp))
                    .clickable { onSearch() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Canvas(Modifier.size(15.dp)) {
                val color = Color(0xFF9B9B96)
                val stroke = 1.7.dp.toPx()
                drawCircle(color, radius = size.width * 0.34f, center = Offset(size.width * 0.4f, size.height * 0.4f), style = Stroke(stroke))
                drawLine(
                    color,
                    Offset(size.width * 0.64f, size.height * 0.64f),
                    Offset(size.width * 0.94f, size.height * 0.94f),
                    stroke,
                    StrokeCap.Round,
                )
            }
                Spacer(Modifier.width(10.dp))
                Text("Search anime…", color = Color(0xFF9B9B96), fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            HomeSortButton(sort) { sort = it }
            Spacer(Modifier.width(8.dp))
            IconSquareButton(SquareIcon.Settings, active = false) { showSettings = true }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recents.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Nothing watched yet", color = Color(0xFFF5F5F2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Search for a series to start.",
                            color = Color(0xFF9B9B96),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                items(items, key = { it.recent.id }) { entry ->
                    RecentRow(
                        deps = deps,
                        recent = entry.recent,
                        titleLanguage = titleLanguage,
                        newEpisodes = entry.newEpisodes,
                        fullyWatched = entry.fullyWatched,
                        onResume = onResume,
                        onLongPress = { pendingRemoval = entry.recent },
                    )
                }
            }
        }
    }

    if (showSettings) {
        Dialog(onDismissRequest = { showSettings = false }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF0C0C0C),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(
                    Modifier.heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text("Settings", color = Color(0xFFF5F5F2), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Anime ${deps.appVersion}", color = Color(0xFF9B9B96), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        "Check for updates",
                        color = Color(0xFFF5F5F2),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 18.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF151515))
                            .clickable { deps.updateChecker.checkForUpdates(manual = true) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                    Text(
                        updateStatus ?: "Update checks are limited to once per minute.",
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Label("TITLE LANGUAGE")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TitleLanguage.entries.forEach { language ->
                            Chip(titleLanguageLabel(language), selected = titleLanguage == language) {
                                onSetTitleLanguage(language)
                            }
                        }
                    }
                    Label("DEFAULT AUDIO LANGUAGE")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AudioLanguages.forEach { language ->
                            Chip(language, selected = defaultAudio == language) {
                                defaultAudio = language
                                deps.progress.saveDefaultAudioLanguage(language)
                            }
                        }
                    }
                    Label("ADULT CONTENT")
                    Chip("Hide adult content", selected = hideAdult) { onSetHideAdult(!hideAdult) }
                    Text(
                        "Hides adult search results and tag chips.",
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Label("PROVIDERS")
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        deps.core.playback.availableProviders()
                            .filter { !it.adultOnly || !hideAdult }
                            .forEach { provider ->
                                Chip(
                                    provider.name,
                                    selected = provider.id !in disabledProviders,
                                ) { onToggleProvider(provider.id) }
                            }
                    }
                    Text(
                        "Disabled providers are skipped when resolving streams.",
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Label("PLAYBACK")
                    Chip("Autoplay next episode", selected = autoplayNext) { onSetAutoplayNext(!autoplayNext) }
                    Text(
                        "Starts the next episode after a short countdown when one is available.",
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Label("SUBTITLES")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Small", "Normal", "Large").forEachIndexed { index, label ->
                            Chip(label, selected = subtitleSize == index) { onSetSubtitleSize(index) }
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Download subtitles", selected = onlineSubtitles) { onSetOnlineSubtitles(!onlineSubtitles) }
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("English", "German", "Japanese").forEach { language ->
                            Chip(language, selected = subtitleLanguage == language) { onSetSubtitleLanguage(language) }
                        }
                    }
                    Text(
                        "Online fetches (OpenSubtitles) only run when the stream ships no subtitle track.",
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Clear cached data",
                        color = Color(0xFFF5F5F2),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF151515))
                            .clickable {
                                deps.clearCache()
                                cacheBytes = 0L
                                showSettings = false
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                    Text(
                        "Metadata cache: " + formatCacheBytes(cacheBytes),
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Removes search, series, stream and image metadata caches. Progress is kept.",
                        color = Color(0xFF9B9B96),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                        Text(
                            "Close",
                            color = Color(0xFFF5F9F2),
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { showSettings = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    val removal = pendingRemoval
    if (removal != null) {
        Dialog(onDismissRequest = { pendingRemoval = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF0C0C0C),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Remove from history?", color = Color(0xFFF5F5F2), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Watch progress and history for ${removal.title} will be deleted.",
                        color = Color(0xFF9B9B96),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Cancel",
                            color = Color(0xFF9B9B96),
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { pendingRemoval = null }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        Text(
                            "Remove",
                            color = Color(0xFFF5F5F2),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFF2A1515))
                                .clickable {
                                    deps.recents?.remove(removal.id)
                                    deps.progress.removeSeries(removal.id)
                                    pendingRemoval = null
                                    localTick += 1
                                }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSortButton(current: HomeSort, onSelect: (HomeSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconSquareButton(SquareIcon.Sort, active = current != HomeSort.RECENT) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HomeSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            color = if (option == current) Color(0xFFF5F5F2) else Color(0xFFC9C9C3),
                            fontSize = 12.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun RecentRow(
    deps: Deps,
    recent: RecentSeries,
    titleLanguage: TitleLanguage,
    newEpisodes: Boolean,
    fullyWatched: Boolean,
    onResume: (RecentSeries) -> Unit,
    onLongPress: () -> Unit,
) {
    val cachedDetail = remember(recent.id) { deps.core.series.peek(recent.id)?.detail }
    val displayRecentTitle = recentTitle(recent, titleLanguage, cachedDetail)
    val displayScore = recent.score ?: cachedDetail?.averageScore
    val fraction = deps.progress.fraction(recent.id, recent.season, recent.episode)
        ?: recent.positionMs.toFloat().div(recent.durationMs.toFloat()).takeIf { recent.durationMs > 0 }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0C0C0C))
            .pointerInput(recent.id) {
                detectTapGestures(
                    onTap = { onResume(recent) },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverPadding = 4.dp
        val coverCorner = 10.dp
        Box(Modifier.width(70.dp).aspectRatio(2f / 3f)) {
            deps.images.Image(
                recent.cover,
                Modifier.fillMaxSize()
                    .padding(coverPadding)
                    .clip(RoundedCornerShape(coverCorner))
                    .background(Color(0xFF151515)),
                ContentScale.Crop,
            )
            displayScore?.takeIf { it > 0 }?.let { score ->
                ImageRatingRing(
                    score,
                    imageCorner = coverCorner,
                    imagePadding = coverPadding,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayRecentTitle,
                color = Color(0xFFF5F5F2),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "S${recent.season} E${recent.episode}",
                    color = Color(0xFF9B9B96),
                    fontSize = 12.sp,
                )
                if (newEpisodes && !fullyWatched) {
                    Text(
                        "NEW",
                        color = Color(0xFF0A0A0A),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(start = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFF5F5F2))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (fraction != null) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp)
                            .background(Color(0xFFF5F5F2)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(
    deps: Deps,
    filters: SearchFilters,
    includeAdult: Boolean,
    titleLanguage: TitleLanguage,
    onFilters: (SearchFilters) -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    deps.back.Back { onBack() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun filterFor(pageNumber: Int) = AniListFilter(
        search = filters.query.trim(),
        includeAdult = includeAdult,
        genres = filters.genres,
        excludedGenres = filters.excludedGenres,
        tags = filters.tags,
        excludedTags = filters.excludedTags,
        minScore = filters.minScore.takeIf { it > 0 },
        formats = filters.formats,
        statuses = filters.statuses,
        season = filters.season,
        seasonYear = filters.seasonYear.toIntOrNull(),
        sort = filters.sort,
        page = pageNumber,
    )

    val seeded = remember { deps.core.aniList.peekPage1(filterFor(1)) }
    var hits by remember { mutableStateOf<List<SearchHit>>(seeded.orEmpty()) }
    var loading by remember { mutableStateOf(seeded == null) }
    var showSkeleton by remember { mutableStateOf(false) }
    var loadedFilters by remember { mutableStateOf(if (seeded != null) filters else null) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var tagCatalog by remember { mutableStateOf<List<AniListTag>>(emptyList()) }
    var tagLoading by remember { mutableStateOf(false) }
    var genreCatalog by remember { mutableStateOf(AniListSource.GENRES) }
    val listState = rememberLazyListState()

    LaunchedEffect(filters.showFilters) {
        if (!filters.showFilters) return@LaunchedEffect
        if (tagCatalog.isNotEmpty() || tagLoading) return@LaunchedEffect
        tagLoading = true
        tagCatalog = withContext(Dispatchers.IO) {
            runCatching { deps.core.aniList.allTags(deps.core.ctx) }.getOrDefault(emptyList())
        }
        tagLoading = false
    }

    LaunchedEffect(Unit) {
        val live = withContext(Dispatchers.IO) {
            runCatching { deps.core.aniList.allGenres(deps.core.ctx) }.getOrDefault(AniListSource.GENRES)
        }
        if (live.isNotEmpty()) genreCatalog = live
    }

    val fallbackTags = remember(hits, filters.tags, filters.excludedTags) {
        val counts = hits.flatMap { it.tags }.groupingBy { it }.eachCount()
        val popular = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        (filters.tags.sorted() + filters.excludedTags.sorted() + popular).distinct()
    }
    val hasFilters = filters.genres.isNotEmpty() || filters.excludedGenres.isNotEmpty() ||
        filters.tags.isNotEmpty() || filters.excludedTags.isNotEmpty() || filters.minScore > 0 ||
        filters.formats.isNotEmpty() || filters.statuses.isNotEmpty() || filters.season != null || filters.seasonYear.isNotBlank()

    suspend fun fetch(pageNumber: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        runCatching {
            deps.core.aniList.search(
                filterFor(pageNumber),
                deps.core.ctx,
                refresh = pageNumber == 1,
            )
        }.onFailure { AppLog.w("search", "Search request failed", it) }.getOrDefault(emptyList())
    }

    LaunchedEffect(filters, includeAdult) {
        loading = true
        showSkeleton = false
        loadingMore = false
        endReached = false
        error = null
        page = 1
        delay(280)
        val skeletonTimer = launch {
            delay(350)
            if (hits.isEmpty()) showSkeleton = true
        }
        val result = runCatching { fetch(1) }
        skeletonTimer.cancel()
        loading = false
        showSkeleton = false
        result.onSuccess { next ->
            hits = next
            endReached = next.size < 30
            page = 2
            loadedFilters = filters
            runCatching { listState.scrollToItem(0) }
        }.onFailure { failure ->
            if (hits.isEmpty()) error = failure.message ?: "search failed"
        }
    }

    val shownCount by rememberUpdatedState(hits.size)

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (!loading && !loadingMore && !endReached && hits.isNotEmpty() && last >= shownCount - 4) {
                    loadingMore = true
                    val next = runCatching { fetch(page) }
                        .onFailure { AppLog.w("search", "Next search page failed", it) }
                        .getOrDefault(emptyList())
                    val existing = hits.mapNotNull { it.externalId }.toSet()
                    hits = hits + next.filter { it.externalId !in existing }
                    page += 1
                    if (next.size < 30) endReached = true
                    loadingMore = false
                }
            }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconSquareButton(SquareIcon.Back, active = false) { onBack() }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF151515))
                    .border(1.dp, Color(0xFF232323), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (filters.query.isEmpty()) {
                    Text("Search anime…", color = Color(0xFF9B9B96), fontSize = 14.sp, maxLines = 1)
                }
                BasicTextField(
                    value = filters.query,
                    onValueChange = { onFilters(filters.copy(query = it)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color(0xFFF5F5F2)),
                    cursorBrush = SolidColor(Color(0xFFF5F5F2)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconSquareButton(SquareIcon.Filter, active = hasFilters || filters.showFilters) {
                onFilters(filters.copy(showFilters = !filters.showFilters))
            }
            Spacer(Modifier.width(8.dp))
            SortIconButton(filters, onFilters)
        }

        if (filters.showFilters) {
            FiltersOverlay(
                filters = filters,
                genres = genreCatalog,
                tagCatalog = tagCatalog,
                fallbackTags = fallbackTags,
                includeAdult = includeAdult,
                loadingTags = tagLoading,
                onFilters = onFilters,
                onDismiss = { onFilters(filters.copy(showFilters = false)) },
            )
        } else if (hasFilters) {
            val summary = buildList {
                filters.genres.forEach { add(it) }
                filters.excludedGenres.forEach { add("-$it") }
                filters.tags.forEach { add(it) }
                filters.excludedTags.forEach { add("-$it") }
                filters.formats.forEach { add(it.replace('_', ' ')) }
                filters.statuses.forEach { add(it.replace('_', ' ')) }
                filters.season?.let { add(it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                if (filters.seasonYear.isNotBlank()) add(filters.seasonYear)
                if (filters.minScore > 0) add("Score ${filters.minScore}+")
            }.joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filters: $summary",
                    color = Color(0xFF9B9B96),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Clear",
                    color = Color(0xFFF5F5F2),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF151515))
                        .clickable {
                            onFilters(
                                SearchFilters(
                                    query = filters.query,
                                    sort = filters.sort,
                                ),
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        if (showSkeleton) {
            SearchSkeleton()
        }
        error?.let { Text(it, color = Color(0xFFF0A0A0), modifier = Modifier.padding(horizontal = 16.dp)) }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hits, key = { it.externalId ?: it.url }) { hit ->
                SearchRow(deps, hit, titleLanguage) { hit.externalId?.toLongOrNull()?.let(onOpen) }
            }
            if (loadingMore) {
                item { SearchSkeleton(rows = 2) }
            }
            if (!loading && !showSkeleton && hits.isEmpty() && error == null) {
                item { Text("Nothing found.", color = Color(0xFF9B9B96), modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

private enum class SquareIcon { Filter, Sort, Back, Close, Language, Settings }

@Composable
private fun IconSquareButton(
    icon: SquareIcon,
    active: Boolean,
    background: Color? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                background ?: when {
                    active -> Color(0xFF2A2A2A)
                    hovered -> Color(0xFF232323)
                    else -> Color(0xFF151515)
                },
            )
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = Color(0xFFF5F5F2)
            val stroke = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            when (icon) {
                SquareIcon.Filter -> {
                    for (i in 0..2) {
                        val y = h * (0.26f + 0.24f * i)
                        drawLine(color, Offset(w * 0.1f, y), Offset(w * 0.9f, y), stroke, StrokeCap.Round)
                    }
                }
                SquareIcon.Sort -> {
                    val left = w * 0.34f
                    val right = w * 0.66f
                    drawLine(color, Offset(left, h * 0.18f), Offset(left, h * 0.82f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(left - w * 0.16f, h * 0.37f), Offset(left, h * 0.16f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(left + w * 0.16f, h * 0.37f), Offset(left, h * 0.16f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(right, h * 0.18f), Offset(right, h * 0.82f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(right - w * 0.16f, h * 0.63f), Offset(right, h * 0.84f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(right + w * 0.16f, h * 0.63f), Offset(right, h * 0.84f), stroke, StrokeCap.Round)
                }
                SquareIcon.Back -> {
                    drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.84f, h * 0.5f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.43f, h * 0.24f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.43f, h * 0.76f), stroke, StrokeCap.Round)
                }
                SquareIcon.Close -> {
                    drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), stroke, StrokeCap.Round)
                }
                SquareIcon.Settings -> {
                    drawCircle(color, radius = w * 0.22f, center = Offset(w / 2f, h / 2f), style = Stroke(stroke))
                    drawCircle(color, radius = w * 0.42f, center = Offset(w / 2f, h / 2f), style = Stroke(stroke))
                    for (i in 0 until 8) {
                        val angle = Math.toRadians(i * 45.0)
                        val inner = w * 0.42f
                        val outer = w * 0.5f
                        val cx = w / 2f
                        val cy = h / 2f
                        drawLine(
                            color,
                            Offset(cx + (Math.cos(angle) * inner).toFloat(), cy + (Math.sin(angle) * inner).toFloat()),
                            Offset(cx + (Math.cos(angle) * outer).toFloat(), cy + (Math.sin(angle) * outer).toFloat()),
                            stroke,
                            StrokeCap.Round,
                        )
                    }
                }
                SquareIcon.Language -> {
                    drawCircle(color, radius = w * 0.4f, center = Offset(w / 2f, h / 2f), style = Stroke(stroke))
                    drawOval(
                        color,
                        topLeft = Offset(w * 0.3f, h * 0.1f),
                        size = Size(w * 0.4f, h * 0.8f),
                        style = Stroke(stroke),
                    )
                    drawLine(color, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.9f, h * 0.5f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun SortIconButton(filters: SearchFilters, onFilters: (SearchFilters) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconSquareButton(SquareIcon.Sort, active = filters.sort != "SCORE_DESC") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AniListSource.SORTS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color(0xFFF5F5F2), fontSize = 13.sp) },
                    onClick = {
                        open = false
                        onFilters(filters.copy(sort = value))
                    },
                )
            }
        }
    }
}

@Composable
private fun FiltersOverlay(
    filters: SearchFilters,
    genres: List<String>,
    tagCatalog: List<AniListTag>,
    fallbackTags: List<String>,
    includeAdult: Boolean,
    loadingTags: Boolean,
    onFilters: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var tagQuery by remember { mutableStateOf("") }
    val tagOptions = remember(tagCatalog, fallbackTags, tagQuery, filters.tags, filters.excludedTags, includeAdult) {
        val selected = filters.tags + filters.excludedTags
        val catalog = tagCatalog
            .filter { includeAdult || !it.adult }
            .map { it.name }
        val source = if (catalog.isNotEmpty()) catalog else fallbackTags
        val query = tagQuery.trim()
        (source + selected).distinct().filter { tag ->
            query.isEmpty() || tag in selected || tag.contains(query, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0C0C0C),
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.heightIn(max = 620.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Filters", color = Color(0xFFF5F5F2), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Done",
                        color = Color(0xFFF5F5F2),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                FilterPanel(
                    filters = filters,
                    genres = if (includeAdult) genres
                    else genres.filterNot { it.equals(AniListSource.ADULT_GENRE, true) },
                    tagOptions = tagOptions,
                    tagQuery = tagQuery,
                    onTagQuery = { tagQuery = it },
                    loadingTags = loadingTags,
                    onFilters = onFilters,
                    modifier = Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    filters: SearchFilters,
    genres: List<String>,
    tagOptions: List<String>,
    tagQuery: String,
    onTagQuery: (String) -> Unit,
    loadingTags: Boolean,
    onFilters: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Label("GENRES")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            genres.forEach { genre ->
                Chip(
                    genre,
                    selected = genre in filters.genres,
                    excluded = genre in filters.excludedGenres,
                ) {
                    val (included, excluded) = cycleFilter(filters.genres, filters.excludedGenres, genre)
                    onFilters(filters.copy(genres = included, excludedGenres = excluded))
                }
            }
        }

        Label("TAGS")
        OutlinedTextField(
            value = tagQuery,
            onValueChange = onTagQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search tags…", color = Color(0xFF6F6F6F), fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFF5F5F2), fontSize = 13.sp),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6F6F6F),
                unfocusedBorderColor = Color(0xFF232323),
                focusedContainerColor = Color(0xFF151515),
                unfocusedContainerColor = Color(0xFF151515),
                cursorColor = Color(0xFFF5F5F2),
            ),
        )
        Text(
            if (loadingTags) "Loading all tags…" else "${tagOptions.size} tags",
            color = Color(0xFF9B9B96),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (tagOptions.isEmpty()) {
            Text(
                "No tags match.",
                color = Color(0xFF9B9B96),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                gridItems(tagOptions, key = { it }) { tag ->
                    Chip(
                        tag,
                        selected = tag in filters.tags,
                        excluded = tag in filters.excludedTags,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val (included, excluded) = cycleFilter(filters.tags, filters.excludedTags, tag)
                        onFilters(filters.copy(tags = included, excludedTags = excluded))
                    }
                }
            }
        }

        Label("MINIMUM SCORE: ${filters.minScore}")
        Slider(
            value = filters.minScore.toFloat(),
            onValueChange = { onFilters(filters.copy(minScore = it.toInt())) },
            valueRange = 0f..100f,
            steps = 19,
        )

        Label("FORMAT")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AniListSource.FORMATS.forEach { format ->
                Chip(format.replace('_', ' '), format in filters.formats) {
                    val next = if (format in filters.formats) filters.formats - format else filters.formats + format
                    onFilters(filters.copy(formats = next))
                }
            }
        }

        Label("STATUS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AniListSource.STATUSES.forEach { status ->
                Chip(status.replace('_', ' '), status in filters.statuses) {
                    val next = if (status in filters.statuses) filters.statuses - status else filters.statuses + status
                    onFilters(filters.copy(statuses = next))
                }
            }
        }

        Label("SEASON")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AniListSource.SEASONS) { season ->
                Chip(season, filters.season == season) {
                    onFilters(filters.copy(season = if (filters.season == season) null else season))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                value = filters.seasonYear,
                onValueChange = { v -> onFilters(filters.copy(seasonYear = v.filter { it.isDigit() }.take(4))) },
                modifier = Modifier.width(120.dp),
                singleLine = true,
                placeholder = { Text("Year", color = Color(0xFF6F6F6F), fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFF5F5F2), fontSize = 13.sp),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6F6F6F),
                    unfocusedBorderColor = Color(0xFF232323),
                    focusedContainerColor = Color(0xFF151515),
                    unfocusedContainerColor = Color(0xFF151515),
                    cursorColor = Color(0xFFF5F5F2),
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Clear filters",
                color = Color(0xFFF5F5F2),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF1D1D1D))
                    .clickable { onFilters(SearchFilters(showFilters = true)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color(0xFF9B9B96), fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
}

@Composable
private fun SortSelector(filters: SearchFilters, onFilters: (SearchFilters) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = AniListSource.SORTS.firstOrNull { it.first == filters.sort }?.second ?: "Rating"
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF151515))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = Color(0xFFF5F5F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("  ▾", color = Color(0xFF9B9B96), fontSize = 10.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AniListSource.SORTS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color(0xFFF5F5F2), fontSize = 13.sp) },
                    onClick = {
                        open = false
                        onFilters(filters.copy(sort = value))
                    },
                )
            }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    excluded: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        color = when {
            excluded -> Color(0xFFFF7070)
            selected -> Color.Black
            else -> Color(0xFFC9C9C3)
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    excluded -> Color(0xFF3A1212)
                    selected -> Color(0xFFF5F5F2)
                    else -> Color(0xFF1A1A1A)
                },
            )
            .then(if (excluded) Modifier.border(1.dp, Color(0xFF9E3A3A), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SearchRow(deps: Deps, hit: SearchHit, titleLanguage: TitleLanguage, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0C0C0C))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val imagePadding = 4.dp
        val imageCorner = 10.dp
        Box(Modifier.width(69.dp).aspectRatio(2f / 3f)) {
            deps.images.Image(
                hit.image,
                Modifier.fillMaxSize().padding(imagePadding).clip(RoundedCornerShape(imageCorner)).background(Color(0xFF151515)),
                ContentScale.Crop,
            )
            hit.score?.takeIf { it > 0 }?.let { score ->
                ImageRatingRing(
                    score,
                    imageCorner = imageCorner,
                    imagePadding = imagePadding,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).heightIn(min = 104.dp)) {
            val season = hit.season?.lowercase()?.replaceFirstChar { it.uppercase() }
            val seasonText = listOfNotNull(season, hit.year?.toString()).joinToString(" ")
            val eyebrow = listOfNotNull(seasonText.ifBlank { null }, hit.studio?.let { "STUDIO $it" }).joinToString(" · ")
            if (eyebrow.isNotBlank()) {
                Text(eyebrow.uppercase(), color = Color(0xFFF5F5F2), fontSize = 9.5.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            }
            val displayTitle = when (titleLanguage) {
                TitleLanguage.ROMAJI -> hit.title ?: hit.titleEn ?: hit.titleNative ?: "Untitled"
                TitleLanguage.ENGLISH -> hit.titleEn ?: hit.title ?: hit.titleNative ?: "Untitled"
                TitleLanguage.NATIVE -> hit.titleNative ?: hit.title ?: hit.titleEn ?: "Untitled"
            }
            Text(
                displayTitle,
                color = Color(0xFFF5F5F2),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.genres.isNotEmpty() || hit.tags.isNotEmpty()) {
                MetaLine(
                    genres = hit.genres,
                    tags = hit.tags,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            hit.snippet?.let {
                Text(
                    previewText(it, 170),
                    color = Color(0xFFC9C9C3),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun formatCacheBytes(bytes: Long?): String = when {
    bytes == null -> "calculating…"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "${bytes / (1024L * 1024L)} MB"
}

private fun recentTitle(recent: RecentSeries, language: TitleLanguage, detail: DetailResult?): String {
    if (detail != null) return displayTitle(detail, language)
    return when (language) {
        TitleLanguage.ROMAJI -> recent.titleRomaji ?: recent.title
        TitleLanguage.ENGLISH -> recent.titleEnglish ?: recent.titleRomaji ?: recent.title
        TitleLanguage.NATIVE -> recent.titleNative ?: recent.titleRomaji ?: recent.title
    }
}

private fun titleLanguageLabel(language: TitleLanguage): String = when (language) {
    TitleLanguage.ROMAJI -> "Romaji"
    TitleLanguage.ENGLISH -> "English"
    TitleLanguage.NATIVE -> "Native"
}

private fun displayTitle(d: DetailResult, language: TitleLanguage): String = when (language) {
    TitleLanguage.ROMAJI -> d.title?.romaji ?: d.title?.english ?: d.title?.native ?: "Untitled"
    TitleLanguage.ENGLISH -> d.title?.english ?: d.title?.romaji ?: d.title?.native ?: "Untitled"
    TitleLanguage.NATIVE -> d.title?.native ?: d.title?.romaji ?: d.title?.english ?: "Untitled"
}

@Composable
private fun DetailScreen(
    deps: Deps,
    id: Long,
    refreshTick: Int,
    onBack: () -> Unit,
    onPlay: (Int, Int, String, String, String?, Double?, String?, Int?) -> Unit,
    titleLanguage: TitleLanguage,
    onCycleTitleLanguage: () -> Unit,
    onGenre: (String) -> Unit,
    onTag: (String) -> Unit,
) {
    val cached = remember(id) { deps.core.series.peek(id) }
    var detail by remember(id) { mutableStateOf(cached?.detail) }
    var seasons by remember(id) { mutableStateOf(cached?.seasons.orEmpty()) }
    var loading by remember(id) { mutableStateOf(cached == null) }
    var showSkeleton by remember(id) { mutableStateOf(false) }
    var refreshing by remember(id) { mutableStateOf(false) }
    var refreshTickLocal by remember(id) { mutableIntStateOf(0) }
    var showDesc by remember(id) { mutableStateOf(false) }
    val detailListState = rememberLazyListState()

    deps.back.Back { onBack() }

    LaunchedEffect(id, refreshTickLocal) {
        if (cached == null) loading = true
        refreshing = true
        val skeletonTimer = launch {
            delay(350)
            if (loading) showSkeleton = true
        }
        val view = withContext(Dispatchers.IO) {
            runCatching { deps.core.series.load(id, deps.core.ctx, refresh = cached != null) }
                .onFailure { AppLog.w("series", "Could not load series $id", it) }
                .getOrNull()
        }
        skeletonTimer.cancel()
        detail = view?.detail
        seasons = view?.seasons.orEmpty()
        loading = false
        refreshing = false
        showSkeleton = false
    }

    if (loading) {
        if (showSkeleton) DetailSkeleton() else Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val d = detail ?: run {
        Text("Not found", color = Color(0xFFF0A0A0), modifier = Modifier.padding(24.dp))
        return
    }

    val lastPlayed = remember(id, refreshTick) { deps.progress.last(id) }
    val playTarget: Pair<Int, Int>? = run {
        val resume = lastPlayed
        val resumable = resume != null && seasons.any { (season, eps) ->
            season == resume.season && eps.any { it.number == resume.episode }
        }
        when {
            resumable -> resume!!.season to resume.episode
            seasons.isNotEmpty() -> seasons.first().let { (season, eps) -> season to (eps.firstOrNull()?.number ?: 1) }
            d.format == "MOVIE" -> 1 to 1
            else -> null
        }
    }

    LaunchedEffect(id, playTarget) {
        val target = playTarget ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                deps.core.playback.play(
                    id.toString(),
                    target.first,
                    target.second,
                    absoluteEpisode = absoluteEpisodeIndex(seasons, target.first, target.second),
                    episodeTitle = seasons.firstOrNull { it.first == target.first }
                        ?.second?.firstOrNull { it.number == target.second }?.title,
                )
            }.onFailure { AppLog.w("playback", "Prewarm failed for $id S${target.first} E${target.second}", it) }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 640.dp
            val coverWidth = if (wide) 240.dp else 120.dp
            val coverHeightOverride = if (wide) 360.dp else 270.dp
            val titleSize = if (wide) 32.sp else 23.sp
            val tileWidth = if (wide) 260.dp else 200.dp
            val bannerHeight = if (wide) 330.dp else 230.dp

            Box(Modifier.fillMaxWidth().height(bannerHeight)) {
                d.bannerImage?.let { banner ->
                    deps.images.Image(banner, Modifier.fillMaxSize(), ContentScale.Crop)
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.72f),
                            0.45f to Color.Black.copy(alpha = 0.42f),
                            0.75f to Color.Black.copy(alpha = 0.85f),
                            1.0f to Color.Black,
                        ),
                    ),
                )
            }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconSquareButton(SquareIcon.Back, active = false) { onBack() }
                    Spacer(Modifier.weight(1f))
                    IconSquareButton(SquareIcon.Language, active = false) { onCycleTitleLanguage() }
                }

                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        state = detailListState,
                        modifier = Modifier.widthIn(max = 980.dp).fillMaxHeight().navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                val coverPadding = 4.dp
                                val coverCorner = 12.dp
                                Box(Modifier.height(coverHeightOverride).aspectRatio(2f / 3f)) {
                                    deps.images.Image(
                                        d.coverImage,
                                        Modifier.fillMaxSize()
                                            .padding(coverPadding)
                                            .clip(RoundedCornerShape(coverCorner))
                                            .background(Color(0xFF151515)),
                                        ContentScale.Crop,
                                    )
                                    d.averageScore?.takeIf { it > 0 }?.let { score ->
                                        ImageRatingRing(
                                            score,
                                            imageCorner = coverCorner,
                                            imagePadding = coverPadding,
                                            modifier = Modifier.matchParentSize(),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f).heightIn(min = coverHeightOverride)) {
                                    val season = d.season?.lowercase()?.replaceFirstChar { it.uppercase() }
                                    val seasonText = listOfNotNull(season, d.seasonYear?.toString()).joinToString(" ")
                                    val studioText = d.studios.firstOrNull()?.let {
                                        if (it.startsWith("Studio", ignoreCase = true)) it else "STUDIO $it"
                                    }
                                    val eyebrow = listOfNotNull(seasonText.ifBlank { null }, studioText).joinToString(" · ")
                                    if (eyebrow.isNotBlank()) {
                                        Text(
                                            eyebrow.uppercase(),
                                            color = Color(0xFFF5F5F2),
                                            fontSize = if (wide) 12.sp else 10.5.sp,
                                            letterSpacing = 1.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    val seriesTitle = displayTitle(d, titleLanguage)
                                    Text(
                                        seriesTitle,
                                        color = Color(0xFFF5F5F2),
                                        fontSize = titleSize,
                                        fontWeight = FontWeight.ExtraBold,
                                        lineHeight = (titleSize.value * 1.18f).sp,
                                        maxLines = 3,
                                        modifier = Modifier.padding(top = if (wide) 4.dp else 1.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { d.siteUrl?.let(deps.openUrl) },
                                    )
                                    val tagNames = d.tags.filter { it.rank > 50 }.sortedByDescending { it.rank }.take(14).map { it.name }
                                    MetaLine(
                                        genres = d.genres,
                                        tags = tagNames,
                                        fontSize = if (wide) 13.sp else 12.sp,
                                        modifier = Modifier.padding(top = if (wide) 8.dp else 4.dp),
                                    )

                                    d.description?.let { desc ->
                                        TrimmedText(
                                            text = desc,
                                            maxLines = 3,
                                            expanded = false,
                                            color = Color(0xFFC9C9C3),
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp,
                                            modifier = Modifier.padding(top = if (wide) 10.dp else 6.dp).fillMaxWidth(),
                                            onClick = { showDesc = true },
                                        )
                                    }

                                    if (d.characters.isNotEmpty()) {
                                        val charState = rememberLazyListState()
                                        LazyRow(
                                            state = charState,
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(top = if (wide) 12.dp else 8.dp)
                                                .scrollFades(charState),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            items(d.characters.take(18)) { c ->
                                                Box(
                                                    Modifier.size(if (wide) 84.dp else 68.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(Color(0xFF151515))
                                                        .then(
                                                            if (c.id != null) {
                                                                Modifier.clickable {
                                                                    deps.openUrl("https://anilist.co/character/" + c.id)
                                                                }
                                                            } else {
                                                                Modifier
                                                            },
                                                        ),
                                                ) {
                                                    deps.images.Image(c.image, Modifier.fillMaxSize(), ContentScale.Crop)
                                                    Box(
                                                        Modifier.fillMaxSize().background(
                                                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))),
                                                        ),
                                                    )
                                                    Text(
                                                        c.name,
                                                        color = Color(0xFFF5F5F2),
                                                        fontSize = 9.5.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 4.dp, vertical = 3.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (seasons.isEmpty() && d.format != "MOVIE") {
                            item {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (refreshing) "Loading episodes…" else "Episodes unavailable.",
                                        color = Color(0xFF9B9B96),
                                        fontSize = 13.sp,
                                    )
                                    if (!refreshing) {
                                        Text(
                                            "Retry",
                                            color = Color(0xFFF5F5F2),
                                            fontSize = 13.sp,
                                            modifier = Modifier.padding(top = 12.dp)
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(Color(0xFF151515))
                                                .clickable { refreshTickLocal += 1 }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }

                        run {
                            seasons.forEach { (seasonNumber, eps) ->
                                item {
                                    val label = when {
                                        seasonNumber == 0 -> "Specials"
                                        seasonNumber in 1900..2099 -> seasonNumber.toString()
                                        else -> "Season $seasonNumber"
                                    }
                                    Text(
                                        label.uppercase(),
                                        color = Color(0xFF9B9B96),
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 16.dp, top = if (seasonNumber == seasons.first().first) 18.dp else 20.dp, bottom = 8.dp),
                                    )
                                    val epRowState = rememberLazyListState()
                                    LazyRow(
                                        state = epRowState,
                                        modifier = Modifier.scrollFades(epRowState),
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        items(eps, key = { "${seasonNumber}-${it.number}" }) { ep ->
                                            EpisodeTile(deps, id, ep, seasonNumber, tileWidth) {
                                                onPlay(
                                                    seasonNumber,
                                                    ep.number ?: 0,
                                                    "${displayTitle(d, titleLanguage)} · S$seasonNumber E${ep.number}",
                                                    displayTitle(d, titleLanguage),
                                                    d.coverImage,
                                                    d.averageScore,
                                                    ep.title,
                                                    absoluteEpisodeIndex(seasons, seasonNumber, ep.number ?: 0),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (detailListState.firstVisibleItemIndex > 0 || detailListState.firstVisibleItemScrollOffset > 0) {
            Box(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.92f), Color.Transparent),
                        ),
                    ),
            )
        }

        playTarget?.let { (season, episode) ->
            val isSeries = seasons.any { it.first == season }
            val label = if (isSeries) {
                "${displayTitle(d, titleLanguage)} · S$season E$episode"
            } else {
                displayTitle(d, titleLanguage)
            }
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 20.dp)
                    .size(56.dp)
                    .shadow(14.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F5F2))
                    .clickable {
                        onPlay(
                            season,
                            episode,
                            label,
                            displayTitle(d, titleLanguage),
                            d.coverImage,
                            d.averageScore,
                            seasons.firstOrNull { it.first == season }
                                ?.second?.firstOrNull { it.number == episode }?.title,
                            absoluteEpisodeIndex(seasons, season, episode),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.12f, 0f)
                        lineTo(size.width * 0.94f, size.height / 2f)
                        lineTo(size.width * 0.12f, size.height)
                        close()
                    }
                    drawPath(path, Color(0xFF0A0A0A))
                }
            }
        }
    }

    if (showDesc) {
        val descText = d.description.orEmpty()
        Dialog(onDismissRequest = { showDesc = false }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF0C0C0C),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                    Text("DESCRIPTION", color = Color(0xFF9B9B96), fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(descText, color = Color(0xFFC9C9C3), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(
                        "Close",
                        color = Color(0xFFF5F5F2),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF1D1D1D))
                            .clickable { showDesc = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, corner: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF141414), Color(0xFF1D1D1D), Color(0xFF141414)),
                ),
            ),
    )
}

@Composable
private fun SearchSkeleton(rows: Int = 5) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { SearchSkeletonRow() }
    }
}

@Composable
private fun SearchSkeletonRow() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0C0C0C)).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SkeletonBlock(Modifier.width(69.dp).aspectRatio(2f / 3f), corner = 12.dp)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f).heightIn(min = 104.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth(0.32f).height(9.dp), corner = 4.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.62f).height(16.dp), corner = 4.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.42f).height(16.dp), corner = 4.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.92f).height(11.dp), corner = 4.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.95f).height(11.dp), corner = 4.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(11.dp), corner = 4.dp)
        }
    }
}

@Composable
private fun DetailSkeleton() {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > 640.dp
        val coverWidth = if (wide) 240.dp else 120.dp
        val bannerHeight = if (wide) 330.dp else 230.dp
        val tileWidth = if (wide) 260.dp else 200.dp
        val characterSize = if (wide) 84.dp else 68.dp

        Box(Modifier.fillMaxWidth().height(bannerHeight)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.0f to Color(0xFF141414),
                        0.5f to Color(0xFF0C0C0C),
                        1.0f to Color.Black,
                    ),
                ),
            )
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.size(44.dp), corner = 999.dp)
            }

            val coverHeight = if (wide) 360.dp else 270.dp
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SkeletonBlock(Modifier.height(coverHeight).aspectRatio(2f / 3f), corner = 14.dp)
                Spacer(Modifier.width(16.dp))
                Column(
                    Modifier.weight(1f).heightIn(min = coverHeight),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.34f).height(if (wide) 12.dp else 10.dp), corner = 4.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(0.74f).height(if (wide) 30.dp else 24.dp), corner = 6.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(0.96f).height(12.dp), corner = 4.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(1f).height(12.dp), corner = 4.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(0.68f).height(12.dp), corner = 4.dp)
                    Row(
                        Modifier.fillMaxWidth().clipToBounds().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(if (wide) 6 else 4) {
                            SkeletonBlock(Modifier.size(characterSize), corner = 12.dp)
                        }
                    }
                }
            }

            SkeletonBlock(Modifier.padding(start = 16.dp, top = 20.dp).width(64.dp).height(11.dp), corner = 4.dp)
            Row(
                Modifier.fillMaxWidth().clipToBounds().padding(start = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { SkeletonBlock(Modifier.width(tileWidth).aspectRatio(16f / 9f), corner = 12.dp) }
            }

            SkeletonBlock(Modifier.padding(start = 16.dp, top = 20.dp).width(64.dp).height(11.dp), corner = 4.dp)
            Row(
                Modifier.fillMaxWidth().clipToBounds().padding(start = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { SkeletonBlock(Modifier.width(tileWidth).aspectRatio(16f / 9f), corner = 12.dp) }
            }
        }
    }
}

private fun previewText(text: String, limit: Int): String {
    val clean = text.trim()
    if (clean.length <= limit) return clean
    val cut = clean.take(limit)
    val space = cut.lastIndexOf(' ')
    val base = (if (space > limit / 2) cut.substring(0, space) else cut)
        .trimEnd()
        .trimEnd { ch -> ch in ".,;:!?…·-–—\"'()[]{}" }
    return base + "…"
}

@Composable
private fun TrimmedText(
    text: String,
    maxLines: Int,
    expanded: Boolean,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var shown by remember(text, maxLines, expanded) { mutableStateOf(text) }
    var clipped by remember(text, maxLines, expanded) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val clickModifier = if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else modifier
    Text(
        shown,
        color = if (onClick != null && hovered) Color(0xFFF5F5F2) else color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Clip,
        modifier = clickModifier,
        onTextLayout = { result ->
            if (!expanded && !clipped && result.hasVisualOverflow) {
                clipped = true
                val end = runCatching { result.getLineEnd(maxLines - 1, visibleEnd = true) }
                    .getOrDefault(text.length)
                val cut = text.substring(0, end.coerceIn(0, text.length))
                    .trimEnd()
                    .trimEnd { it in ".,;:!?…·-–—\"'()[]{}" }
                shown = cut + "…"
            }
        },
    )
}

@Composable
private fun MetaLine(
    genres: List<String>,
    tags: List<String>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val text = buildString {
        if (genres.isNotEmpty()) append(genres.joinToString("  "))
        if (genres.isNotEmpty() && tags.isNotEmpty()) append("   ·   ")
        if (tags.isNotEmpty()) append(tags.joinToString("  "))
    }
    if (text.isBlank()) return
    Text(
        text,
        color = Color(0xFFF5F5F2),
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.fillMaxWidth().edgeFadeRight(),
    )
}

@Composable
private fun Modifier.scrollFades(state: LazyListState): Modifier {
    val left = state.canScrollBackward
    val right = state.canScrollForward
    if (!left && !right) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = 18.dp.toPx()
            val f = (edge / size.width).coerceIn(0.02f, 0.35f)
            val brush = when {
                left && right -> Brush.horizontalGradient(
                    0.0f to Color.Transparent, f to Color.Black,
                    1f - f to Color.Black, 1f to Color.Transparent,
                )
                right -> Brush.horizontalGradient(0.0f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent)
                else -> Brush.horizontalGradient(0.0f to Color.Transparent, f to Color.Black, 1f to Color.Black)
            }
            drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
}

private fun Modifier.edgeFadeRight(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.Black,
                0.80f to Color.Black,
                1.0f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private fun Modifier.edgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.055f to Color.Black,
                0.945f to Color.Black,
                1.0f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun ImageRatingRing(
    score: Double,
    imageCorner: androidx.compose.ui.unit.Dp,
    imagePadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier) {
        val strokeWidth = 3.dp.toPx()
        val inset = with(density) { imagePadding.toPx() }
        val radius = with(density) { imageCorner.toPx() }
        val rect = Rect(inset, inset, size.width - inset, size.height - inset)
        if (rect.width <= radius * 2f || rect.height <= radius * 2f) return@Canvas

        val outline = Path().apply {
            val centerX = (rect.left + rect.right) / 2f
            moveTo(centerX, rect.top)
            lineTo(rect.right - radius, rect.top)
            arcTo(Rect(rect.right - 2 * radius, rect.top, rect.right, rect.top + 2 * radius), -90f, 90f, false)
            lineTo(rect.right, rect.bottom - radius)
            arcTo(Rect(rect.right - 2 * radius, rect.bottom - 2 * radius, rect.right, rect.bottom), 0f, 90f, false)
            lineTo(rect.left + radius, rect.bottom)
            arcTo(Rect(rect.left, rect.bottom - 2 * radius, rect.left + 2 * radius, rect.bottom), 90f, 90f, false)
            lineTo(rect.left, rect.top + radius)
            arcTo(Rect(rect.left, rect.top, rect.left + 2 * radius, rect.top + 2 * radius), 180f, 90f, false)
            lineTo(centerX, rect.top)
        }

        val measure = PathMeasure().apply { setPath(outline, false) }
        val total = measure.length
        if (total <= 0f) return@Canvas
        val sweep = (score.coerceIn(0.0, 100.0) / 100.0 * total).toFloat()
        if (sweep <= 0f) return@Canvas

        val start = ((total - sweep) / 2f) % total
        val drawn = Path()
        val first = minOf(total - start, sweep)
        measure.getSegment(start, start + first, drawn, true)
        if (sweep > first) {
            val rest = Path()
            measure.getSegment(0f, sweep - first, rest, true)
            drawn.addPath(rest)
        }
        drawPath(drawn, color = Color(0xFFF5F5F2), style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    }
}

@Composable
private fun EpisodeTile(deps: Deps, id: Long, ep: TvmazeEpisode, season: Int, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF151515)).clickable(onClick = onClick),
    ) {
        deps.images.Image(ep.thumb, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))))
        Text(
            "E" + (ep.number ?: 0).toString().padStart(2, '0'), color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Text(
            ep.title ?: "Episode ${ep.number}", color = Color.White, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 7.dp, vertical = 5.dp)
                .edgeFadeRight(),
        )
        val fraction = deps.progress.fraction(id, season, ep.number ?: 0)
        if (fraction != null && fraction > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFF5F5F2)))
            }
        }
    }
}

private fun languageLabelFor(track: PlayerAudioTrack, source: PlaySource?): String {
    val label = track.label.trim()
    return if (label.equals("Native", ignoreCase = true) || label.startsWith("Track ", ignoreCase = true)) {
        source?.languages?.firstOrNull() ?: label
    } else {
        label
    }
}

private fun buildResolutionOptions(
    sources: List<PlaySource>,
    index: Int,
    session: PlayerSession?,
    probed: Map<String, List<PlayerResolution>>,
    language: String,
): List<PlayerResolutionOption> {
    val out = LinkedHashMap<String, PlayerResolutionOption>()
    val activeId = session?.activeResolutionId
    val currentSource = sources.getOrNull(index)
    val currentMatches = currentSource?.languages?.any { it.equals(language, ignoreCase = true) } ?: true

    if (currentMatches) {
        out["Auto"] = PlayerResolutionOption(
            label = "Auto",
            id = "auto",
            sourceIndex = index,
            selected = activeId == null || activeId == "auto",
        )
        session?.resolutions?.forEach { resolution ->
            if (resolution.id == "auto") return@forEach
            out[resolution.label] = PlayerResolutionOption(
                label = resolution.label,
                id = resolution.id,
                sourceIndex = index,
                selected = resolution.selected || resolution.id == activeId,
            )
        }
    }
    sources.forEachIndexed { sourceIndex, source ->
        if (source.languages.none { it.equals(language, ignoreCase = true) }) return@forEachIndexed
        probed[source.url].orEmpty().forEach { resolution ->
            if (!out.containsKey(resolution.label)) {
                out[resolution.label] = PlayerResolutionOption(
                    label = resolution.label,
                    id = resolution.id,
                    sourceIndex = sourceIndex,
                    selected = false,
                )
            }
        }
    }
    return out.values.toList()
}

private fun buildLanguages(sources: List<PlaySource>, index: Int, session: PlayerSession?): List<PlayerLanguage> {
    val out = LinkedHashMap<String, PlayerLanguage>()
    val source = sources.getOrNull(index)
    session?.audioTracks?.forEach { track ->
        val label = languageLabelFor(track, source)
        if (out[label] == null) {
            out[label] = PlayerLanguage(
                label = label,
                trackId = track.id,
                sourceIndex = null,
                selected = track.id == session.activeAudioId || track.selected,
            )
        }
    }
    sources.forEachIndexed { sourceIndex, playSource ->
        playSource.languages.forEach { language ->
            if (out[language] == null) {
                out[language] = PlayerLanguage(language, trackId = null, sourceIndex = sourceIndex, selected = false)
            }
        }
    }
    return out.values.toList()
}

@Composable
private fun PlayerOverlay(
    deps: Deps,
    args: PlayerArgs,
    titleLanguage: TitleLanguage,
    autoplayNext: Boolean,
    onlineSubtitles: Boolean,
    subtitleLanguage: String,
    subtitleSize: Int,
    onClose: () -> Unit,
    onPlayNext: (PlayerArgs) -> Unit,
) {
    deps.back.Back { onClose() }
    var sources by remember(args) { mutableStateOf<List<PlaySource>>(emptyList()) }
    var index by remember(args) { mutableStateOf(0) }
    var status by remember(args) { mutableStateOf("") }
    var failed by remember(args) { mutableStateOf(false) }
    var resolving by remember(args) { mutableStateOf(true) }
    var resumeFrom by remember(args) { mutableStateOf(deps.progress.resumeMs(args.id, args.season, args.episode)) }
    var session by remember(args) { mutableStateOf<PlayerSession?>(null) }
    var controlsVisible by remember(args) { mutableStateOf(true) }
    var poke by remember(args) { mutableIntStateOf(0) }
    var fullscreen by remember(args) { mutableStateOf(false) }
    var resolveAttempt by remember(args) { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refreshedOnce by remember(args) { mutableStateOf(false) }
    var probedResolutions by remember(args) { mutableStateOf<Map<String, List<PlayerResolution>>>(emptyMap()) }
    var pendingResolution by remember(args) { mutableStateOf<String?>(null) }
    var playerError by remember(args) { mutableStateOf<String?>(null) }
    val inPictureInPicture by deps.player.pictureInPicture.collectAsState()
    val focus = remember { FocusRequester() }

    val headerDetail = remember(args.id) { deps.core.series.peek(args.id)?.detail }
    val headerTitle = headerDetail?.let { displayTitle(it, titleLanguage) } ?: args.title
    val currentSource = sources.getOrNull(index)
    val activeLanguage = session?.let { active ->
        active.audioTracks.firstOrNull { it.id == active.activeAudioId }
            ?.let { languageLabelFor(it, currentSource) }
    } ?: deps.progress.audioPreference(args.id) ?: deps.progress.defaultAudioLanguage()
    val titleSource = sources.firstOrNull {
        it.episodeTitleGerman != null || it.episodeTitleEnglish != null
    } ?: currentSource
    val headerEpisodeTitle = (
        if (activeLanguage.equals("German", ignoreCase = true)) {
            titleSource?.episodeTitleGerman ?: args.episodeTitle
        } else {
            titleSource?.episodeTitleEnglish ?: args.episodeTitle
        }
    ) ?: "Episode ${args.episode}"
    val headerLabel = "$headerTitle · S${args.season} E${args.episode}"
    val source = sources.getOrNull(index)

    val seasons = remember(args.id, session?.ended) { deps.core.series.peek(args.id)?.seasons }
    val nextPointer = remember(seasons, args.season, args.episode) {
        seasons?.let { nextEpisode(it, args.season, args.episode) }
    }
    val nextEntry = remember(seasons, nextPointer) {
        nextPointer?.let { pointer ->
            seasons?.firstOrNull { it.first == pointer.season }?.second
                ?.firstOrNull { it.number == pointer.episode }
        }
    }
    val nextArgs = remember(nextPointer, nextEntry, headerTitle, headerDetail) {
        nextPointer?.let { pointer ->
            PlayerArgs(
                id = args.id,
                season = pointer.season,
                episode = pointer.episode,
                label = "$headerTitle · S${pointer.season} E${pointer.episode}",
                title = headerTitle,
                cover = headerDetail?.coverImage ?: args.cover,
                score = args.score,
                episodeTitle = nextEntry?.title,
                absoluteEpisode = pointer.absolute,
            )
        }
    }

    var onlineTracks by remember(args) { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    val externalTracks = remember(source?.url, source?.subtitles, onlineTracks) {
        ((source?.subtitles ?: emptyList()) + onlineTracks).distinctBy { it.url }
    }
    var subtitlesOn by remember(args.id) {
        mutableStateOf(deps.progress.subtitlesEnabled(args.id) ?: true)
    }
    var subtitleLang by remember { mutableStateOf(deps.progress.subtitleLanguage()) }
    var subtitleFetching by remember(args) { mutableStateOf(false) }
    var activeExternalUrl by remember(args) { mutableStateOf<String?>(null) }
    var preferExternal by remember(args) { mutableStateOf(false) }
    var cues by remember(args) { mutableStateOf<List<dev.anime.model.SubtitleCue>>(emptyList()) }
    var upNextSeconds by remember(args) { mutableStateOf<Int?>(null) }
    var upNextDismissed by remember(args) { mutableStateOf(false) }
    LaunchedEffect(session?.ended) {
        if (session?.ended != true) {
            upNextDismissed = false
            upNextSeconds = null
        }
    }
    LaunchedEffect(session?.ended, nextArgs, autoplayNext, upNextDismissed) {
        if (session?.ended != true || nextArgs == null || !autoplayNext || upNextDismissed) return@LaunchedEffect
        for (second in 5 downTo 1) {
            upNextSeconds = second
            delay(1_000)
        }
        upNextSeconds = null
        onPlayNext(nextArgs)
    }

    val toggleFullscreen = {
        fullscreen = !fullscreen
        deps.player.setFullscreen(fullscreen)
    }

    DisposableEffect(Unit) {
        deps.player.onScreenShown()
        onDispose {
            deps.player.setFullscreen(false)
            deps.player.onScreenHidden()
        }
    }

    LaunchedEffect(args) { runCatching { focus.requestFocus() } }
    LaunchedEffect(inPictureInPicture) {
        if (inPictureInPicture) controlsVisible = false
    }

    LaunchedEffect(args, resolveAttempt) {
        resolving = true
        deps.recents?.record(
            RecentSeries(
                id = args.id,
                title = args.title,
                cover = args.cover,
                score = args.score,
                episodeTitle = args.episodeTitle,
                season = args.season,
                episode = args.episode,
                absoluteEpisode = args.absoluteEpisode,
                positionMs = resumeFrom,
                durationMs = 0L,
                titleRomaji = headerDetail?.title?.romaji,
                titleEnglish = headerDetail?.title?.english,
                titleNative = headerDetail?.title?.native,
            ),
        )
        val resp = withContext(Dispatchers.IO) {
            runCatching {
                deps.core.playback.play(
                    args.id.toString(),
                    args.season,
                    args.episode,
                    forceRefresh = resolveAttempt > 0,
                    absoluteEpisode = args.absoluteEpisode,
                    episodeTitle = args.episodeTitle,
                )
            }.onFailure {
                AppLog.w("playback", "Play resolution failed for ${args.id} S${args.season} E${args.episode}", it)
            }.getOrNull()
        }
        sources = resp?.sources.orEmpty()
        val preferred = deps.progress.audioPreference(args.id) ?: deps.progress.defaultAudioLanguage()
        val preferredIndex = sources.indexOfFirst { source ->
            source.languages.any { it.equals(preferred, ignoreCase = true) }
        }
        index = preferredIndex.coerceAtLeast(0)
        failed = sources.isEmpty()
        resolving = false
        status = if (sources.isEmpty()) (resp?.error ?: "No playable source.") else ""

        if (sources.isNotEmpty()) {
            delay(900)
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    deps.core.playback.play(
                        args.id.toString(),
                        args.season,
                        args.episode,
                        forceRefresh = true,
                        absoluteEpisode = args.absoluteEpisode,
                        episodeTitle = args.episodeTitle,
                    )
                }.onFailure {
                    AppLog.w("playback", "Play source refresh failed for ${args.id} S${args.season} E${args.episode}", it)
                }.getOrNull()
            }
            if (fresh != null && fresh.sources.isNotEmpty()) {
                val oldLanguages = sources.flatMap { it.languages }.toSet()
                val newLanguages = fresh.sources.flatMap { it.languages }.toSet()
                if (newLanguages.size > oldLanguages.size && newLanguages.containsAll(oldLanguages)) {
                    val position = session?.positionMs ?: resumeFrom
                    val preferred = deps.progress.audioPreference(args.id) ?: deps.progress.defaultAudioLanguage()
                    val targetIndex = fresh.sources.indexOfFirst { source ->
                        source.languages.any { it.equals(preferred, ignoreCase = true) }
                    }
                    resumeFrom = position
                    sources = fresh.sources
                    index = if (targetIndex >= 0) targetIndex else index.coerceIn(0, fresh.sources.lastIndex)
                }
            }
        }
    }

    LaunchedEffect(session?.audioTracks, sources, index) {
        val current = session ?: return@LaunchedEffect
        val preferred = deps.progress.audioPreference(args.id) ?: deps.progress.defaultAudioLanguage()
        val source = sources.getOrNull(index)
        val track = current.audioTracks.firstOrNull {
            languageLabelFor(it, source).equals(preferred, ignoreCase = true)
        }
        if (track != null) {
            if (current.activeAudioId != track.id) current.selectAudio(track.id)
            return@LaunchedEffect
        }
        val targetIndex = sources.indexOfFirst { playSource ->
            playSource.languages.any { it.equals(preferred, ignoreCase = true) }
        }
        if (targetIndex >= 0 && targetIndex != index) {
            resumeFrom = current.positionMs
            index = targetIndex
        }
    }

    LaunchedEffect(sources) {
        if (sources.isEmpty()) return@LaunchedEffect
        val seeded = sources.mapNotNull { source ->
            val cached = deps.core.playback.cachedResolutions(source.url)
            if (cached.isEmpty()) null else source.url to cached.map { PlayerResolution(it.id, it.label) }
        }.toMap()
        if (seeded.isNotEmpty()) probedResolutions = probedResolutions + seeded

        val missing = sources.map { it.url }.distinct()
            .filter { it !in probedResolutions && it !in seeded }
        if (missing.isEmpty()) return@LaunchedEffect
        val results = coroutineScope {
            missing.map { url ->
                async {
                    url to runCatching { deps.player.probeResolutions(url) }
                        .onFailure { AppLog.w("player", "Resolution probe failed", it) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        probedResolutions = probedResolutions + results
        results.forEach { (url, resolutions) ->
            deps.core.playback.storeResolutions(
                url,
                resolutions.map { StreamResolution(it.id, it.label) },
            )
        }
    }

    LaunchedEffect(session?.resolutions, session?.activeResolutionId, pendingResolution) {
        val label = pendingResolution ?: return@LaunchedEffect
        val current = session ?: return@LaunchedEffect
        val list = current.resolutions
        if (list.isEmpty()) return@LaunchedEffect
        val target = if (label == "Auto") {
            list.firstOrNull { it.id == "auto" }
        } else {
            list.firstOrNull { it.label.equals(label, ignoreCase = true) }
        }
        if (target != null) current.selectResolution(target.id)
        pendingResolution = null
    }

    LaunchedEffect(poke) { controlsVisible = true }

    LaunchedEffect(poke, controlsVisible, session?.playing) {
        if (controlsVisible && session?.playing == true) {
            delay(2600)
            controlsVisible = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.F -> { toggleFullscreen(); true }
                    Key.Escape -> { onClose(); true }
                    Key.Spacebar -> { session?.playPause(); true }
                    Key.DirectionRight -> {
                        session?.let { it.seekTo(it.positionMs + 10_000) }
                        true
                    }
                    Key.DirectionLeft -> {
                        session?.let { it.seekTo((it.positionMs - 10_000).coerceAtLeast(0)) }
                        true
                    }
                    else -> false
                }
            },
    ) {
        LaunchedEffect(source?.url, onlineSubtitles, subtitleLang, subtitlesOn) {
            val currentSource = source ?: return@LaunchedEffect
            onlineTracks = emptyList()
            if (!onlineSubtitles || !subtitlesOn || currentSource.subtitles.isNotEmpty()) {
                subtitleFetching = false
                return@LaunchedEffect
            }
            subtitleFetching = true
            val detail = headerDetail ?: withContext(Dispatchers.IO) {
                runCatching { deps.core.details.byId(args.id.toString()) }.getOrNull()
            }
            if (detail != null) {
                onlineTracks = withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            deps.core.subtitles.forEpisode(
                                detail = detail,
                                season = args.season,
                                episode = args.episode,
                                language = subtitleLang,
                                ctx = deps.core.ctx,
                            )
                        }.onFailure { AppLog.w("subtitles", "Online subtitle fetch failed", it) }
                            .getOrDefault(emptyList())
                    }
                } ?: emptyList()
            }
            subtitleFetching = false
        }

        // Native (manifest) tracks win; otherwise use the external overlay.
        LaunchedEffect(subtitlesOn, session?.subtitleTracks) {
            val active = session ?: return@LaunchedEffect
            if (!subtitlesOn) {
                if (active.activeSubtitleId != null) active.selectSubtitle("off")
            } else if (active.activeSubtitleId == null && active.subtitleTracks.isNotEmpty()) {
                active.selectSubtitle(active.subtitleTracks.first().id)
            }
        }
        LaunchedEffect(externalTracks, subtitlesOn, session?.subtitleTracks, preferExternal) {
            val native = session?.subtitleTracks.orEmpty()
            if (!subtitlesOn) {
                cues = emptyList()
                return@LaunchedEffect
            }
            val externalAllowed = native.isEmpty() || preferExternal
            if (!externalAllowed) return@LaunchedEffect
            if (activeExternalUrl == null && externalTracks.isNotEmpty()) {
                val pick = externalTracks.firstOrNull { it.default } ?: externalTracks.first()
                activeExternalUrl = pick.url
                if (preferExternal && native.isNotEmpty()) session?.selectSubtitle("off")
            }
        }
        LaunchedEffect(activeExternalUrl, subtitlesOn) {
            val url = activeExternalUrl
            if (!subtitlesOn || url == null) {
                cues = emptyList()
                return@LaunchedEffect
            }
            cues = withContext(Dispatchers.IO) {
                runCatching { deps.core.subtitles.fetchCues(url, deps.core.ctx) }
                    .onFailure { AppLog.w("subtitles", "Subtitle file parse failed", it) }
                    .getOrDefault(emptyList())
            }
            AppLog.i("subtitles", "External cues loaded: " + cues.size + " from " + url.take(80))
        }

        if (failed) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Couldn't play this episode", color = Color(0xFFF5F5F2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val detail = playerError ?: status.takeIf { it.isNotBlank() }
                if (detail != null) {
                    Text(
                        detail,
                        color = Color(0xFF9B9B96),
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "Retry",
                    color = Color(0xFFF5F5F2),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF151515))
                        .clickable {
                            playerError = null
                            failed = false
                            resolving = true
                            refreshedOnce = false
                            status = ""
                            resolveAttempt += 1
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        } else if (source == null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (resolving) "Finding a source…" else "Starting stream…",
                    color = Color(0xFF9B9B96),
                    fontSize = 13.sp,
                )
            }
        } else {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                poke += 1
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { session?.playPause() },
                            onDoubleTap = { offset ->
                                val active = session ?: return@detectTapGestures
                                val width = size.width.toFloat()
                                when {
                                    offset.x < width / 3f ->
                                        active.seekTo((active.positionMs - 10_000).coerceAtLeast(0))
                                    offset.x > width * 2f / 3f ->
                                        active.seekTo(active.positionMs + 10_000)
                                    else -> active.playPause()
                                }
                                controlsVisible = true
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                deps.player.Video(
                    url = source.url,
                    resumeMs = resumeFrom,
                    onProgress = { pos, dur ->
                        deps.progress.save(args.id, args.season, args.episode, pos, dur)
                        deps.recents?.record(
                            RecentSeries(
                                id = args.id,
                                title = args.title,
                                cover = args.cover,
                                score = args.score,
                                episodeTitle = args.episodeTitle,
                                season = args.season,
                                episode = args.episode,
                                absoluteEpisode = args.absoluteEpisode,
                                positionMs = pos,
                                durationMs = dur,
                                titleRomaji = headerDetail?.title?.romaji,
                                titleEnglish = headerDetail?.title?.english,
                                titleNative = headerDetail?.title?.native,
                            ),
                        )
                    },
                    onError = { reason ->
                        playerError = reason
                        val active = session
                        val resumeAt = active?.positionMs ?: resumeFrom
                        val preferred = deps.progress.audioPreference(args.id)
                        val currentLanguage = preferred
                            ?: active?.audioTracks?.firstOrNull { it.id == active.activeAudioId }?.label
                        val candidates = ((index + 1) until sources.size).toList()
                        val sameLanguage = candidates.firstOrNull { candidate ->
                            currentLanguage != null && sources[candidate].languages.any {
                                it.equals(currentLanguage, ignoreCase = true)
                            }
                        }
                        val anyLanguage = candidates.firstOrNull()
                        when {
                            sameLanguage != null -> {
                                index = sameLanguage
                                resumeFrom = resumeAt
                            }
                            !refreshedOnce && currentLanguage != null -> {
                                refreshedOnce = true
                                resumeFrom = resumeAt
                                resolveAttempt += 1
                                failed = false
                            }
                            anyLanguage != null -> {
                                index = anyLanguage
                                resumeFrom = resumeAt
                            }
                            else -> {
                                failed = true
                                status = "All sources failed."
                            }
                        }
                    },
                    onSession = { newSession ->
                        if (newSession != null) {
                            session = newSession
                        }
                    },
                )

                val current = session
                if (controlsVisible && !inPictureInPicture) {
                    if (!fullscreen) {
                        Row(
                            Modifier.align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xB30C0C0C))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.widthIn(max = 520.dp)) {
                                    Text(
                                        headerLabel,
                                        color = Color(0xFFC9C9C3),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        headerEpisodeTitle,
                                        color = Color(0xFFF5F5F2),
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconSquareButton(SquareIcon.Close, active = false, background = Color(0xB3151515)) { onClose() }
                        }
                    }
                    if (current != null) {
                        PlayerControls(
                            session = current,
                            languages = buildLanguages(sources, index, current),
                            subtitles = current.subtitleTracks + externalTracks.mapIndexed { i, track ->
                                PlayerSubtitleTrack(
                                    id = "ext:" + track.url,
                                    label = track.label ?: track.language ?: "Subtitle " + (i + 1),
                                    detail = track.language,
                                    selected = track.url == activeExternalUrl,
                                )
                            },
                            subtitlesOn = subtitlesOn,
                            onToggleSubtitles = {
                                subtitlesOn = !subtitlesOn
                                deps.progress.saveSubtitlesEnabled(args.id, subtitlesOn)
                            },
                            subtitleLanguage = subtitleLang,
                            onSelectSubtitleLanguage = { language ->
                                subtitleLang = language
                                preferExternal = true
                                activeExternalUrl = null
                                cues = emptyList()
                                subtitlesOn = true
                                deps.progress.saveSubtitlesEnabled(args.id, true)
                                deps.progress.saveSubtitleLanguage(language)
                            },
                            subtitlesMissing = subtitlesOn && !subtitleFetching &&
                                current.subtitleTracks.isEmpty() && externalTracks.isEmpty(),
                            externalActive = activeExternalUrl != null,
                            externalCueCount = cues.size,
                            resolutions = buildResolutionOptions(
                                sources = sources,
                                index = index,
                                session = current,
                                probed = probedResolutions,
                                language = activeLanguage,
                            ),
                            onSelectResolution = { option ->
                                if (option.sourceIndex == index) {
                                    current.selectResolution(option.id ?: "auto")
                                } else {
                                    val position = current.positionMs
                                    pendingResolution = option.label
                                    controlsVisible = true
                                    scope.launch {
                                        kotlinx.coroutines.delay(160)
                                        resumeFrom = position
                                        index = option.sourceIndex
                                    }
                                }
                            },
                            onSelectLanguage = { language ->
                                deps.progress.saveAudioPreference(args.id, language.label)
                                if (language.trackId != null) {
                                    current.selectAudio(language.trackId)
                                } else if (language.sourceIndex != null && language.sourceIndex != index) {
                                    val targetIndex = language.sourceIndex
                                    val position = current.positionMs
                                    controlsVisible = true
                                    scope.launch {
                                        kotlinx.coroutines.delay(160)
                                        resumeFrom = position
                                        index = targetIndex
                                    }
                                }
                            },
                            onSelectSubtitle = { id ->
                                if (id.startsWith("ext:")) {
                                    preferExternal = true
                                    activeExternalUrl = id.removePrefix("ext:")
                                    cues = emptyList()
                                    current.selectSubtitle("off")
                                } else if (id == "off") {
                                    preferExternal = false
                                    activeExternalUrl = null
                                    cues = emptyList()
                                    current.selectSubtitle("off")
                                } else {
                                    preferExternal = false
                                    activeExternalUrl = null
                                    cues = emptyList()
                                    current.selectSubtitle(id)
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                val caption = if (subtitlesOn) {
                    val position = session?.positionMs ?: 0L
                    cues.firstOrNull { position >= it.startMs && position < it.endMs }?.text
                } else null
                if (caption != null && !inPictureInPicture) {
                    Text(
                        caption,
                        color = Color.White,
                        fontSize = when (subtitleSize) {
                            0 -> 13.sp
                            2 -> 20.sp
                            else -> 16.sp
                        },
                        lineHeight = when (subtitleSize) {
                            0 -> 17.sp
                            2 -> 25.sp
                            else -> 21.sp
                        },
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = Shadow(Color.Black, Offset(0f, 1.5f), blurRadius = 6f),
                        ),
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 96.dp),
                    )
                }

                val upcoming = nextArgs
                val upNext = upNextSeconds
                if (upcoming != null && upNext != null && !inPictureInPicture) {
                    var dragOffset by remember(args) { mutableStateOf(Offset.Zero) }
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 88.dp)
                            .width(240.dp)
                            .aspectRatio(16f / 10f)
                            .graphicsLayer {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                alpha = 1f - (dragOffset.getDistance() / 600f).coerceIn(0f, 0.5f)
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF151515))
                            .pointerInput(upcoming) {
                                detectDragGestures(
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                    },
                                    onDragEnd = {
                                        if (kotlin.math.abs(dragOffset.x) > size.width * 0.3f ||
                                            dragOffset.y > size.height * 0.3f
                                        ) {
                                            upNextDismissed = true
                                            upNextSeconds = null
                                        }
                                        dragOffset = Offset.Zero
                                    },
                                    onDragCancel = { dragOffset = Offset.Zero },
                                )
                            }
                            .clickable {
                                upNextDismissed = true
                                upNextSeconds = null
                                onPlayNext(upcoming)
                            },
                    ) {
                        deps.images.Image(nextEntry?.thumb, Modifier.matchParentSize(), ContentScale.Crop)
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                                ),
                            ),
                        )
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "UP NEXT · ${upNext}s",
                                color = Color(0xFFD9D9D3),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                            )
                            Text(
                                upcoming.episodeTitle ?: "Episode ${upcoming.episode}",
                                color = Color(0xFFF5F5F2),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            Text(
                                "S${upcoming.season} E${upcoming.episode} · tap to play · swipe away",
                                color = Color(0xFFB5B5AE),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    session: PlayerSession,
    languages: List<PlayerLanguage>,
    subtitles: List<PlayerSubtitleTrack>,
    subtitlesOn: Boolean,
    onToggleSubtitles: () -> Unit,
    subtitleLanguage: String,
    onSelectSubtitleLanguage: (String) -> Unit,
    subtitlesMissing: Boolean,
    externalActive: Boolean,
    externalCueCount: Int,
    resolutions: List<PlayerResolutionOption>,
    onSelectResolution: (PlayerResolutionOption) -> Unit,
    onSelectLanguage: (PlayerLanguage) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember { mutableStateOf<Float?>(null) }
    val duration = session.durationMs
    val shownFraction = preview
        ?: if (duration > 0) (session.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPosition = if (preview != null && duration > 0) (preview!! * duration).toLong() else session.positionMs

    Row(
        modifier.fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB30C0C0C))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayPauseButton(session.playing) { session.playPause() }
        Spacer(Modifier.width(10.dp))
        SeekBar(
            fraction = shownFraction,
            onPreview = { preview = it },
            onSeek = {
                preview = null
                if (duration > 0) session.seekTo((it * duration).toLong())
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            formatTime(shownPosition) + " / " + formatTime(duration),
            color = Color(0xFFC9C9C3),
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(8.dp))
        AudioMenuButton(languages, onSelectLanguage)
        Spacer(Modifier.width(8.dp))
        SubtitleMenuButton(
            subtitles = subtitles,
            activeId = session.activeSubtitleId,
            enabled = subtitlesOn,
            language = subtitleLanguage,
            missing = subtitlesMissing,
            externalActive = externalActive,
            externalCueCount = externalCueCount,
            onToggleEnabled = onToggleSubtitles,
            onSelectLanguage = onSelectSubtitleLanguage,
            onSelect = onSelectSubtitle,
        )
        Spacer(Modifier.width(8.dp))
        ResolutionButton(resolutions, onSelectResolution)
    }
}

@Composable
private fun PlayerIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(40.dp)
            .clip(CircleShape)
            .background(if (hovered) Color(0xFF232323) else Color(0xFF151515))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    PlayerIconButton(onClick) {
        Canvas(Modifier.size(12.dp)) {
            val color = Color(0xFFF5F5F2)
            if (playing) {
                val barWidth = size.width * 0.3f
                val radius = CornerRadius(1.2f, 1.2f)
                drawRoundRect(color, size = Size(barWidth, size.height), cornerRadius = radius)
                drawRoundRect(color, topLeft = Offset(size.width - barWidth, 0f), size = Size(barWidth, size.height), cornerRadius = radius)
            } else {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    onPreview: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var lastFraction by remember { mutableStateOf(fraction) }

    Canvas(
        modifier.height(18.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / widthPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        lastFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                        onPreview(lastFraction)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(lastFraction)
                    },
                    onDragCancel = {
                        dragging = false
                        onSeek(lastFraction)
                    },
                ) { change, _ ->
                    change.consume()
                    lastFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                    onPreview(lastFraction)
                }
            },
    ) {
        val track = 3.dp.toPx()
        val y = (size.height - track) / 2f
        val radius = CornerRadius(track / 2f, track / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.20f), topLeft = Offset(0f, y), size = Size(size.width, track), cornerRadius = radius)
        val filled = size.width * fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(Color(0xFFF5F5F2), topLeft = Offset(0f, y), size = Size(filled, track), cornerRadius = radius)
        }
        if (dragging) {
            drawCircle(Color(0xFFF5F5F2), radius = 5.dp.toPx(), center = Offset(filled, size.height / 2f))
        }
    }
}

@Composable
private fun ResolutionButton(
    options: List<PlayerResolutionOption>,
    onSelect: (PlayerResolutionOption) -> Unit,
) {
    if (options.size <= 1) return
    var open by remember { mutableStateOf(false) }
    val active = options.firstOrNull { it.selected } ?: options.first()

    Box {
        PlayerIconButton(onClick = { open = true }) {
            Canvas(Modifier.size(16.dp)) {
                val color = Color(0xFFF5F5F2)
                val stroke = 1.4.dp.toPx()
                drawRoundRect(
                    color,
                    topLeft = Offset(size.width * 0.16f, size.height * 0.12f),
                    size = Size(size.width * 0.68f, size.height * 0.5f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    color,
                    topLeft = Offset(size.width * 0.05f, size.height * 0.4f),
                    size = Size(size.width * 0.68f, size.height * 0.5f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            color = if (option.label == active.label) Color(0xFFF5F5F2) else Color(0xFFC9C9C3),
                            fontSize = 12.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioMenuButton(languages: List<PlayerLanguage>, onSelect: (PlayerLanguage) -> Unit) {
    if (languages.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val active = languages.firstOrNull { it.selected } ?: languages.first()

    Box {
        PlayerIconButton(onClick = { open = true }) {
            Canvas(Modifier.size(16.dp)) {
                val color = Color(0xFFF5F5F2)
                val bodyWidth = size.width * 0.22f
                drawRoundRect(
                    color,
                    topLeft = Offset(0f, size.height * 0.33f),
                    size = Size(bodyWidth, size.height * 0.34f),
                    cornerRadius = CornerRadius(1f, 1f),
                )
                val cone = Path().apply {
                    moveTo(bodyWidth, size.height * 0.42f)
                    lineTo(size.width * 0.46f, size.height * 0.2f)
                    lineTo(size.width * 0.46f, size.height * 0.8f)
                    lineTo(bodyWidth, size.height * 0.58f)
                    close()
                }
                drawPath(cone, color)
                drawArc(
                    color,
                    startAngle = -55f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.42f, size.height * 0.24f),
                    size = Size(size.width * 0.5f, size.height * 0.52f),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Text(
                            language.label,
                            color = if (language.label == active.label) Color(0xFFF5F5F2) else Color(0xFFC9C9C3),
                            fontSize = 12.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(language)
                    },
                )
            }
        }
    }
}

@Composable
private fun SubtitleMenuButton(
    subtitles: List<PlayerSubtitleTrack>,
    activeId: String?,
    enabled: Boolean,
    language: String,
    missing: Boolean,
    externalActive: Boolean,
    externalCueCount: Int,
    onToggleEnabled: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val active = subtitles.firstOrNull { it.id == activeId }
    Box {
        PlayerIconButton(onClick = { open = true }) {
            Text(
                "CC",
                color = when {
                    !enabled -> Color(0xFF6F6F6F)
                    active != null -> Color(0xFFF5F5F2)
                    else -> Color(0xFF9B9B96)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (enabled) "Subtitles: On" else "Subtitles: Off",
                        color = Color(0xFFF5F5F2),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                onClick = {
                    onToggleEnabled()
                },
            )
            DropdownMenuItem(
                text = { Text("Language: " + language, color = Color(0xFF9B9B96), fontSize = 11.sp) },
                onClick = {},
                enabled = false,
            )
            listOf("English", "German", "Japanese").forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            color = if (option == language) Color(0xFFF5F5F2) else Color(0xFFC9C9C3),
                            fontSize = 12.sp,
                        )
                    },
                    onClick = {
                        onSelectLanguage(option)
                    },
                )
            }
            if (enabled) {
                DropdownMenuItem(
                    text = { Text("Off", color = if (active == null) Color(0xFFF5F5F2) else Color(0xFFC9C9C3), fontSize = 12.sp) },
                    onClick = {
                        open = false
                        onSelect("off")
                    },
                )
                subtitles.forEach { track ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                listOfNotNull(track.label, track.detail).joinToString(" · "),
                                color = if (track.id == activeId) Color(0xFFF5F5F2) else Color(0xFFC9C9C3),
                                fontSize = 12.sp,
                            )
                        },
                        onClick = {
                            open = false
                            onSelect(track.id)
                        },
                    )
                }
                if (subtitles.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (missing) "No " + language + " subtitles found for this episode."
                                else "Looking for subtitles…",
                                color = Color(0xFF9B9B96),
                                fontSize = 11.sp,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                } else if (externalActive) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (externalCueCount > 0) "Loaded " + externalCueCount + " cues"
                                else "Downloading subtitle…",
                                color = Color(0xFF9B9B96),
                                fontSize = 11.sp,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
