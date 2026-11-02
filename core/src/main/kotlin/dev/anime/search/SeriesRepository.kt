package dev.anime.search

import dev.anime.cache.ByteLruCache
import dev.anime.cache.DiskCache
import dev.anime.model.DetailResult

import dev.anime.source.SourceContext
import dev.anime.source.TvmazeEpisode
import dev.anime.source.TvmazeEpisodesSource
import dev.anime.util.AppLog
import dev.anime.util.hasSeasonMarker
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
    private val disk = cacheDir?.let { DiskCache(File(it, "series-v5"), 32L * 1024 * 1024) }
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    fun peek(id: Long): SeriesView? {
        cache.get(id)?.let { return it }
        val stored = disk?.read(id.toString()) ?: return null
        val decoded = runCatching { json.decodeFromString<CachedSeries>(stored) }
            .onFailure { AppLog.w("series", "Ignoring unreadable series cache for $id", it) }
            .getOrNull() ?: return null
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
                val searchNames = names.flatMap { name ->
                    listOf(
                        name,
                        name.substringBefore(':').trim(),
                        name.replace(Regex("(?i)\\b(\\d+(st|nd|rd|th)\\s+season|season\\s+\\d+|part\\s+\\d+|cour\\s+\\d+)\\b"), " ").trim(),
                    )
                }.filter { it.length >= 2 }.distinctBy { it.lowercase() }
                var show = tvmaze.findShow(searchNames, detail.seasonYear, ctx)
                var raw = if (show != null) tvmaze.episodes(show.id, ctx) else emptyList()
                if (raw.isEmpty()) {
                    kotlinx.coroutines.delay(350)
                    show = tvmaze.findShow(searchNames, detail.seasonYear, ctx)
                    raw = if (show != null) tvmaze.episodes(show.id, ctx) else emptyList()
                }
                val grouped = raw
                    .filter { it.number != null }
                    .groupBy { it.season }
                    .toSortedMap()
                    .map { (season, list) -> season to list.sortedBy { it.number } }
                val selected = selectSeasons(detail, grouped)
                if (acceptsEpisodes(detail, selected)) {
                    selected
                } else {
                    syntheticSeasons(detail)
                }
            } else emptyList()

            val view = SeriesView(detail, seasons)
            if (seasons.isNotEmpty() || detail.format == "MOVIE") {
                cache.put(id, view)
                disk?.write(id.toString(), json.encodeToString(CachedSeries(detail, seasons)))
            }
            view
        }
    }

    private fun selectSeasons(
        detail: DetailResult,
        grouped: List<Pair<Int, List<TvmazeEpisode>>>,
    ): List<Pair<Int, List<TvmazeEpisode>>> {
        if (grouped.size <= 1) return grouped
        if (grouped.size > 12) return grouped
        if (grouped.all { it.first >= 1900 }) return grouped

        val rawTitle = detail.title?.romaji ?: detail.title?.english ?: detail.title?.native
        val marked = rawTitle != null && hasSeasonMarker(rawTitle)
        val format = detail.format?.uppercase()
        if (!marked && (format == "TV" || format == "TV_SHORT")) {
            return grouped
        }

        val target = detail.episodes ?: return grouped
        if (target <= 0) return grouped

        fun seasonYear(episodes: List<TvmazeEpisode>): Int? = episodes
            .mapNotNull { it.airdate?.take(4)?.toIntOrNull() }
            .minOrNull()

        fun distance(episodes: List<TvmazeEpisode>): Int {
            val wanted = detail.seasonYear ?: return 0
            val actual = seasonYear(episodes) ?: return 9_999
            return kotlin.math.abs(actual - wanted)
        }

        val exact = grouped.filter { it.second.size == target }
            .minByOrNull { distance(it.second) }
        if (exact != null) return listOf(exact.first to exact.second)

        for (start in grouped.indices) {
            var sum = 0
            var end = start
            while (end < grouped.size && sum < target) {
                sum += grouped[end].second.size
                end++
            }
            if (sum == target) {
                return grouped.subList(start, end)
            }
        }

        if (format == "SPECIAL" || format == "OVA" || format == "ONA") {
            return listOf(1 to (1..target).map { n -> TvmazeEpisode(season = 1, number = n) })
        }
        val closest = grouped.minByOrNull { kotlin.math.abs(it.second.size - target) }
        if (closest != null && closest.second.size >= target) {
            return listOf(closest.first to closest.second.take(target))
        }
        return grouped
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

    internal companion object {
        /** Formats where TVmaze's parent-series episode list must never be borrowed. */
        val NON_EPISODIC_FORMATS = setOf("OVA", "ONA", "SPECIAL", "MUSIC")

    /**
     * Decide whether a TVmaze episode selection can stand in for this AniList entry.
     *
     * Tvmaze search can return the parent TV series for an OVA/side story
     * ("Elfen Lied: Tooriame nite …" contains "Elfen Lied"), so a selection that
     * cannot match the AniList episode count for a non-episodic format, or that
     * is implausibly larger than it, is rejected and rendered synthetically.
     */
    internal fun acceptsEpisodes(
        detail: DetailResult,
        selected: List<Pair<Int, List<TvmazeEpisode>>>,
    ): Boolean {
        if (selected.isEmpty()) return false
        val format = detail.format?.uppercase()
        val want = detail.episodes
        if (want == null) {
            // No count to check against: never borrow a TV show for OVAs/specials.
            return format !in NON_EPISODIC_FORMATS
        }
        if (want <= 0) return false
        val got = selected.sumOf { it.second.size }
        if (got <= 0) return false
        return if (format in NON_EPISODIC_FORMATS) {
            got == want
        } else {
            got <= want * 2 + 1
        }
    }

    /** Single pseudo-season matching AniList's own episode count. */
    internal fun syntheticSeasons(detail: DetailResult): List<Pair<Int, List<TvmazeEpisode>>> {
        val count = detail.episodes ?: 0
        if (count <= 0) return emptyList()
        return listOf(1 to (1..count).map { n -> TvmazeEpisode(season = 1, number = n) })
    }
    }
}
