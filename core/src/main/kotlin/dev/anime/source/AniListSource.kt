package dev.anime.source

import dev.anime.cache.ByteLruCache
import dev.anime.cache.DiskCache
import dev.anime.model.SearchHit
import dev.anime.util.AppLog
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@Serializable
data class AniListTag(
    val name: String,
    val adult: Boolean = false,
)

data class AniListFilter(
    val search: String = "",
    val includeAdult: Boolean = false,
    val genres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val minScore: Int? = null,
    val formats: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val season: String? = null,
    val seasonYear: Int? = null,
    val sort: String = "SCORE_DESC",
    val page: Int = 1,
    val perPage: Int = 30,
)

class AniListSource(cacheDir: java.io.File? = null) : SourceAdapter {
    private val disk = cacheDir?.let { DiskCache(File(it, "search"), 32L * 1024 * 1024) }
    private val tagDisk = cacheDir?.let { DiskCache(File(it, "tags-v2"), 2L * 1024 * 1024) }
    private val genreDisk = cacheDir?.let { DiskCache(File(it, "genres-v1"), 256L * 1024) }
    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val tagLock = Mutex()
    @Volatile private var tagCache: List<AniListTag>? = null
    private val genreLock = Mutex()
    @Volatile private var genreCache: List<String>? = null

    private val searchCache = ByteLruCache<String, List<SearchHit>>(32L * 1024 * 1024) { hits ->
        var bytes = 256L
        hits.forEach { hit ->
            bytes += 320L + hit.title.length + (hit.titleEn?.length ?: 0) +
                (hit.snippet?.length ?: 0) + (hit.image?.length ?: 0) +
                hit.genres.sumOf { it.length + 12 } + hit.tags.sumOf { it.length + 12 }
        }
        bytes
    }

    override val id = "anilist"
    override val name = "AniList"
    override val baseUrl = "https://anilist.co"
    override val kind = "metadata"

    private val gql = """
        query (${'$'}search: String, ${'$'}includeAdult: Boolean, ${'$'}genres: [String], ${'$'}excludedGenres: [String],
               ${'$'}tags: [String], ${'$'}excludedTags: [String], ${'$'}minScore: Int,
               ${'$'}formats: [MediaFormat], ${'$'}statuses: [MediaStatus], ${'$'}season: MediaSeason,
               ${'$'}seasonYear: Int, ${'$'}sort: [MediaSort], ${'$'}page: Int, ${'$'}perPage: Int) {
          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(search: ${'$'}search, type: ANIME, genre_in: ${'$'}genres, genre_not_in: ${'$'}excludedGenres,
                  tag_in: ${'$'}tags, tag_not_in: ${'$'}excludedTags,
                  averageScore_greater: ${'$'}minScore, format_in: ${'$'}formats, status_in: ${'$'}statuses,
                  season: ${'$'}season, seasonYear: ${'$'}seasonYear, sort: ${'$'}sort, isAdult: ${'$'}includeAdult) {
              id
              title { romaji english native }
              synonyms
              startDate { year }
              episodes
              format
              season
              studios(isMain: true) { nodes { name } }
              averageScore
              siteUrl
              coverImage { large }
              genres
              tags { name rank }
              description(asHtml: false)
            }
          }
        }
    """.trimIndent()

    override suspend fun search(query: String, ctx: SourceContext): List<SearchHit> =
        search(AniListFilter(search = query), ctx)

