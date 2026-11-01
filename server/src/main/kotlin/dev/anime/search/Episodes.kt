package dev.anime.search

import dev.anime.model.DetailResult
import dev.anime.model.EpisodeAltTitle
import dev.anime.model.EpisodeEntry
import dev.anime.model.SeasonInfo
import dev.anime.model.SeasonList
import dev.anime.source.SourceContext
import dev.anime.source.TvmazeEpisodesSource
import dev.anime.store.Jsonx
import dev.anime.store.Store
import kotlinx.coroutines.withTimeoutOrNull

private const val EPISODES_CACHE_TTL = 30 * 60_000L
private const val PROVIDER_TIMEOUT_MS = 10_000L

class EpisodeIndex(
    private val store: Store,
    private val ctx: SourceContext,
) {
    private val tvmaze = TvmazeEpisodesSource()

    suspend fun byDetail(detail: DetailResult): SeasonList? {
        val key = "ep-v1:" + detail.id
        val cached = store.searchCacheGet(key)
        if (cached != null && System.currentTimeMillis() - cached.second <= EPISODES_CACHE_TTL) {
            return runCatching { Jsonx.decodeFromString<SeasonList>(cached.first) }.getOrNull()
        }
        val built = runCatching { fetch(detail) }.getOrNull()
        if (built != null) {
            runCatching { store.searchCachePut(key, Jsonx.encodeToString(built)) }
            return built
        }
        return cached?.let { c -> runCatching { Jsonx.decodeFromString<SeasonList>(c.first) }.getOrNull() }
    }

    private suspend fun fetch(detail: DetailResult): SeasonList? {
        val names = buildList {
            detail.title?.romaji?.let { add(it) }
            detail.title?.english?.let { add(it) }
            detail.title?.native?.let { add(it) }
            addAll(detail.synonyms)
        }.map { it.trim() }.filter { it.length >= 2 }.distinct()

        // Best-first: providers are ordered; the first success ends the chain
        // and only its data feeds the view, so no second provider is touched.
        val best = runCatching {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS + PROVIDER_TIMEOUT_MS) { tvmazeProvider(detail, names) }
        }.getOrNull()

        // Without a curated provider, fall back to AniList's episode count so the
        // episode list still renders (numbers only) instead of failing outright.
        val entries = best?.entries ?: placeholderEntries(detail)
        val seasons = merge(listOf(entries))
        val total = seasons.sumOf { it.episodes.size }

        return SeasonList(
            id = detail.id,
            title = detail.title?.english ?: detail.title?.romaji,
            season = detail.season,
            seasonYear = detail.seasonYear,
            episodesCount = detail.episodes,
            seasons = seasons,
            totalEpisodes = total,
            providerCount = if (best != null) 1 else 0,
        )
    }

    private fun placeholderEntries(detail: DetailResult): List<MergedEntry> {
        val count = detail.episodes ?: if (detail.format == "MOVIE") 1 else 0
        if (count <= 0) return emptyList()
        return (1..count).map { n ->
            MergedEntry(
                flat = null,
                key = EpisodeKey(1, n),
                source = "AniList",
                title = null,
                curated = true,
            )
        }
    }

    private data class ProviderBatch(val entries: List<MergedEntry>)

    private suspend fun tvmazeProvider(detail: DetailResult, names: List<String>): ProviderBatch? {
        val show = tvmaze.findShow(names, detail.seasonYear, ctx) ?: return null
        val raw = tvmaze.episodes(show.id, ctx)
        if (raw.isEmpty()) return null
        return ProviderBatch(raw.map { e ->
            MergedEntry(
                flat = null,
                key = EpisodeKey(e.season, e.number ?: 0),
                source = "TVmaze",
                title = e.title?.trim()?.takeUnless { it.isBlank() },
                thumb = e.thumb,
                url = e.url,
                airdate = e.airdate,
                curated = true,
            )
        })
    }

    private data class EpisodeKey(val season: Int, val number: Int)

    private data class MergedEntry(
        val flat: Int?,
        val key: EpisodeKey?,
        val source: String,
        val title: String?,
        val thumb: String? = null,
        val url: String? = null,
        val airdate: String? = null,
        val curated: Boolean = false,
    )

    private class Cell {
        var title: String? = null
        var thumb: String? = null
        var url: String? = null
        var airdate: String? = null
        var curatedTitle: String? = null
        val sources = LinkedHashSet<String>()
        val variants = LinkedHashMap<String, EpisodeAltTitle>()
    }

    private fun merge(groups: List<List<MergedEntry>>): List<SeasonInfo> {
        val sizes = HashMap<Int, Int>()
        for (g in groups) for (e in g) {
            val k = e.key ?: continue
            if (e.curated && k.number > 0) sizes[k.season] = maxOf(sizes.getOrDefault(k.season, 0), k.number)
        }

        val cells = LinkedHashMap<EpisodeKey, Cell>()
        for (group in groups) for (e in group) {
            val k = e.key
                ?: e.flat?.let { seatFlat(it, sizes) }
                ?: continue
            val cell = cells.getOrPut(k) { Cell() }
            cell.sources.add(e.source)
            if (e.title != null) cell.variants.putIfAbsent(norm(e.title), EpisodeAltTitle(e.source, e.title))
            if (e.curated) cell.curatedTitle = e.title
            else if (cell.title == null) cell.title = e.title
            if (cell.thumb == null) cell.thumb = e.thumb
            if (cell.url == null) cell.url = e.url
            if (cell.airdate == null) cell.airdate = e.airdate
        }

        return cells.entries
            .groupBy({ it.key.season }, { it })
            .map { (season, list) ->
                SeasonInfo(
                    season = season,
                    episodes = list.sortedBy { it.key.number }.map { (key, cell) ->
                        val canonical = cell.curatedTitle ?: cell.title
                            ?: cell.variants.values.firstOrNull()?.title
                        EpisodeEntry(
                            season = key.season,
                            number = key.number,
                            title = canonical,
                            altTitles = cell.variants.values
                                .filter { it.title != canonical && norm(it.title) != norm(canonical ?: "") }
                                .take(6),
                            thumb = cell.thumb,
                            url = cell.url,
                            sources = cell.sources.toList(),
                            airdate = cell.airdate,
                        )
                    },
                )
            }
            .sortedBy { it.season }
    }

    private fun seatFlat(n: Int, sizes: Map<Int, Int>): EpisodeKey {
        if (n <= 0) return EpisodeKey(0, n)
        var acc = 0
        for ((s, c) in sizes.toSortedMap()) {
            if (n <= acc + c) return EpisodeKey(s, n - acc)
            acc += c
        }
        val lastSeason = sizes.keys.maxOrNull()
        return if (lastSeason == null) EpisodeKey(1, n) else EpisodeKey(lastSeason, n - acc + sizes[lastSeason]!!)
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
