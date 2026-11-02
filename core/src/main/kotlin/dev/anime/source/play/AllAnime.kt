package dev.anime.source.play

import dev.anime.model.DetailResult
import dev.anime.model.PlaySource
import dev.anime.source.SourceContext
import dev.anime.util.AppLog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import dev.anime.store.obj

/**
 * AllAnime playback provider (api.mkissa.net GraphQL + allanime.day embeds).
 *
 * The API requires a rotating signature stack:
 *  1. `x-aa-boot`: hex(HMAC(SHA-256(HMAC(mask, label+buildId), sig template)))
 *  2. bootstrap GET returns `{epoch, partB}`; AES key = base64(partB) XOR mask
 *  3. `aaReq`: AES-256-GCM `{v,ts,epoch,buildId,qh,k}` with the nonce seed
 *     `SHA-256(seed template)[..12]`; ts floored to a 5-minute bucket
 *  4. episode query is Apollo persisted; hash = sha256(query text)
 *  5. `sourceUrls` may arrive AES-256-CTR-encrypted (`tobeparsed`, IV||2)
 *  6. obfuscated ids start with `--` (hex, XOR 0x38) and resolve to
 *     `/apivtwo/clock.json` HLS link lists.
 *
 * Volatile values (build id, mask, boot label, sig templates, hosts) live in a
 * remote JSON config with baked-in fallbacks; a failed resolve refreshes the
 * config once and retries, so published rotations self-heal without an update.
 */
class AllAnimeProvider(cacheDir: java.io.File? = null) : PlaybackProvider {
    override val id = "allanime"
    override val name = "AllAnime"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val configFile = cacheDir?.let { java.io.File(it, "allanime-config.json") }

    @Volatile private var config: Config = Config()
    @Volatile private var configRefreshedAt = 0L
    private val configLock = kotlinx.coroutines.sync.Mutex()

    /** Consecutive failed resolves; drives exponential backoff. */
    @Volatile private var failures = 0
    @Volatile private var retryAt = 0L
    @Volatile private var rateLimitedUntil = 0L
    @Volatile private var lastApiError: String? = null

    init {
        configFile?.takeIf { it.isFile }?.let { file ->
            runCatching {
                val stored = json.decodeFromString<Config>(file.readText())
                if (stored.buildId != Cfg.BUILD_ID || stored.qdMaskHex != Cfg.QD_MASK_HEX) config = stored
            }.onFailure { AppLog.w("allanime", "Ignoring unreadable config cache", it) }
        }
    }

    @Serializable
    data class Config(
        @SerialName("build_id") val buildId: String = Cfg.BUILD_ID,
        @SerialName("api_url") val apiUrl: String = Cfg.API_URL,
        @SerialName("bootstrap_url") val bootstrapUrl: String = Cfg.BOOTSTRAP_URL,
        @SerialName("referer") val referer: String = Cfg.REFERER,
        @SerialName("referer_host") val refererHost: String = Cfg.REFERER_HOST,
        @SerialName("base_host") val baseHost: String = Cfg.BASE_HOST,
        @SerialName("key_group") val keyGroup: String = Cfg.KEY_GROUP,
        @SerialName("episode_lane") val episodeLane: String = Cfg.EPISODE_LANE,
        @SerialName("qd_mask_hex") val qdMaskHex: String = Cfg.QD_MASK_HEX,
        @SerialName("episode_query") val episodeQuery: String = Cfg.EPISODE_QUERY,
        @SerialName("epoch_bucket_ms") val epochBucketMs: Long = Cfg.EPOCH_BUCKET_MS,
        @SerialName("boot_label") val bootLabel: String = Cfg.BOOT_LABEL,
        @SerialName("boot_sig_template") val bootSigTemplate: String = Cfg.BOOT_SIG_TEMPLATE,
        @SerialName("aa_req_seed_template") val aaReqSeedTemplate: String = Cfg.AA_REQ_SEED_TEMPLATE,
    )

