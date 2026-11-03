package dev.anime.source

import dev.anime.cache.DiskCache
import dev.anime.model.DetailResult
import dev.anime.model.SubtitleTrack
import dev.anime.util.AppLog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder

/**
 * Online subtitle lookup for streams that carry no subtitle track.
 *
 * Uses the keyless OpenSubtitles v3 addon endpoint (the Stremio community
 * proxy) with an IMDb id resolved from the title through IMDb's suggestion
 * API. Movies use `/subtitles/movie/{imdb}.json`, episodic entries use
 * `/subtitles/series/{imdb}:{season}:{episode}.json`.
 */
class SubtitleService(cacheDir: File? = null) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val disk = cacheDir?.let { DiskCache(File(it, "subtitles-v1"), 4L * 1024 * 1024) }
    private val imdbLock = Mutex()

    @Serializable
    internal data class Suggestion(val id: String? = null, val l: String? = null, val y: Int? = null, val qid: String? = null)

    @Serializable
    private data class Suggestions(val d: List<Suggestion> = emptyList())

    @Serializable
    private data class OnlineSubtitle(
        val url: String? = null,
        val lang: String? = null,
        val subtitleFileName: String? = null,
        val movieReleaseName: String? = null,
    )

    @Serializable
    private data class OnlineResponse(val subtitles: List<OnlineSubtitle> = emptyList())

    /** Subtitle tracks for one episode, preferring [language]; empty when none exist. */
    suspend fun forEpisode(
        detail: DetailResult,
        season: Int,
        episode: Int,
        language: String,
        ctx: SourceContext,
        refresh: Boolean = false,
    ): List<SubtitleTrack> {
        val imdb = imdbId(detail, ctx) ?: return emptyList()
        val isMovie = detail.format.equals("MOVIE", ignoreCase = true)
        val endpoint = if (isMovie) {
            "$BASE/subtitles/movie/$imdb.json"
        } else {
            "$BASE/subtitles/series/$imdb:${season.coerceAtLeast(1)}:${episode.coerceAtLeast(1)}.json"
        }
        val cacheKey = "subs|" + endpoint + "|" + language.lowercase()
        if (!refresh) {
            disk?.read(cacheKey)?.let { text ->
                runCatching { json.decodeFromString<List<SubtitleTrack>>(text) }
                    .getOrNull()?.let { return it }
            }
        }

        val text = runCatching {
            ctx.http.get(endpoint) {
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "application/json")
            }.bodyAsText()
        }.onFailure { AppLog.w("subtitles", "Online subtitle lookup failed", it) }
            .getOrNull() ?: return emptyList()

        val parsed = runCatching { json.decodeFromString<OnlineResponse>(text) }
            .onFailure { AppLog.w("subtitles", "Invalid subtitle response", it) }
            .getOrNull() ?: return emptyList()

        val wanted = languageCode(language)
        val matching = parsed.subtitles.filter { it.url != null && it.lang == wanted }
        val pool = matching.ifEmpty {
            if (wanted != "eng") parsed.subtitles.filter { it.url != null && it.lang == "eng" } else emptyList()
        }
        val tracks = pool.take(5).mapIndexed { index, sub ->
            SubtitleTrack(
                url = sub.url!!,
                label = languageName(sub.lang) + listOfNotNull(sub.movieReleaseName, sub.subtitleFileName?.substringBeforeLast('.'))
                    .firstOrNull { !it.isNullOrBlank() }?.let { " · " + it.take(40) }.orEmpty(),
                language = languageName(sub.lang),
                default = index == 0,
                mime = "application/x-subrip",
            )
        }
        runCatching { disk?.write(cacheKey, json.encodeToString(tracks)) }
        return tracks
    }

    /** Download and parse a subtitle file for overlay rendering. */
    suspend fun fetchCues(url: String, ctx: SourceContext, refresh: Boolean = false): List<dev.anime.model.SubtitleCue> {
        val cacheKey = "cue|" + url
        if (!refresh) {
            disk?.read(cacheKey)?.let { text ->
                runCatching { json.decodeFromString<List<dev.anime.model.SubtitleCue>>(text) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        val text = runCatching {
            ctx.http.get(url) {
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "*/*")
            }.bodyAsText()
        }.onFailure { AppLog.w("subtitles", "Subtitle file download failed", it) }
            .getOrNull() ?: return emptyList()

        val cues = SubtitleParser.parse(text)
        if (cues.isNotEmpty()) {
            runCatching { disk?.write(cacheKey, json.encodeToString(cues)) }
        }
        return cues
    }

    /** IMDb id for the AniList entry, resolved once through the suggestion API. */
    private suspend fun imdbId(detail: DetailResult, ctx: SourceContext): String? = imdbLock.withLock {
        val cacheKey = "imdb|" + detail.id
        disk?.read(cacheKey)?.takeIf { it.isNotBlank() }?.let { return@withLock it }

        val wanted = listOfNotNull(detail.title?.english, detail.title?.romaji)
            .filter { it.isNotBlank() }
        for (title in wanted) {
            val query = URLEncoder.encode(dev.anime.util.stripSeasonMarkers(title).take(80), Charsets.UTF_8)
            val text = runCatching {
                ctx.http.get("https://v3.sg.media-imdb.com/suggestion/x/$query.json") {
                    header(HttpHeaders.Accept, "application/json")
                }.bodyAsText()
            }.onFailure { AppLog.w("subtitles", "IMDb lookup failed for \"$title\"", it) }
                .getOrNull() ?: continue
            val suggestions = runCatching { json.decodeFromString<Suggestions>(text) }.getOrNull()?.d.orEmpty()
            val best = suggestions
                .filter { it.id?.startsWith("tt") == true && it.qid in ID_TYPES }
                .mapNotNull { candidate -> score(candidate, detail)?.let { candidate to it } }
                .maxByOrNull { it.second }
                ?.first
                ?: continue
            val id = best.id ?: continue
            runCatching { disk?.write(cacheKey, id) }
            return@withLock id
        }
        null
    }

    /** Title/year/type similarity; null when it is clearly a different work. */
    internal fun score(candidate: Suggestion, detail: DetailResult): Int? {
        val names = listOfNotNull(detail.title?.english, detail.title?.romaji)
            .mapNotNull { normalize(it) }
        val got = normalize(candidate.l) ?: return null
        if (names.isEmpty()) return null
        val titleScore = when {
            names.any { it == got } -> 4
            names.any { got.length >= 6 && (got.contains(it) || it.contains(got)) } -> 2
            else -> 0
        }
        if (titleScore == 0) return null
        val wantedYear = detail.seasonYear
        val yearScore = when {
            wantedYear == null || candidate.y == null -> 1
            candidate.y == wantedYear -> 3
            kotlin.math.abs(candidate.y - wantedYear) <= 1 -> 2
            else -> 0
        }
        if (yearScore == 0) return null
        val typeScore = if (detail.format.equals("MOVIE", true)) {
            if (candidate.qid == "movie" || candidate.qid == "feature") 2 else 1
        } else {
            if (candidate.qid == "tvSeries" || candidate.qid == "tvMiniSeries") 2 else 1
        }
        return titleScore * 10 + yearScore * 3 + typeScore
    }

    private fun normalize(value: String?): String? = value
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.trim()
        ?.ifEmpty { null }

    internal companion object {
        private const val BASE = "https://opensubtitles-v3.strem.io"
        internal val ID_TYPES = setOf("movie", "feature", "tvSeries", "tvMiniSeries", "tvMovie")
        private val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun languageCode(language: String): String = when (language.lowercase()) {
            "german" -> "ger"
            "japanese" -> "jpn"
            "english" -> "eng"
            "spanish" -> "spa"
            "french" -> "fre"
            else -> "eng"
        }

        fun languageName(code: String?): String = when (code) {
            "eng" -> "English"
            "ger", "deu" -> "German"
            "jpn" -> "Japanese"
            "spa" -> "Spanish"
            "fre", "fra" -> "French"
            "por", "pob" -> "Portuguese"
            "rus" -> "Russian"
            "ita" -> "Italian"
            "zho", "chi" -> "Chinese"
            "zht" -> "Chinese (TW)"
            "kor" -> "Korean"
            "ara" -> "Arabic"
            "tur" -> "Turkish"
            "pol" -> "Polish"
            "nld", "dut" -> "Dutch"
            else -> code ?: "Subtitle"
        }
    }
}

/** SRT/WebVTT parsing for overlay rendering. */
object SubtitleParser {
    private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val tag = Regex("<[^>]+>")
    private val assTag = Regex("""\{\\[^}]*}""")

    fun parse(text: String): List<dev.anime.model.SubtitleCue> {
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n"))
        val cues = ArrayList<dev.anime.model.SubtitleCue>(blocks.size)
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeIndex < 0) continue
            val range = lines[timeIndex].split("-->", limit = 2)
            val start = parseTime(range.getOrNull(0)) ?: continue
            val end = parseTime(range.getOrNull(1)) ?: continue
            if (end <= start) continue
            val body = lines.drop(timeIndex + 1)
                .joinToString("\n")
                .let { tag.replace(it, "") }
                .let { assTag.replace(it, "") }
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim()
            if (body.isEmpty()) continue
            cues += dev.anime.model.SubtitleCue(start, end, body.take(600))
            if (cues.size >= 8_000) break
        }
        return cues
    }

    private fun parseTime(raw: String?): Long? {
        val value = raw?.trim()?.substringBefore(' ')?.trim() ?: return null
        val m = time.find(value) ?: return null
        val hours = m.groupValues[1].toLongOrNull() ?: 0L
        val minutes = m.groupValues[2].toLongOrNull() ?: return null
        val seconds = m.groupValues[3].toLongOrNull() ?: return null
        val millis = m.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }
}
