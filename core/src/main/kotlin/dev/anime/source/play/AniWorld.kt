package dev.anime.source.play

import dev.anime.model.DetailResult
import dev.anime.cache.DiskCache
import dev.anime.model.PlaySource
import dev.anime.source.SourceContext
import dev.anime.store.Jsonx
import dev.anime.util.AppLog
import dev.anime.util.stripSeasonMarkers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class AniWorldProvider(cacheDir: File? = null) : PlaybackProvider {
    override val id = "aniworld"
    override val name = "AniWorld"
    override val priority = 10

    private val slugCache = ConcurrentHashMap<Long, String>()
    private val structureCache = ConcurrentHashMap<String, List<SeasonCount>>()
    private val structureDisk = cacheDir?.let { DiskCache(File(it, "aniworld-structure"), 1L * 1024 * 1024) }
    private val structureJson = Json { ignoreUnknownKeys = true }

    private val jsRedirect = Regex("""window\.location\.href\s*=\s*'([^']+)'""")

    override suspend fun resolve(
        detail: DetailResult,
        season: Int,
        episode: Int,
        ctx: SourceContext,
        absoluteEpisode: Int?,
        episodeTitle: String?,
    ): List<PlaySource> {
        if (episode < 1) return emptyList()
        val slug = slugFor(detail, ctx) ?: return emptyList()
        val offset = titleSeason(detail) - 1
        val pageSeasons = linkedSetOf<Int>().apply {
            add((season + offset).coerceAtLeast(1))
            add(season.coerceAtLeast(1))
        }.toList()

        var byLanguage = LinkedHashMap<Int, List<String>>()
        var matchedHtml: String? = null
        for (pageSeason in pageSeasons) {
            val html = fetch("$BASE/anime/stream/$slug/staffel-$pageSeason/episode-$episode", ctx) ?: continue
            val parsed = AniWorldParser.parseLanguages(html)
            if (parsed.isNotEmpty()) {
                byLanguage = parsed
                matchedHtml = html
                break
            }
        }

        if (byLanguage.isEmpty() && absoluteEpisode != null) {
            val mapped = mapAbsoluteEpisode(slug, absoluteEpisode, ctx)
            if (mapped != null) {
                val html = fetch(
                    "$BASE/anime/stream/$slug/staffel-${mapped.first}/episode-${mapped.second}",
                    ctx,
                )
                if (html != null) {
                    val parsed = AniWorldParser.parseLanguages(html)
                    if (parsed.isNotEmpty()) {
                        byLanguage = parsed
                        matchedHtml = html
                    }
                }
            }
        }
        if (byLanguage.isEmpty()) return emptyList()

        val titles = matchedHtml?.let(AniWorldParser::episodeTitles) ?: AniWorldParser.EpisodeTitles(null, null)
        val episodeTitleGerman = titles.german
        val episodeTitleEnglish = titles.english

        for (language in LANGUAGE_ORDER) {
            val targets = byLanguage[language]?.take(4) ?: continue
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
                            episodeTitleGerman = episodeTitleGerman,
                            episodeTitleEnglish = episodeTitleEnglish,
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            if (sources.isNotEmpty()) return sources.take(3)
        }
        return emptyList()
    }

    @Serializable
    private data class SeasonCount(val season: Int, val count: Int)

    @Serializable
    private data class StructureEntry(val savedAt: Long, val seasons: List<SeasonCount>)

    private suspend fun mapAbsoluteEpisode(slug: String, absolute: Int, ctx: SourceContext): Pair<Int, Int>? {
        val structure = episodeStructure(slug, ctx)
        if (structure.isEmpty()) return null
        var remaining = absolute.coerceAtLeast(1)
        for (entry in structure) {
            if (remaining <= entry.count) return entry.season to remaining
            remaining -= entry.count
        }
        val last = structure.last()
        return last.season to last.count
    }

    private suspend fun episodeStructure(slug: String, ctx: SourceContext): List<SeasonCount> {
        structureCache[slug]?.let { return it }
        structureDisk?.read(slug)?.let { text ->
            val entry = runCatching { structureJson.decodeFromString<StructureEntry>(text) }
                .onFailure { AppLog.w("aniworld", "Ignoring unreadable season cache for $slug", it) }
                .getOrNull()
            if (entry != null && System.currentTimeMillis() - entry.savedAt < 24 * 60 * 60_000L) {
                structureCache[slug] = entry.seasons
                return entry.seasons
            }
        }
        val seriesHtml = fetch("$BASE/anime/stream/$slug", ctx) ?: return emptyList()
        val seasons = AniWorldParser.seasonNumbers(seriesHtml)
        if (seasons.isEmpty()) return emptyList()

        val gate = Semaphore(4)
        val counts = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val html = fetch("$BASE/anime/stream/$slug/staffel-$season", ctx) ?: return@withPermit null
                        val maxEpisode = AniWorldParser.episodeNumbers(html, season).maxOrNull()
                        if (maxEpisode == null) null else SeasonCount(season, maxEpisode)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        structureCache[slug] = counts
        runCatching {
            structureDisk?.write(slug, structureJson.encodeToString(StructureEntry(System.currentTimeMillis(), counts)))
        }.onFailure { AppLog.w("aniworld", "Could not persist season structure for $slug", it) }
        return counts
    }

    private fun titleSeason(detail: DetailResult): Int {
        for (title in listOfNotNull(detail.title?.romaji, detail.title?.english)) {
            val season = AniWorldParser.titleSeason(title)
            if (season != 1) return season
        }
        return 1
    }

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
            }.onFailure { AppLog.w("aniworld", "Series search failed for \"$title\"", it) }
                .getOrNull() ?: continue

            val results = runCatching {
                Jsonx.parseToJsonElement(text).let { it as? JsonArray }.orEmpty().mapNotNull { el ->
                    val obj = el.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    name to link
                }
            }.onFailure { AppLog.w("aniworld", "Invalid series search response for \"$title\"", it) }
                .getOrDefault(emptyList())
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
        }.onFailure { AppLog.w("aniworld", "Redirect resolution failed for $target", it) }
            .getOrNull()
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
        VoeDecoder.decodePage(html)?.let { return it }
        return VoeDecoder.findM3u8(html)
    }

    private suspend fun fetch(url: String, ctx: SourceContext): String? = runCatching {
        ctx.http.get(url) {
            header(HttpHeaders.UserAgent, UA)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }.bodyAsText()
    }.onFailure { AppLog.w("aniworld", "Page fetch failed: ${url.removePrefix(BASE)}", it) }
        .getOrNull()

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