    private suspend fun refreshConfig(ctx: SourceContext, throttle: Boolean) {
        if (throttle && System.currentTimeMillis() - configRefreshedAt < Cfg.REMOTE_CONFIG_RETRY_MS) return
        configLock.withLock {
            val age = System.currentTimeMillis() - configRefreshedAt
            if (throttle && age < Cfg.REMOTE_CONFIG_RETRY_MS) return@withLock
            configRefreshedAt = System.currentTimeMillis()

            val sources = System.getenv("ALLANIME_CONFIG_URL")?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                ?: Cfg.REMOTE_CONFIG_URLS
            for (url in sources) {
                val text = runCatching {
                    ctx.http.get(url) {
                        header(HttpHeaders.Accept, "application/json")
                    }.bodyAsText()
                }.onFailure { AppLog.w("allanime", "Remote config fetch failed: $url", it) }
                    .getOrNull() ?: continue

                val parsed = runCatching { json.decodeFromString<Config>(text) }
                    .onFailure { AppLog.w("allanime", "Remote config invalid: $url", it) }
                    .getOrNull() ?: continue

                config = parsed
                runCatching { configFile?.writeText(text) }
                    .onFailure { AppLog.w("allanime", "Could not persist config", it) }
                return@withLock
            }
        }
    }

    override suspend fun resolve(
        detail: DetailResult,
        season: Int,
        episode: Int,
        ctx: SourceContext,
        absoluteEpisode: Int?,
        episodeTitle: String?,
    ): List<PlaySource> {
        if (episode < 1) return emptyList()
        val now = System.currentTimeMillis()
        if (now < retryAt || now < rateLimitedUntil) return emptyList()

        refreshConfig(ctx, throttle = true)

        val episodeString = absoluteEpisode?.toString() ?: episode.toString()
        val first = episodeSources(detail, episodeString, config, ctx)
        if (first.isNotEmpty()) {
            failures = 0
            retryAt = 0
            lastApiError = null
            return first
        }

        // A fresh rotation surfaces as "no sources": refresh the remote config
        // (throttle-free) and try the new constants once before giving up.
        refreshConfig(ctx, throttle = false)
        val retried = episodeSources(detail, episodeString, config, ctx)
        if (retried.isNotEmpty()) {
            failures = 0
            retryAt = 0
            lastApiError = null
            return retried
        }

        recordFailure(now)
        return emptyList()
    }

    private fun recordFailure(now: Long) {
        failures += 1
        val error = lastApiError.orEmpty()
        val rateLimited = error.contains("captcha", true) ||
            error.contains("too many requests", true) ||
            error.contains("rate", true)
        if (rateLimited) {
            // Respect the API's cooldown instead of retrying into a captcha wall.
            rateLimitedUntil = now + (parseRetryDelay(error) ?: Cfg.RATE_LIMIT_COOLDOWN_MS)
        }
        retryAt = now + backoffFor(failures)
        AppLog.w("allanime", "Resolve failed ($failures); retry in ${(retryAt - now) / 1000}s")
    }