    suspend fun search(filter: AniListFilter, ctx: SourceContext, refresh: Boolean = false): List<SearchHit> {
        val cacheKey = if (filter.page == 1) filterKey(filter) else null
        if (cacheKey != null && !refresh) {
            searchCache.get(cacheKey)?.let { return it }
            disk?.read(cacheKey)?.let { text ->
                runCatching {
                    cacheJson.decodeFromString<List<SearchHit>>(text)
                }.getOrNull()?.let { cached ->
                    searchCache.put(cacheKey, cached)
                    return cached
                }
            }
        }

        val variables = buildJsonObject {
            if (filter.search.isNotBlank()) put("search", filter.search)
            put("includeAdult", filter.includeAdult)
            if (filter.genres.isNotEmpty()) put("genres", buildJsonArray { filter.genres.forEach { add(it) } })
            if (filter.excludedGenres.isNotEmpty()) {
                put("excludedGenres", buildJsonArray { filter.excludedGenres.forEach { add(it) } })
            }
            if (filter.tags.isNotEmpty()) put("tags", buildJsonArray { filter.tags.forEach { add(it) } })
            if (filter.excludedTags.isNotEmpty()) {
                put("excludedTags", buildJsonArray { filter.excludedTags.forEach { add(it) } })
            }
            filter.minScore?.let { put("minScore", it) }
            if (filter.formats.isNotEmpty()) put("formats", buildJsonArray { filter.formats.forEach { add(it) } })
            if (filter.statuses.isNotEmpty()) put("statuses", buildJsonArray { filter.statuses.forEach { add(it) } })
            filter.season?.let { put("season", it) }
            filter.seasonYear?.let { put("seasonYear", it) }
            put("sort", buildJsonArray { add(filter.sort) })
            put("page", filter.page.coerceAtLeast(1))
            put("perPage", filter.perPage.coerceIn(1, 50))
        }

        val body = buildJsonObject {
            put("query", gql)
            put("variables", variables)
        }.toString()

        val res = ctx.http.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (res.status.value != 200) return emptyList()

        val root = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        val media = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray ?: return emptyList()

        val hits = media.mapNotNull { el ->
            val o = el.jsonObject
            val t = o["title"]?.jsonObject
            val title = t?.get("romaji")?.jsonPrimitive?.contentOrNullSafe()
                ?: t?.get("english")?.jsonPrimitive?.contentOrNullSafe()
                ?: return@mapNotNull null
            SearchHit(
                sourceId = id,
                sourceName = name,
                title = title,
                titleEn = t?.get("english")?.jsonPrimitive?.contentOrNullSafe(),
                titleNative = t?.get("native")?.jsonPrimitive?.contentOrNullSafe(),
                year = o["startDate"]?.jsonObject?.get("year")?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull(),
                episodes = o["episodes"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull(),
                format = o["format"]?.jsonPrimitive?.contentOrNullSafe(),
                score = o["averageScore"]?.jsonPrimitive?.contentOrNullSafe()?.toDoubleOrNull(),
                url = o["siteUrl"]?.jsonPrimitive?.contentOrNullSafe() ?: baseUrl,
                image = o["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNullSafe(),
                externalId = o["id"]?.jsonPrimitive?.contentOrNullSafe(),
                synonyms = o["synonyms"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() } ?: emptyList(),
                snippet = o["description"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.replace(Regex("\\s+"), " ")
                    ?.let { d ->
                        if (d.length <= 600) d else d.take(600).trimEnd()
                            .trimEnd { ch -> ch in ".,;:!?…·-–—\"'" } + "…"
                    },
                season = o["season"]?.jsonPrimitive?.contentOrNullSafe(),
                studio = o["studios"]?.jsonObject?.get("nodes")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe(),
                genres = o["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }.orEmpty(),
                tags = o["tags"]?.jsonArray.orEmpty()
                    .mapNotNull { el -> el.jsonObject.let { tg ->
                        val name = tg["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@let null
                        val rank = tg["rank"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
                        if (rank > 50) name else null
                    } }
                    .take(8),
            )
        }
        val visible = filterAdultHits(hits, filter.includeAdult)
        if (cacheKey != null) {
            searchCache.put(cacheKey, visible)
            disk?.write(cacheKey, cacheJson.encodeToString(visible))
        }
        return visible
    }

    /** Drop explicit entries even when the API flags disagree, unless adult is allowed. */
    internal fun filterAdultHits(hits: List<SearchHit>, includeAdult: Boolean): List<SearchHit> =
        if (includeAdult) hits else hits.filter { hit ->
            hit.genres.none { it.equals("Hentai", ignoreCase = true) }
        }

    suspend fun allTags(ctx: SourceContext): List<AniListTag> = tagLock.withLock {
        tagCache?.let { return@withLock it }
        val cached = tagDisk?.read("all")?.let { text ->
            runCatching { cacheJson.decodeFromString<List<AniListTag>>(text) }
                .onFailure { AppLog.w("anilist", "Ignoring unreadable tag cache", it) }
                .getOrNull()
        }
        if (!cached.isNullOrEmpty()) {
            tagCache = cached
            return@withLock cached
        }

        val tags = runCatching {
            val res = ctx.http.post("https://graphql.anilist.co") {
                contentType(ContentType.Application.Json)
                setBody("""{"query":"query { MediaTagCollection { name isAdult } }"}""")
            }
            if (res.status.value != 200) error("AniList tags returned HTTP ${res.status.value}")
            val root = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            root["data"]?.jsonObject?.get("MediaTagCollection")?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val tag = element.jsonObject
                    val name = tag["name"]?.jsonPrimitive?.contentOrNullSafe()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    AniListTag(name, adult = tag["isAdult"]?.jsonPrimitive?.contentOrNullSafe() == "true")
                }
                .distinctBy { it.name }
                .sortedBy { it.name.lowercase() }
        }.onFailure { AppLog.w("anilist", "Tag catalog load failed", it) }.getOrDefault(emptyList())

        if (tags.isNotEmpty()) {
            tagCache = tags
            runCatching { tagDisk?.write("all", cacheJson.encodeToString(tags)) }
                .onFailure { AppLog.w("anilist", "Could not persist tag catalog", it) }
        }
        tags
    }

    fun peekPage1(filter: AniListFilter): List<SearchHit>? {
        val key = filterKey(filter.copy(page = 1))
        searchCache.get(key)?.let { return it }
        val text = disk?.read(key) ?: return null
        val decoded = runCatching { cacheJson.decodeFromString<List<SearchHit>>(text) }.getOrNull() ?: return null
        searchCache.put(key, decoded)
        return decoded
    }

    private fun filterKey(filter: AniListFilter): String = buildString {
        append(filter.search.trim().lowercase())
        append('|').append(filter.includeAdult)
        append('|').append(filter.genres.sorted().joinToString(","))
        append('|').append(filter.excludedGenres.sorted().joinToString(","))
        append('|').append(filter.tags.sorted().joinToString(","))
        append('|').append(filter.excludedTags.sorted().joinToString(","))
        append('|').append(filter.minScore ?: -1)
        append('|').append(filter.formats.sorted().joinToString(","))
        append('|').append(filter.statuses.sorted().joinToString(","))
        append('|').append(filter.season ?: "")
        append('|').append(filter.seasonYear ?: -1)
        append('|').append(filter.sort)
        append('|').append(filter.perPage)
    }

    override suspend fun probe(ctx: SourceContext): Boolean = search(AniListFilter(search = "frieren"), ctx).isNotEmpty()

    /** Live genre catalog; falls back to [GENRES] when the query fails. */
    suspend fun allGenres(ctx: SourceContext, refresh: Boolean = false): List<String> = genreLock.withLock {
        if (!refresh) {
            genreCache?.let { return@withLock it }
            genreDisk?.read("genres")?.let { text ->
                runCatching { cacheJson.decodeFromString<List<String>>(text) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                        genreCache = it
                        return@withLock it
                    }
            }
        }
        val live = runCatching {
            val text = ctx.http.post("https://graphql.anilist.co") {
                contentType(ContentType.Application.Json)
                setBody("""{"query":"query { GenreCollection }"}""")
            }.bodyAsText()
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
                ?.get("GenreCollection")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                ?.takeIf { it.isNotEmpty() }
        }.onFailure { AppLog.w("anilist", "Genre collection fetch failed", it) }
            .getOrNull()

        val genres = live ?: GENRES
        genreCache = genres
        runCatching { genreDisk?.write("genres", cacheJson.encodeToString(genres)) }
            .onFailure { AppLog.w("anilist", "Could not persist genre collection", it) }
        genres
    }

    companion object {
        /** AniList's genre taxonomy; the live list is fetched (GenreCollection) with this fallback. */
        val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Hentai", "Mahou Shoujo",
            "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life",
            "Sports", "Supernatural", "Thriller",
        )
        const val ADULT_GENRE = "Hentai"
        val FORMATS = listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
        val STATUSES = listOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "CANCELLED", "HIATUS")
        val SEASONS = listOf("WINTER", "SPRING", "SUMMER", "FALL")
        val SORTS = listOf(
            "SCORE_DESC" to "Rating",
            "POPULARITY_DESC" to "Popularity",
            "TRENDING_DESC" to "Trending",
            "START_DATE_DESC" to "Newest",
            "TITLE_ROMAJI" to "Title",
            "FAVOURITES_DESC" to "Favourites",
        )
    }
}

internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this.isString) this.content else this.content.takeIf { it != "null" }
