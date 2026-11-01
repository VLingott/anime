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

        fun rank(list: List<TvmazeShow>) = list.sortedBy { s ->
            s.year?.let { y -> if (seasonYear == null) 0 else abs(y - seasonYear) } ?: 9_999
        }

        for ((q, shows) in perName) {
            val anime = shows.filter { it.anime }
            if (anime.isEmpty()) continue
            val target = norm(q)
            val matched = anime.filter {
                val n = norm(it.name)
                n.isNotEmpty() && (n == target || n.contains(target) || target.contains(n))
            }
            (rank(matched).ifEmpty { rank(anime) }).firstOrNull()?.let { return it }
        }

        for ((q, shows) in perName) {
            val target = norm(q)
            shows.firstOrNull { norm(it.name) == target && target.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private suspend fun searchShows(query: String, ctx: SourceContext): List<TvmazeShow> {
        val res = ctx.http.get(
            "https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(query.take(80), Charsets.UTF_8),
        )
        if (res.status.value != 200) return emptyList()
        val body = runCatching { Json.parseToJsonElement(res.bodyAsText()).arr() }.getOrNull() ?: return emptyList()
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
        }.getOrDefault(emptyList())

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
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