    internal fun backoffFor(failures: Int): Long =
        (Cfg.BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 6)).coerceAtMost(Cfg.BACKOFF_MAX_MS)

    /** "please try again in 3 seconds" -> 3_000 ms (+ margin). */
    internal fun parseRetryDelay(message: String): Long? =
        Regex("try again in (\\d+) second", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { (it + 2) * 1_000 }

    private suspend fun episodeSources(
        detail: DetailResult,
        episodeString: String,
        cfg: Config,
        ctx: SourceContext,
    ): List<PlaySource> {
        val showId = showIdFor(detail, cfg, ctx) ?: return emptyList()
        val boot = bootstrap(cfg, ctx) ?: return emptyList()
        val key = Crypto.deriveKey(boot.partB, cfg.qdMaskHex) ?: return emptyList()
        val queryHash = Crypto.sha256Hex(cfg.episodeQuery)
        val aaReq = Crypto.signAaReq(
            key = key,
            seedTemplate = cfg.aaReqSeedTemplate,
            epoch = boot.epoch,
            buildId = cfg.buildId,
            queryHash = queryHash,
            lane = cfg.episodeLane,
        ) ?: return emptyList()

        val body = buildJsonObject {
            put("query", cfg.episodeQuery)
            putJsonObject("variables") {
                put("showId", showId)
                put("translationType", "sub")
                put("episodeString", episodeString)
            }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", queryHash)
                }
                put("k", cfg.episodeLane)
                put("aaReq", aaReq)
            }
        }
        val response = ctx.http.post(cfg.apiUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, Cfg.UA)
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.Origin, cfg.referer)
            header(HttpHeaders.Referrer, cfg.referer)
            header("x-build-id", cfg.buildId)
            setBody(json.encodeToString(body))
        }.bodyAsText().let { text -> text.takeIf { it.isNotBlank() } } ?: return emptyList()

        val refs = decodeRefs(response, key) ?: return emptyList()

        // Modern refs are host embeds; extract the ones this player can use.
        val links = mutableListOf<PlaySource>()
        for (ref in refs.orderForExtraction()) {
            when {
                ref.sourceUrl?.contains("ok.ru/") == true ->
                    links += okruSources(ref, cfg, ctx)
                Crypto.deobfuscateSourceUrl(ref.sourceUrl)?.startsWith("/") == true -> {
                    // Legacy clock.json refs; each link becomes one source.
                    for (link in clockLinks(cfg, ref, ctx).filter { !it.link.isNullOrBlank() && it.link.contains(".m3u8") }) {
                        val resolution = link.resolutionStr?.toIntOrNull()?.let { "${it}p" } ?: "auto"
                        links += PlaySource(
                            id = id,
                            name = "$name ${ref.name?.takeIf { it.isNotBlank() } ?: "Default"} $resolution",
                            kind = "hls",
                            url = "/api/hls/stream.m3u8?u=" + URLEncoder.encode(link.link, "utf-8"),
                            languages = listOf("Japanese"),
                        )
                    }
                }
            }
            if (links.size >= 6) break
        }
        return links.distinctBy { it.url }
    }

    /** Prefer extractable providers over web embed pages we cannot resolve. */
    private fun List<SourceRef>.orderForExtraction(): List<SourceRef> =
        sortedWith(
            compareBy { ref ->
                when {
                    ref.name.equals("Default", true) -> 0
                    ref.sourceUrl?.contains("ok.ru/") == true -> 1
                    ref.sourceUrl?.contains("clock.json") == true ||
                        ref.sourceUrl?.startsWith("--") == true -> 2
                    else -> 3
                }
            },
        )

    // ── Episode sources request + unwrap ─────────────────────────────────

    private suspend fun decodeRefs(body: String, key: ByteArray): List<SourceRef>? {
        val rootResult = runCatching { json.parseToJsonElement(body) }
        val root = rootResult.getOrNull()?.obj() ?: run {
            AppLog.w("allanime", "Sources response not JSON")
            return null
        }

        val wrapped = Crypto.findTobeparsed(root)?.let { tbp -> Crypto.decryptTobeparsed(tbp, key) }
        val text = wrapped ?: body
        val parsed = runCatching { json.decodeFromString<SourcesResponse>(text) }
            .onFailure { AppLog.w("allanime", "Sources payload unreadable (${it::class.simpleName})") }
            .getOrNull() ?: return null
        if (parsed.errors.isNotEmpty()) {
            val message = parsed.errors.joinToString { it.message ?: "?" }
            lastApiError = message
            AppLog.w("allanime", "Sources API errors: $message")
            return null
        }
        val episode = parsed.data?.episode ?: parsed.episode ?: return null
        return episode.sourceUrls?.filter { !it.sourceUrl.isNullOrBlank() }.orEmpty()
    }

    // ── Bootstrap ────────────────────────────────────────────────────────

    private data class Boot(val epoch: Long, val partB: String)

    @Volatile private var bootCache: CacheBoot? = null
    private data class CacheBoot(val boot: Boot, val at: Long)

    private suspend fun bootstrap(cfg: Config, ctx: SourceContext): Boot? {
        bootCache?.let { cached ->
            if (System.currentTimeMillis() - cached.at < Cfg.BOOT_TTL_MS) return cached.boot
        }
        for (epoch in epochCandidates(cfg.epochBucketMs, System.currentTimeMillis())) {
            val aaBoot = Crypto.signAaBoot(
                maskHex = cfg.qdMaskHex,
                label = cfg.bootLabel,
                sigTemplate = cfg.bootSigTemplate,
                buildId = cfg.buildId,
                keyGroup = cfg.keyGroup,
                refererHost = cfg.refererHost,
                epoch = epoch,
                lane = cfg.episodeLane,
            ) ?: continue

            val text = runCatching {
                ctx.http.get(cfg.bootstrapUrl + "?buildId=" + URLEncoder.encode(cfg.buildId, "utf-8") +
                    "&k=" + URLEncoder.encode(cfg.episodeLane, "utf-8")) {
                    header(HttpHeaders.UserAgent, Cfg.UA)
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.Origin, cfg.referer)
                    header(HttpHeaders.Referrer, cfg.referer)
                    header("x-build-id", cfg.buildId)
                    header("x-aa-boot", aaBoot)
                }.bodyAsText()
            }.onFailure { AppLog.w("allanime", "Bootstrap request failed at epoch $epoch", it) }
                .getOrNull() ?: continue

            val rootBoot = runCatching { json.parseToJsonElement(text) }.getOrNull()?.obj()
            val partB = (rootBoot?.get("partB") as? JsonPrimitive)?.content
                ?: continue
            val epochValue = (rootBoot?.get("epoch") as? JsonPrimitive)?.content?.toLongOrNull() ?: epoch
            val boot = Boot(epochValue, partB)
            bootCache = CacheBoot(boot, System.currentTimeMillis())
            return boot
        }
        AppLog.w("allanime", "Bootstrap handshake failed for all epoch candidates")
        return null
    }

    /** Current epoch bucket plus the previous one inside its first day (web client qS/zh). */
    internal fun epochCandidates(bucketMs: Long, nowMs: Long): List<Long> {
        val grace = 86_400_000L
        val current = nowMs / bucketMs
        val previous = if (current > 0 && nowMs - current * bucketMs < grace) current - 1L else current
        return if (previous == current) listOf(current) else listOf(previous, current)
    }

    // ── Show search / AniList mapping ────────────────────────────────────

    @Serializable
    data class ShowEdge(
        @SerialName("_id") val showId: String? = null,
        val name: String? = null,
        val englishName: String? = null,
        val aniListId: JsonPrimitive? = null,
    ) {
        val anilistIdValue: Long?
            get() = aniListId?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.toLongOrNull()
    }

    @Serializable
    data class ShowResponse(val data: Data? = null) {
        @Serializable
        data class Data(val shows: Shows? = null)

        @Serializable
        data class Shows(val edges: List<ShowEdge> = emptyList())
    }

    private suspend fun showIdFor(detail: DetailResult, cfg: Config, ctx: SourceContext): String? {
        val names = listOfNotNull(
            detail.title?.english?.takeIf { it.isNotBlank() },
            detail.title?.romaji?.takeIf { it.isNotBlank() },
            detail.title?.native?.takeIf { it.isNotBlank() },
        ).distinct()
        for (name in names) {
            val id = searchShow(name, detail, cfg, ctx)
            if (id != null) return id
        }
        AppLog.w("allanime", "No allanime show matched \"${detail.title?.english ?: detail.id}\"")
        return null
    }

    private suspend fun searchShow(
        title: String,
        detail: DetailResult,
        cfg: Config,
        ctx: SourceContext,
    ): String? {
        val body = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("query", title)
                    put("allowAdult", detail.adult)
                    put("allowUnknown", false)
                }
                put("limit", 12)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            }
            put("query", Cfg.SEARCH_QUERY)
        }
        val text = runCatching {
            ctx.http.post(cfg.apiUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, Cfg.UA)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Origin, cfg.referer)
                header(HttpHeaders.Referrer, cfg.referer)
                header("x-build-id", cfg.buildId)
                setBody(json.encodeToString(body))
            }.bodyAsText()
        }.onFailure { AppLog.w("allanime", "Search request failed for \"$title\"", it) }
            .getOrNull() ?: return null

        return runCatching {
            val edges = json.decodeFromString<ShowResponse>(text).data?.shows?.edges.orEmpty()
            var fallback: Pair<String, Int>? = null
            for (edge in edges) {
                val showId = edge.showId ?: continue
                if (edge.anilistIdValue == detail.id) return showId

                val base = Crypto.normalize(edge.name) ?: continue
                val english = Crypto.normalize(edge.englishName)
                val wanted = Crypto.normalize(title) ?: continue
                val score = when {
                    base == wanted -> 3
                    english != null && english == wanted -> 2
                    base.startsWith(wanted) -> 1
                    else -> 0
                }
                if (score > 0 && (fallback == null || fallback.second < score)) {
                    fallback = showId to score
                }
            }
            fallback?.first
        }.onFailure { AppLog.w("allanime", "Invalid search response for \"$title\"", it) }
            .getOrNull()
    }

    // ── Ok.ru embed extraction ───────────────────────────────────────────

    /**
     * Extract the HLS master from an ok.ru videoembed page. The page carries a
     * `data-options` attribute containing an escaped JSON blob whose
     * `ondemandHls` field is a multi-resolution master playlist.
     */
    private suspend fun okruSources(ref: SourceRef, cfg: Config, ctx: SourceContext): List<PlaySource> {
        val url = ref.sourceUrl ?: return emptyList()
        val html = runCatching {
            ctx.http.get(url) {
                header(HttpHeaders.UserAgent, Cfg.UA)
                header(HttpHeaders.Referrer, cfg.referer)
            }.bodyAsText()
        }.onFailure { AppLog.w("allanime", "Ok.ru page fetch failed", it) }
            .getOrNull() ?: return emptyList()

        val options = runCatching {
            org.jsoup.Jsoup.parse(html).selectFirst("div[data-options]")?.attr("data-options")
        }.getOrNull() ?: return emptyList()
        if (options.isNullOrBlank()) return emptyList()

        val playlistUrl = options.extractEscapedLink("ondemandHls")
        if (!playlistUrl.isNullOrBlank() && playlistUrl.startsWith("https://")) {
            return listOf(
                PlaySource(
                    id = id,
                    name = "$name Ok auto",
                    kind = "hls",
                    url = "/api/hls/stream.m3u8?u=" + URLEncoder.encode(playlistUrl, "utf-8"),
                    languages = listOf("Japanese"),
                ),
            )
        }

        // Fall back to the per-quality video entries baked into the blob.
        val perQuality = options.extractVideos()
        return perQuality
            .filter { it.second.startsWith("https://") && it.second.contains(".m3u8") }
            .take(4)
            .map { (quality, videoUrl) ->
                PlaySource(
                    id = id,
                    name = "$name Ok " + OkRu.fixQuality(quality),
                    kind = "hls",
                    url = "/api/hls/stream.m3u8?u=" + URLEncoder.encode(videoUrl, "utf-8"),
                    languages = listOf("Japanese"),
                )
            }.distinctBy { it.url }
    }

    private object OkRu {
        /** ok.ru quality names to resolution suffixes (yuzono okruextractor table). */
        fun fixQuality(quality: String): String = when (quality.trim().removePrefix("\\").lowercase()) {
            "ultra" -> "2160p"
            "quad" -> "1440p"
            "full" -> "1080p"
            "hd" -> "720p"
            "sd" -> "480p"
            "low" -> "360p"
            "lowest" -> "240p"
            "mobile" -> "144p"
            else -> quality.trim().removePrefix("\\")
        }
    }

    /** Pull an escaped link value like `"ondemandHls":"https://..."` out of data-options. */
    private fun String.extractEscapedLink(attr: String): String? =
        Regex("""$attr\\*"\s*:\s*\\?"([^\\"]+)""").find(this)?.groupValues?.get(1)
            ?.replace(Regex("\\\\+u0026"), "&")

    /** Per-quality entries from an escaped `videos` array in data-options. */
    private fun String.extractVideos(): List<Pair<String, String>> {
        val array = Regex("""videos\\*"\s*:\s*\\*\[(.*?)(?:\]|$)""", RegexOption.DOT_MATCHES_ALL)
            .find(this)?.groupValues?.get(1) ?: return emptyList()
        return array.split(Regex("""\{\s*\\*"name\\*"\s*:\s*\\*"""))
            .drop(1)
            .mapNotNull { data ->
                val quality = data.substringBefore('"').trim().replace("\\\\", "")
                val url = data.extractEscapedLink("url") ?: return@mapNotNull null
                quality to url
            }
    }

    @Serializable
    data class SourceRef(
        @SerialName("sourceUrl") val sourceUrl: String? = null,
        @SerialName("sourceName") val name: String? = null,
    )

    @Serializable
    data class EpisodeSources(val sourceUrls: List<SourceRef>? = null)

    @Serializable
    data class ApiError(val message: String? = null)

    @Serializable
    data class DataEpisode(val episode: EpisodeSources? = null)

    @Serializable
    data class SourcesResponse(
        val data: DataEpisode? = null,
        val episode: EpisodeSources? = null,
        val errors: List<ApiError> = emptyList(),
    )

    @Serializable
    data class ClockLink(
        val link: String? = null,
        @SerialName("resolutionStr") val resolutionStr: String? = null,
        val hls: Boolean? = null,
    )

    @Serializable
    data class ClockResponse(val links: List<ClockLink> = emptyList())

    // ── Clock embeds ─────────────────────────────────────────────────────

    private suspend fun clockLinks(cfg: Config, ref: SourceRef, ctx: SourceContext): List<ClockLink> {
        // Only embed refs resolve through clock.json; direct media hosts are
        // (deliberately) not supported here.
        val path = Crypto.deobfuscateSourceUrl(ref.sourceUrl ?: return emptyList())
            ?: return emptyList()
        if (!path.startsWith("/")) return emptyList()
        val url = "https://${cfg.baseHost}$path"
        val text = runCatching {
            ctx.http.get(url) {
                header(HttpHeaders.UserAgent, Cfg.UA)
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Origin, cfg.referer)
                header(HttpHeaders.Referrer, cfg.referer)
            }.bodyAsText()
        }.onFailure { AppLog.w("allanime", "Clock fetch failed for ${ref.name ?: "ref"}", it) }
            .getOrNull() ?: return emptyList()

        return runCatching { json.decodeFromString<ClockResponse>(text).links }
            .onFailure { AppLog.w("allanime", "Clock response invalid for $path", it) }
            .getOrDefault(emptyList())
    }

    internal object Cfg {
        /** Remote constant overrides: maintained upstream first, our own copy as fallback. */
        val REMOTE_CONFIG_URLS = listOf(
            "https://raw.githubusercontent.com/serplay/anidoku/master/allanime-config.json",
            "https://raw.githubusercontent.com/VLingott/anime/master/allanime-config.json",
        )
        const val REMOTE_CONFIG_RETRY_MS = 30_000L
        const val BOOT_TTL_MS = 30_000L
        const val RATE_LIMIT_COOLDOWN_MS = 3L * 60_000
        const val BACKOFF_BASE_MS = 30_000L
        const val BACKOFF_MAX_MS = 10L * 60_000

        const val BUILD_ID = "182"
        const val API_URL = "https://api.mkissa.net/api"
        const val BOOTSTRAP_URL = "https://api.mkissa.net/client-crypto/v1/bootstrap"
        const val REFERER = "https://mkissa.to"
        const val REFERER_HOST = "mkissa.to"
        const val BASE_HOST = "allanime.day"
        const val KEY_GROUP = "mkissa"
        const val EPISODE_LANE = "k7"
        const val QD_MASK_HEX =
            "3b39c164fc7fafeebc0d766575f8930b09f50354e71084906034f1947a6ad1c6"
        const val EPISODE_QUERY =
            "query(\$showId:String!,\$translationType:VaildTranslationTypeEnumType!,\$episodeString:String!){episode(showId:\$showId translationType:\$translationType episodeString:\$episodeString){sourceUrls show{_id}}}"
        const val EPOCH_BUCKET_MS = 604_800_000L
        const val BOOT_LABEL = "UCCk4NP:"
        const val BOOT_SIG_TEMPLATE = "{referer_host}.{build_id}.{key_group}.{epoch}.{lane}"
        const val AA_REQ_SEED_TEMPLATE = "{epoch}:{build_id}:{qh}:{ts}:{lane}"

        const val SEARCH_QUERY =
            "query( \$search: SearchInput \$limit: Int \$page: Int \$translationType: VaildTranslationTypeEnumType \$countryOrigin: VaildCountryOriginEnumType ) { shows( search: \$search limit: \$limit page: \$page translationType: \$translationType countryOrigin: \$countryOrigin ) { edges { _id name englishName aniListId thumbnail availableEpisodes __typename } }}"

        val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    /** Debofuscation, signing and key derivation, one place. */
    internal object Crypto {
        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun normalize(value: String?): String? = value?.let { s ->
            s.lowercase().replace(Regex("\\s+"), " ").trim().ifEmpty { null }
        }

        /** HMAC-SHA256, standard 64-byte block construction. */
        fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /** Per-epoch AES key: base64(partB) XOR mask. Returns null on bad input. */
        fun deriveKey(partBb64: String, maskHex: String): ByteArray? = runCatching {
            val partB = java.util.Base64.getDecoder().decode(partBb64.trim().let { v ->
                v.replace('-', '+').replace('_', '/') + "=".repeat((4 - v.length % 4) % 4)
            })
            val mask = maskHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (partB.size != 32 || mask.size != 32) null
            else ByteArray(32) { i -> (partB[i].toInt() xor mask[i].toInt()).toByte() }
        }.getOrNull()

        /** hex(HMAC(HMAC(mask, label + buildId), rendered sig template)). */
        fun signAaBoot(
            maskHex: String,
            label: String,
            sigTemplate: String,
            buildId: String,
            keyGroup: String,
            refererHost: String,
            epoch: Long,
            lane: String,
        ): String? = runCatching {
            val mask = maskHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val innerKey = hmacSha256(mask, (label + buildId).toByteArray(Charsets.UTF_8))
            val rendered = sigTemplate
                .replace("{build_id}", buildId)
                .replace("{key_group}", keyGroup)
                .replace("{referer_host}", refererHost)
                .replace("{epoch}", epoch.toString())
                .replace("{lane}", lane)
            val mac = hmacSha256(innerKey, rendered.toByteArray(Charsets.UTF_8))
            mac.joinToString("") { "%02x".format(it) }
        }.getOrNull()

        /** base64(0x01 || nonce(12) || AES-256-GCM(payload)); no AAD, tag embedded. */
        fun signAaReq(
            key: ByteArray,
            seedTemplate: String,
            epoch: Long,
            buildId: String,
            queryHash: String,
            lane: String,
            nowMs: Long = System.currentTimeMillis(),
        ): String? = runCatching {
            if (key.size != 32 || queryHash.length != 64) return null
            // ts floored to a 5-minute bucket so the token is stable in-window.
            val floored = nowMs / (5L * 60_000L) * (5L * 60_000L)
            val payload = """{"v":1,"ts":$floored,"epoch":$epoch,"buildId":"$buildId","qh":"$queryHash","k":"$lane"}"""
            val seed = seedTemplate
                .replace("{epoch}", epoch.toString())
                .replace("{build_id}", buildId)
                .replace("{qh}", queryHash)
                .replace("{ts}", floored.toString())
                .replace("{lane}", lane)
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            val nonce = digest.copyOf(12)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce),
            )
            val sealed = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            val envelope = ByteArray(1 + 12 + sealed.size).also { bytes ->
                bytes[0] = 0x01
                nonce.copyInto(bytes, 1)
                sealed.copyInto(bytes, 13)
            }
            java.util.Base64.getEncoder().encodeToString(envelope)
        }.getOrNull()

        /** AES-256-CTR `0x01 || iv(12) || ciphertext || tag(16)` with IV||0x2 counter. */
        fun decryptTobeparsed(token: String, key: ByteArray): String? = runCatching {
            val blob = java.util.Base64.getDecoder().decode(
                token.trim().replace('-', '+').replace('_', '/') +
                    "=".repeat((4 - token.trim().replace('-', '+').replace('_', '/').length % 4) % 4),
            )
            if (key.size != 32 || blob.size < 13 + 16) return null
            val counter = ByteArray(16)
            blob.copyInto(counter, 0, 1, 13)
            counter[12] = 0x00
            counter[13] = 0x00
            counter[14] = 0x00
            counter[15] = 0x02
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            val decrypted = cipher.doFinal(blob.copyOfRange(13, blob.size - 16))
            String(decrypted, Charsets.UTF_8)
        }.getOrNull()

        /** `--hex` ids decode to slash paths: hex XOR 0x38, /clock -> /clock.json. */
        fun deobfuscateSourceUrl(url: String?): String? {
            if (url == null) return null
            if (!url.startsWith("--")) return url.takeIf { it.startsWith("/") }
            val hex = url.removePrefix("--").trim()
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
                val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
                bytes[i] = (((hi shl 4) + lo) xor 0x38).toByte()
            }
            val decoded = String(bytes, Charsets.UTF_8)
                .replaceFirst("/clock", "/clock.json")
            return decoded
        }

        /** Locate a `tobeparsed` string field anywhere inside the response tree. */
        fun findTobeparsed(element: JsonElement?): String? {
            val obj = element as? JsonObject ?: return null
            for ((key, value) in obj) {
                if (key == "tobeparsed") {
                    (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
                }
                if (value is JsonObject) findTobeparsed(value)?.let { return it }
            }
            return null
        }
    }
}
