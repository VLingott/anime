package dev.anime.source.play

import dev.anime.model.DetailResult
import dev.anime.model.PlaySource
import dev.anime.source.SourceContext
import dev.anime.store.Jsonx
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class AniWorldProvider : PlaybackProvider {
    override val id = "aniworld"
    override val name = "AniWorld"
    override val priority = 10

    private val slugCache = ConcurrentHashMap<Long, String>()

    private val junk = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    private val jsonScript = Regex(
        """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val jsRedirect = Regex("""window\.location\.href\s*=\s*'([^']+)'""")
    private val legacyPayload = Regex("""var a168c='([^']+)'""")
    private val m3u8Pattern = Regex("""https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""")
    private val listItem = Regex("""<li[^>]*data-lang-key="(\d+)"[^>]*>""")
    private val linkTarget = Regex("""data-link-target="([^"]+)"""")

    override suspend fun resolve(
        detail: DetailResult,
        season: Int,
        episode: Int,
        ctx: SourceContext,
    ): List<PlaySource> {
        if (episode < 1) return emptyList()
        val slug = slugFor(detail, ctx) ?: return emptyList()
        val offset = titleSeason(detail) - 1
        val pageSeasons = listOf((season + offset).coerceAtLeast(1), season.coerceAtLeast(1)).distinct()

        var byLanguage = LinkedHashMap<Int, MutableList<String>>()
        for (pageSeason in pageSeasons) {
            val html = fetch("$BASE/anime/stream/$slug/staffel-$pageSeason/episode-$episode", ctx) ?: continue
            val parsed = parseLanguages(html)
            if (parsed.isNotEmpty()) {
                byLanguage = parsed
                break
            }
        }
        if (byLanguage.isEmpty()) return emptyList()

        for (language in LANGUAGE_ORDER) {
            val targets = byLanguage[language]?.distinct()?.take(4) ?: continue
            val sources = coroutineScope {
                targets.map { target ->
                    async(Dispatchers.IO) {
                        val embed = resolveRedirect(target, ctx) ?: return@async null
                        val direct = resolveEmbed(embed, ctx) ?: return@async null
                        val proxied = "/api/hls/stream.m3u8?u=" + URLEncoder.encode(direct, Charsets.UTF_8)
                        PlaySource(
                            id = id,
                            name = "$name ${languageLabel(language)}",
                            kind = "hls",
                            url = proxied,
                            languages = listOf(if (language == 1) "German" else "Japanese"),
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            if (sources.isNotEmpty()) return sources.take(3)
        }
        return emptyList()
    }

    private fun parseLanguages(html: String): LinkedHashMap<Int, MutableList<String>> {
        val byLanguage = LinkedHashMap<Int, MutableList<String>>()
        for (match in listItem.findAll(html)) {
            val tag = match.value
            val language = match.groupValues[1].toIntOrNull() ?: continue
            val target = linkTarget.find(tag)?.groupValues?.get(1) ?: continue
            byLanguage.getOrPut(language) { mutableListOf() }.add(target)
        }
        return byLanguage
    }

    private fun titleSeason(detail: DetailResult): Int {
        val titles = listOfNotNull(detail.title?.romaji, detail.title?.english)
        for (title in titles) {
            Regex("(?i)(\\d+)(st|nd|rd|th)\\s+season").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            Regex("(?i)season\\s+(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            Regex("(?i)part\\s+(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 1
    }

    private fun stripSeasonMarkers(title: String): String = title
        .replace(Regex("(?i)\\b(\\d+(st|nd|rd|th)\\s+season|season\\s+\\d+|part\\s+\\d+|cour\\s+\\d+)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private suspend fun slugFor(detail: DetailResult, ctx: SourceContext): String? {
        slugCache[detail.id]?.let { return it }
        var fallback: String? = null
        val baseTitles = (listOfNotNull(detail.title?.romaji, detail.title?.english, detail.title?.native) + detail.synonyms)
            .map { it.trim() }
            .filter { it.length >= 2 }
        val titles = (baseTitles.take(3) + baseTitles.map { stripSeasonMarkers(it) })
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
            .take(6)

        for (title in titles) {
            val text = runCatching {
                ctx.http.get("$BASE/ajax/seriesSearch?keyword=" + URLEncoder.encode(title, Charsets.UTF_8)) {
                    header(HttpHeaders.UserAgent, UA)
                    header(HttpHeaders.Accept, "application/json")
                }.bodyAsText()
            }.getOrNull() ?: continue

            val results = runCatching {
                Jsonx.parseToJsonElement(text).let { it as? JsonArray }.orEmpty().mapNotNull { el ->
                    val obj = el.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    name to link
                }
            }.getOrDefault(emptyList())
            if (results.isEmpty()) continue
            if (fallback == null) fallback = results.first().second

            val target = normalize(title)
            val match = results.firstOrNull { normalize(it.first) == target }
                ?: results.firstOrNull {
                    val candidate = normalize(it.first)
                    candidate.contains(target) || target.contains(candidate)
                }
            if (match != null) {
                slugCache[detail.id] = match.second
                return match.second
            }
        }
        return fallback?.also { slugCache[detail.id] = it }
    }

    private suspend fun resolveRedirect(target: String, ctx: SourceContext): String? = withContext(Dispatchers.IO) {
        var current = if (target.startsWith("http")) target else BASE + target
        runCatching {
            repeat(6) {
                val connection = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", UA)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = java.net.URL(java.net.URL(current), location).toString()
                } else {
                    return@runCatching current
                }
            }
            current
        }.getOrNull()
    }

    private suspend fun resolveEmbed(url: String, ctx: SourceContext): String? {
        var current = url
        var html = fetch(current, ctx) ?: return null
        var hops = 0
        while (hops < 4) {
            val next = jsRedirect.find(html)?.groupValues?.get(1)
            if (next == null || next == current) break
            current = next
            html = fetch(current, ctx) ?: return null
            hops++
        }
        decodeVoePage(html)?.let { return it }
        return m3u8Pattern.find(html)?.value
    }

    private fun decodeVoePage(html: String): String? {
        for (match in jsonScript.findAll(html)) {
            val element = runCatching { Json.parseToJsonElement(match.groupValues[1].trim()) }.getOrNull() ?: continue
            val encoded = when (element) {
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(element.contentOrNull)
                else -> emptyList()
            }
            for (candidate in encoded) {
                decodeVoe(candidate)?.let { return it }
            }
        }
        legacyPayload.find(html)?.groupValues?.get(1)?.let { encoded ->
            decodeVoe(encoded)?.let { return it }
        }
        return null
    }

    private fun decodeVoe(encoded: String): String? = runCatching {
        var text = rotateLetters(encoded)
        for (part in junk) text = text.replace(part, "_")
        text = text.replace("_", "")
        val first = String(base64(text), Charsets.UTF_8)
        val shifted = buildString(first.length) {
            for (ch in first) append((ch.code - 3).toChar())
        }
        val second = String(base64(shifted.reversed()), Charsets.UTF_8)
        val obj = Json.parseToJsonElement(second).jsonObject
        obj["source"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
    }.getOrNull()

    private fun base64(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            else -> value
        }
        return Base64.getDecoder().decode(padded)
    }

    private fun rotateLetters(value: String): String = buildString(value.length) {
        for (ch in value) {
            val code = ch.code
            append(
                when {
                    code in 65..90 -> ((code - 65 + 13) % 26 + 65).toChar()
                    code in 97..122 -> ((code - 97 + 13) % 26 + 97).toChar()
                    else -> ch
                },
            )
        }
    }

    private suspend fun fetch(url: String, ctx: SourceContext): String? = runCatching {
        ctx.http.get(url) {
            header(HttpHeaders.UserAgent, UA)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }.bodyAsText()
    }.getOrNull()

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun languageLabel(language: Int): String = when (language) {
        1 -> "German Dub"
        3 -> "German Sub"
        2 -> "English Sub"
        else -> "Stream"
    }

    private companion object {
        const val BASE = "https://aniworld.to"
        const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val LANGUAGE_ORDER = listOf(1, 3, 2)
    }
}
