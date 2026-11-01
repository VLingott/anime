package dev.anime.search

import dev.anime.cache.ByteLruCache
import dev.anime.cache.DiskCache
import dev.anime.model.DetailResult
import dev.anime.source.SourceContext
import dev.anime.source.TvmazeEpisode
import dev.anime.source.TvmazeEpisodesSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class CachedSeries(
    val detail: DetailResult,
    val seasons: List<Pair<Int, List<TvmazeEpisode>>>,
)

data class SeriesView(
    val detail: DetailResult,
    val seasons: List<Pair<Int, List<TvmazeEpisode>>>,
)

class SeriesRepository(
    private val details: Details,
    private val tvmaze: TvmazeEpisodesSource,
    cacheDir: File? = null,
) {
    private val cache = ByteLruCache<Long, SeriesView>(32L * 1024 * 1024, ::weight)
    private val disk = cacheDir?.let { DiskCache(File(it, "series"), 32L * 1024 * 1024) }
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    fun peek(id: Long): SeriesView? {
        cache.get(id)?.let { return it }
        val stored = disk?.read(id.toString()) ?: return null
        val decoded = runCatching { json.decodeFromString<CachedSeries>(stored) }.getOrNull() ?: return null
        val view = SeriesView(decoded.detail, decoded.seasons)
        cache.put(id, view)
        return view
    }

    suspend fun load(id: Long, ctx: SourceContext, refresh: Boolean = false): SeriesView? {
        if (!refresh) peek(id)?.let { return it }
        return locks.getOrPut(id) { Mutex() }.withLock {
            if (!refresh) peek(id)?.let { return@withLock it }
            val detail = details.byId(id.toString()) ?: return@withLock null
            val seasons = if (detail.format != "MOVIE") {
                val names = listOfNotNull(detail.title?.romaji, detail.title?.english, detail.title?.native) + detail.synonyms
                val show = tvmaze.findShow(names, detail.seasonYear, ctx)
                val grouped = if (show != null) {
                    tvmaze.episodes(show.id, ctx)
                        .filter { it.number != null }
                        .groupBy { it.season }
                        .toSortedMap()
                        .map { (season, list) -> season to list.sortedBy { it.number } }
                } else emptyList()
                grouped.ifEmpty {
                    val count = detail.episodes ?: 0
                    if (count > 0) listOf(1 to (1..count).map { n -> TvmazeEpisode(season = 1, number = n) }) else emptyList()
                }
            } else emptyList()

            val view = SeriesView(detail, seasons)
            cache.put(id, view)
            disk?.write(id.toString(), json.encodeToString(CachedSeries(detail, seasons)))
            view
        }
    }

    private fun weight(view: SeriesView): Long {
        val d = view.detail
        var bytes = 4096L
        bytes += (d.description?.length ?: 0).toLong()
        bytes += (d.bannerImage?.length ?: 0).toLong()
        bytes += (d.coverImage?.length ?: 0).toLong()
        d.synonyms.forEach { bytes += it.length + 16 }
        d.genres.forEach { bytes += it.length + 16 }
        d.tags.forEach { bytes += it.name.length + 32 }
        d.characters.forEach { bytes += it.name.length + (it.image?.length ?: 0) + 48 }
        view.seasons.forEach { (_, episodes) ->
            bytes += 16
            episodes.forEach { ep ->
                bytes += 96 + (ep.title?.length ?: 0) + (ep.thumb?.length ?: 0)
            }
        }
        return bytes
    }
}
