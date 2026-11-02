package dev.anime.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

import dev.anime.model.StreamHit
import dev.anime.store.arr
import dev.anime.store.int
import dev.anime.store.obj
import dev.anime.store.str
import dev.anime.util.AppLog
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.math.abs

class TvmazeEpisodesSource : StreamSource {
    override val id = "tvmaze-episodes"
    override val name = "TVmaze Episodes"
    override val baseUrl = "https://www.tvmaze.com"
    override val kind = "stream"
    override val strictTitles = true

    override suspend fun search(query: String, ctx: SourceContext): List<StreamHit> {
        if (query.isBlank()) return emptyList()
        val show = findShow(listOf(query), null, ctx) ?: return emptyList()
        return episodes(show.id, ctx)
            .filter { it.season > 0 && it.number != null }
            .map { e ->
                StreamHit(
                    sourceId = id,
                    sourceName = name,
                    title = e.title ?: "Episode " + e.number,
                    episode = e.number,
                    kind = "S%02dE%02d".format(e.season, e.number),
                    year = e.airdate?.take(4)?.toIntOrNull(),
                    url = e.url,
                    language = e.thumb?.let { "still" },
                )
            }
    }

    override suspend fun probe(ctx: SourceContext): Boolean = search("frieren", ctx).isNotEmpty()

    suspend fun findShow(names: List<String>, seasonYear: Int?, ctx: SourceContext): TvmazeShow? {
        val seen = HashSet<String>()
        val queries = ArrayList<String>()
        for (raw in names) {
            val q = raw.trim()
            if (q.length < 2 || !seen.add(q.lowercase())) continue
            queries += q
        }
        if (queries.isEmpty()) return null

        val perName = coroutineScope {
            queries.map { q ->
                async(Dispatchers.IO) { q to searchShows(q, ctx) }
            }.awaitAll().filter { it.second.isNotEmpty() }
        }
        if (perName.isEmpty()) return null

        val candidates = LinkedHashMap<String, TvmazeCandidate>()
        for ((query, shows) in perName) {
            for (show in shows) {
                val score = Shows.titleScore(show.name, query)
                if (score <= 0) continue
                val distance = seasonYear?.let { y -> show.year?.let { abs(it - y) } }
                val next = TvmazeCandidate(show, score, distance)
                val previous = candidates[show.id]
                if (previous == null || next.betterThan(previous)) candidates[show.id] = next
            }
        }
        val acceptable = candidates.values.filter { Shows.acceptable(it.score, it.yearDistance) }
        val ordered = acceptable.sortedWith(
            compareByDescending<TvmazeCandidate> { it.show.anime }
                .thenByDescending { it.score }
                .thenBy { it.yearDistance ?: Int.MAX_VALUE },
        )
        return ordered.firstOrNull()?.show
    }

    private fun TvmazeCandidate.betterThan(other: TvmazeCandidate): Boolean {
        if (score != other.score) return score > other.score
        val mine = yearDistance ?: Int.MAX_VALUE
        val theirs = other.yearDistance ?: Int.MAX_VALUE
        if (mine != theirs) return mine < theirs
        return show.anime && !other.show.anime
    }

    private suspend fun searchShows(query: String, ctx: SourceContext): List<TvmazeShow> {
        val res = ctx.http.get(
            "https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(query.take(80), Charsets.UTF_8),
        )
        if (res.status.value != 200) {
            AppLog.w("tvmaze", "Show search returned HTTP ${res.status.value} for \"$query\"")
            return emptyList()
        }
        val body = runCatching { Json.parseToJsonElement(res.bodyAsText()).arr() }
            .onFailure { AppLog.w("tvmaze", "Invalid show search response for \"$query\"", it) }
            .getOrNull() ?: return emptyList()
        return body.orEmpty().mapNotNull { el ->
            val s = el.obj()?.get("show")?.obj() ?: return@mapNotNull null
            val genres = s["genres"]?.arr().orEmpty().mapNotNull { g -> g.str() }
            TvmazeShow(
                id = s["id"]?.str() ?: return@mapNotNull null,
                name = s["name"]?.str().orEmpty(),
                year = s["premiered"]?.str()?.take(4)?.toIntOrNull(),
                image = s["image"]?.obj()?.get("medium")?.str(),
                anime = genres.any { it.equals("Anime", ignoreCase = true) },
            )
        }
    }

    suspend fun episodes(showId: String, ctx: SourceContext): List<TvmazeEpisode> =
        runCatching {
            val res = ctx.http.get("https://api.tvmaze.com/shows/$showId/episodes?specials=1")
            if (res.status.value != 200) return@runCatching emptyList()
            Json.parseToJsonElement(res.bodyAsText()).arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                TvmazeEpisode(
                    season = o["season"]?.int() ?: return@mapNotNull null,
                    number = o["number"]?.int(),
                    title = o["name"]?.str(),
                    airdate = o["airdate"]?.str(),
                    thumb = o["image"]?.obj()?.get("medium")?.str()
                        ?: o["image"]?.obj()?.get("original")?.str(),
                    url = o["url"]?.str(),
                )
            }.filter { it.number != null }
        }.onFailure { AppLog.w("tvmaze", "Episode fetch failed for show $showId", it) }
            .getOrDefault(emptyList())

}

/** One TVmaze search candidate with its match evidence. */
private class TvmazeCandidate(
    val show: TvmazeShow,
    val score: Int,
    val yearDistance: Int?,
)

/**
 * Title matching for TVmaze show candidates.
 *
 * Containment alone is not proof: an AniList OVA/side-story title such as
 * "Elfen Lied: Tooriame nite …" contains the TV series name but is a different
 * work. Weak (prefix/containment) matches therefore only count when the
 * premiere year is exactly right; strong matches must be equal after
 * normalisation, with or without season markers.
 */
internal object Shows {
    private val nonWord = Regex("[^a-z0-9]+")

    fun normalize(s: String): String = s.lowercase().replace(nonWord, " ").trim()

    /** 4 exact, 3 equal after dropping season markers, 1 one name is a prefix of the other, 0 otherwise. */
    fun titleScore(candidate: String, wanted: String): Int {
        val a = normalize(candidate)
        val b = normalize(wanted)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 4
        val strippedA = normalize(dev.anime.util.stripSeasonMarkers(candidate))
        val strippedB = normalize(dev.anime.util.stripSeasonMarkers(wanted))
        if (strippedA.isNotEmpty() && strippedA == strippedB) return 3
        if (a.length < 4 || b.length < 4) return 0
        return if (a.startsWith("$b ") || b.startsWith("$a ")) 1 else 0
    }

    /** Weak matches need a year-exact corroboration; strong ones stand alone. */
    fun acceptable(score: Int, yearDistance: Int?): Boolean = when {
        score >= 3 -> true
        score == 1 -> yearDistance == 0
        else -> false
    }
}

data class TvmazeShow(
    val id: String,
    val name: String,
    val year: Int? = null,
    val image: String? = null,
    val anime: Boolean = false,
)

@Serializable
data class TvmazeEpisode(
    val season: Int,
    val number: Int? = null,
    val title: String? = null,
    val airdate: String? = null,
    val thumb: String? = null,
    val url: String? = null,
)
