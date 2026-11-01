package dev.anime

import dev.anime.registry.Registry
import dev.anime.search.Aggregator
import dev.anime.source.AniListSource
import dev.anime.source.ExtRepos
import dev.anime.search.EpisodeIndex
import dev.anime.search.PlaybackService
import dev.anime.source.SourceContext
import dev.anime.source.SourceAdapter
import dev.anime.source.buildHttpClient
import dev.anime.store.Store
import dev.anime.search.Details
import dev.anime.search.StreamAggregator
import dev.anime.source.StreamSource
import dev.anime.source.TvmazeEpisodesSource
import dev.anime.web.HlsProxy
import dev.anime.web.ThumbService
import dev.anime.web.apiModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

private const val UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = Store(System.getenv("DB_PATH") ?: "data/anime.db")
    val ctx = SourceContext(buildHttpClient(UA))

    val sources: List<SourceAdapter> = listOf(AniListSource())

    val registry = Registry(sources, store, ctx, scope)

    val streamStore = Store(System.getenv("STREAM_DB_PATH") ?: "data/stream.db", 32L * 1024 * 1024)
    // Pull all site sources from the kohi-den / yuzono extension lists, then rank at start().
    val streamSources: List<StreamSource> = listOf(TvmazeEpisodesSource()) + runBlocking {
        ExtRepos.load(ctx)
    }
    val streamRegistry = Registry(streamSources, streamStore, ctx, scope)

    val details = Details(ctx)
    val aggregator = Aggregator(registry, store, ctx)
    val streamAgg = StreamAggregator(streamRegistry, streamStore, ctx)
    val episodeIndex = EpisodeIndex(
        streamStore,
        ctx,
    )
    val playback = PlaybackService(
        ctx,
        details,
        cacheDir = java.io.File(System.getenv("PLAY_CACHE_PATH") ?: "data/play-cache"),
    )
    val hlsProxy = HlsProxy(ctx, System.getenv("HLS_CACHE_PATH") ?: "data/hls-cache")

    runBlocking {
        registry.init()
        streamRegistry.init()
    }
    registry.start()
    streamRegistry.start()

    val thumbs = ThumbService(System.getenv("THUMB_PATH") ?: "data/thumbs", ctx)

    embeddedServer(CIO, host = System.getenv("BIND_HOST") ?: "0.0.0.0", port = port) {
        apiModule(registry, aggregator, store, thumbs, details, episodeIndex, streamRegistry, streamAgg, playback, hlsProxy)
    }.start(wait = true)
}
