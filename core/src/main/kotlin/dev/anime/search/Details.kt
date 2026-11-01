package dev.anime.search

import dev.anime.model.DetailCharacter
import dev.anime.model.DetailResult
import dev.anime.model.DetailTag
import dev.anime.model.DetailTitle
import dev.anime.source.SourceContext
import dev.anime.store.Jsonx
import dev.anime.store.arr
import dev.anime.store.dbl
import dev.anime.store.int
import dev.anime.store.obj
import dev.anime.store.str
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.Collections

class Details(private val ctx: SourceContext) {
    private val hot = Collections.synchronizedMap(
        object : LinkedHashMap<String, DetailResult>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DetailResult>?) = size > 256
        },
    )

    suspend fun byId(rawId: String): DetailResult? {
        val id = rawId.trim().toIntOrNull() ?: return null
        hot[id.toString()]?.let { return it }

        val gql = """
            query (${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) {
              id title { romaji english native } synonyms description(asHtml: false)
              genres tags { name rank } format status season seasonYear episodes duration
              averageScore popularity source startDate { year month day } endDate { year month day }
              coverImage { extraLarge large color } bannerImage
              studios(isMain: true) { nodes { name } }
              siteUrl trailer { site id }
              characters(perPage: 8, sort: [ROLE, RELEVANCE]) { edges { role node { id name { full } image { large } } } }
            } }
        """.trimIndent()
        val body = """{"query":${Jsonx.encodeToString(gql)},"variables":{"id":$id}}"""

        val result = try {
            val text = ctx.http.post("https://graphql.anilist.co") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()

            val media = Jsonx.parseToJsonElement(text).obj()?.get("data")?.obj()?.get("Media")?.obj()
                ?: return null
            val t = media["title"]?.obj()
            val cover = media["coverImage"]?.obj()

            DetailResult(
                id = id.toLong(),
                title = DetailTitle(
                    romaji = t?.get("romaji")?.str(),
                    english = t?.get("english")?.str(),
                    native = t?.get("native")?.str(),
                ),
                synonyms = media["synonyms"]?.arr()?.mapNotNull { it.str() }.orEmpty(),
                description = media["description"]?.str()?.let { cleanDescription(it) },
                coverImage = cover?.get("extraLarge")?.str() ?: cover?.get("large")?.str(),
                coverColor = cover?.get("color")?.str()?.let(::normalizeColor),
                bannerImage = media["bannerImage"]?.str(),
                genres = media["genres"]?.arr()?.mapNotNull { it.str() }.orEmpty(),
                tags = media["tags"]?.arr().orEmpty().mapNotNull { el ->
                    el.obj()?.let { o -> DetailTag(name = o["name"]?.str() ?: return@mapNotNull null, rank = o["rank"]?.int() ?: 0) }
                },
                format = media["format"]?.str(),
                status = media["status"]?.str(),
                season = media["season"]?.str(),
                seasonYear = media["seasonYear"]?.int(),
                episodes = media["episodes"]?.int(),
                duration = media["duration"]?.int(),
                source = media["source"]?.str(),
                averageScore = media["averageScore"]?.dbl(),
                popularity = media["popularity"]?.str()?.toLongOrNull(),
                studios = media["studios"]?.obj()?.get("nodes")?.arr().orEmpty().mapNotNull { it.obj()?.get("name")?.str() },
                startDate = media["startDate"]?.obj()?.let(::dateOf),
                endDate = media["endDate"]?.obj()?.let(::dateOf),
                siteUrl = media["siteUrl"]?.str(),
                trailer = media["trailer"]?.obj()?.let { tr ->
                    val site = tr.get("site")?.str()
                    val vid = tr.get("id")?.str()
                    when {
                        site == "youtube" && vid != null -> "https://www.youtube.com/watch?v=$vid"
                        site == "dailymotion" && vid != null -> "https://www.dailymotion.com/video/$vid"
                        else -> null
                    }
                },
                characters = media["characters"]?.obj()?.get("edges")?.arr().orEmpty().mapNotNull { edge ->
                    val o = edge.obj() ?: return@mapNotNull null
                    val node = o["node"]?.obj() ?: return@mapNotNull null
                    DetailCharacter(
                        id = node["id"]?.str()?.toLongOrNull(),
                        name = node["name"]?.obj()?.get("full")?.str() ?: return@mapNotNull null,
                        role = o["role"]?.str(),
                        image = node["image"]?.obj()?.get("large")?.str(),
                    )
                },
            )
        } catch (_: Exception) {
            null
        }

        if (result != null) hot[id.toString()] = result
        return result
    }

    private fun cleanDescription(d: String): String =
        d.replace(Regex("<br\\s*/?>\\n?\\s*"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+\\n\\s+"), "\n\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun normalizeColor(c: String): String? =
        if (Regex("^#?[0-9a-fA-F]{6}$").matches(c)) c.removePrefix("#").let { "#$it" } else null

    private fun dateOf(o: kotlinx.serialization.json.JsonObject): String? {
        val y = o["year"]?.str()?.toIntOrNull() ?: return null
        val m = o["month"]?.str()?.toIntOrNull() ?: return "$y"
        val d = o["day"]?.str()?.toIntOrNull() ?: return "$y-${m.toString().padStart(2, '0')}"
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }
}
