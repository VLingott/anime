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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Adult anime playback provider backed by hanime.tv.
 *
 * Only engages for AniList entries flagged as adult ("Hentai"); regular shows
 * never touch the hanime catalog. The catalog is a single full-dataset request
 * cached in memory (the API returns everything at once, like the Aniyomi
 * extension); per-video streams come from POST auth.hanime.tv/api/v11/handshake.
 *
 * Stream URLs need a `Referer: https://hanime.tv/...` header, which `HlsProxy`
 * adds for hanime CDN hosts.
 */
class HanimeProvider : PlaybackProvider {
    override val id = "hanime"
    override val name = "hanime.tv"

    override suspend fun resolve(
        detail: DetailResult,
        season: Int,
        episode: Int,
        ctx: SourceContext,
        absoluteEpisode: Int?,
        episodeTitle: String?,
    ): List<PlaySource> {
        if (!isHentai(detail) || episode < 1) return emptyList()

        val dataset = dataset(ctx) ?: return emptyList()
        val slug = matchSlug(dataset, detail, episode) ?: return emptyList()
        val payload = handshake(slug, ctx) ?: return emptyList()

        return payload.sources
            .filter { it.kind == "normal" && it.src.isNotBlank() && (it.height ?: 0) > 0 }
            .distinctBy { it.height }
            .sortedByDescending { it.height }
            .take(4)
            .map { stream ->
                PlaySource(
                    id = id,
                    name = "$name ${stream.height}p",
                    kind = "hls",
                    url = "/api/hls/stream.m3u8?u=" + URLEncoder.encode(absoluteSource(stream.src), "utf-8"),
                    languages = listOf("Japanese"),
                )
            }
    }

    // ── Catalog ──────────────────────────────────────────────────────────

    @Serializable
    internal data class Hit(
        val id: Long? = null,
        val name: String = "",
        @SerialName("search_titles") val searchTitles: String? = null,
        val slug: String? = null,
        val views: Long? = null,
        val brand: String? = null,
        val likes: Long? = null,
        @SerialName("created_at_unix") val createdAtUnix: Long? = null,
        @SerialName("released_at_unix") val releasedAtUnix: Long? = null,
    ) {
        fun orderKey(): Long = createdAtUnix ?: releasedAtUnix ?: id ?: 0L
    }

    @Serializable
    internal data class SearchResponse(val data: List<Hit> = emptyList())

    @Serializable
    internal data class HandshakeSource(
        val src: String = "",
        val height: Int? = null,
        val width: Long? = null,
        val label: String? = null,
        val kind: String? = null,
    )

    @Serializable
    internal data class HandshakePayload(val sources: List<HandshakeSource> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val datasetLock = Any()
    @Volatile private var datasetHits: List<Hit> = emptyList()
    @Volatile private var datasetAt = 0L

    private suspend fun dataset(ctx: SourceContext): List<Hit>? {
        val age = System.currentTimeMillis() - datasetAt
        if (datasetHits.isNotEmpty() && age < DATASET_TTL_MS) return datasetHits

        val fetched = fetchDataset(ctx)
        synchronized(datasetLock) {
            if (fetched != null) {
                datasetHits = fetched
                datasetAt = System.currentTimeMillis()
                return fetched
            }
        }
        val staleAge = System.currentTimeMillis() - datasetAt
        return if (datasetHits.isNotEmpty() && staleAge < STALE_TTL_MS) datasetHits else null
    }

    private suspend fun fetchDataset(ctx: SourceContext): List<Hit>? {
        val text = runCatching {
            ctx.http.get(SEARCH_URL) {
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "application/json")
                signature.headers.forEach { (key, value) -> header(key, value) }
            }.bodyAsText()
        }.onFailure { AppLog.w("hanime", "Catalog fetch failed", it) }
            .getOrNull() ?: return null

        return runCatching {
            json.decodeFromString<SearchResponse>(text).data
        }.onFailure { AppLog.w("hanime", "Invalid catalog response (${it::class.simpleName})") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Score a catalog hit's series title against the detail's titles (higher wins). */
    internal fun seriesScore(hit: Hit, wanted: List<String>): Int {
        val base = normalize(seriesBase(hit.name)) ?: return 0
        var best = 0
        for (w in wanted) {
            val score = when {
                base == w -> 3
                base.startsWith(w) -> 2
                else -> 0
            }
            if (score > best) best = score
        }
        if (best > 0) return best
        val catalog = normalize(hit.searchTitles ?: hit.name) ?: return 0
        return if (wanted.any { it in catalog }) 1 else 0
    }

    internal fun matchSlug(hits: List<Hit>, detail: DetailResult, episode: Int): String? {
        val wanted = detailTitles(detail).mapNotNull { normalize(it) }.filter { it.length >= TITLE_MIN }
        if (wanted.isEmpty()) return null

        val best = hits
            .mapNotNull { hit -> hit.slug?.takeIf { it.isNotBlank() }?.let { hit to it } }
            .maxByOrNull { (hit, _) ->
                seriesScore(hit, wanted) * 1_000_000 + (hit.views ?: 0L).coerceAtMost(999_999)
            } ?: return null

        val bestScore = seriesScore(best.first, wanted)
        if (bestScore <= 0) return null
        val base = seriesBase(best.first.name)

        val group = hits
            .filter { seriesBase(it.name) == base && !it.slug.isNullOrBlank() }
            .sortedBy { it.orderKey() }
        val pick = if (bestScore >= 2) {
            // ordered series match: episode N is the Nth video of the group
            group.getOrNull(episode - 1)
                ?: (if (episode == 1 && group.isEmpty()) null else null)
        } else {
            // search-catalog containment fallback: only trust a single video
            if (episode == 1) best.first else null
        }
        return pick?.slug
    }

    internal fun isHentai(detail: DetailResult): Boolean =
        detail.adult ||
            detail.genres.any { it.equals("Hentai", ignoreCase = true) } ||
            detail.tags.any { it.name.equals("Hentai", ignoreCase = true) }

    internal fun detailTitles(detail: DetailResult): List<String> = listOfNotNull(
        detail.title?.romaji,
        detail.title?.english,
        detail.title?.native,
    ) + detail.synonyms

    /** Video name → series name: strips " Ep N" markers and trailing standalone numbers. */
    internal fun seriesBase(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.contains(" Ep ")) return trimmed.split(" Ep ")[0].trim()
        val match = EPISODE_SUFFIX.find(trimmed) ?: return trimmed
        val before = trimmed.substring(0, match.range.first)
        if (before.trimEnd().endsWith("Season", ignoreCase = true)) return trimmed
        if (COMPOUND_PREFIX.find(before) != null) return trimmed
        return before.trim()
    }

    private fun normalize(value: String?): String? =
        value?.let { s ->
            val cleaned = s.lowercase()
                .replace(Regex("\\(.*?\\)"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            cleaned.ifEmpty { null }
        }

    private fun absoluteSource(src: String): String =
        if (src.startsWith("http")) src else BASE_URL.trimEnd('/') + src

    // ── Request signature ────────────────────────────────────────────────

    internal data class Signature(val signature: String, val time: Long) {
        val headers: Map<String, String> = mapOf(
            "x-signature" to signature,
            "x-time" to time.toString(),
            "x-signature-version" to "web2",
            "x-csrf-token" to "",
            "x-session-token" to "",
            "x-user-license" to "",
            "x-license" to "",
        )
    }

    private var sig: Signature? = null

    private val signature: Signature        @Synchronized get() {
            sig?.let { current ->
                if (System.currentTimeMillis() / 1000 - current.time < SIG_TTL_SEC) return current
            }
            val nowSec = System.currentTimeMillis() / 1000
            return Signature(signatureFor(nowSec).signature, nowSec).also { sig = it }
        }

    internal fun signatureFor(timeSec: Long): Signature {
        val input = "$timeSec,$SALT_1,$ORIGIN,$SALT_2,$timeSec"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Signature(digest.joinToString("") { "%02x".format(it) }, timeSec)
    }

    // ── Stream handshake ─────────────────────────────────────────────────

    private suspend fun handshake(slug: String, ctx: SourceContext): HandshakePayload? {
        val payload = buildJsonObject {
            put("timestamp_unix", signature.time)
            put("directive", "htv_player_handshake")
            put("slug", slug)
        }.toString()
        val body = """{"token":"${Seal.seal(payload)}"}"""

        val response = runCatching {
            ctx.http.post(AUTH_URL) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Referrer, "$BASE_URL/videos/hentai/$slug")
                header(HttpHeaders.Origin, BASE_URL)
                signature.headers.forEach { (key, value) -> header(key, value) }
                setBody(body)
            }
        }.onFailure { AppLog.w("hanime", "Handshake request failed for $slug", it) }
            .getOrNull() ?: return null

        val statusOk = response.status.value in 200..299
        if (!statusOk) {
            AppLog.w("hanime", "Handshake returned ${response.status.value} for $slug")
            return null
        }
        val xToken = response.headers["x-token"]
        if (xToken.isNullOrBlank()) {
            AppLog.w("hanime", "Handshake returned no x-token for $slug")
            return null
        }

        return runCatching {
            json.decodeFromString<HandshakePayload>(Seal.open(xToken))
        }.onFailure { AppLog.w("hanime", "Handshake payload unreadable for $slug", it) }
            .getOrNull()?.takeIf { it.sources.isNotEmpty() }
    }

    /** "insecure message" envelope used by the auth handshake endpoint. */
    internal object Seal {
        private const val KEY_SEED = "htv-insecure-handshake-v1"
        private const val AAD = "htv-insecure-v1"

        private val key: ByteArray by lazy {
            MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun seal(payload: String): String {
            val iv = ByteArray(GCM_IV).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val raw = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            val ciphertext = raw.copyOfRange(0, raw.size - GCM_TAG)
            val tag = raw.copyOfRange(raw.size - GCM_TAG, raw.size)
            return Base64.encode(
                buildJsonObject {
                    put("v", 1)
                    put("alg", "AES-256-GCM")
                    put("iv", Base64.encode(iv))
                    put("tag", Base64.encode(tag))
                    put("data", Base64.encode(ciphertext))
                }.toString().toByteArray(Charsets.UTF_8),
            )
        }

        fun open(token: String): String {
            val envelope = json.parseToJsonElement(String(Base64.decode(token), Charsets.UTF_8))
                as kotlinx.serialization.json.JsonObject
            val iv = Base64.decode(primitive(envelope, "iv"))
            val tag = Base64.decode(primitive(envelope, "tag"))
            val data = Base64.decode(primitive(envelope, "data"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(data + tag), Charsets.UTF_8)
        }

        private fun primitive(envelope: kotlinx.serialization.json.JsonObject, key: String): String =
            (envelope.getValue(key) as JsonPrimitive).content

        internal object Base64 {
            fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

            fun decode(value: String): ByteArray {
                val padded = value
                    .replace('-', '+')
                    .replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
                return java.util.Base64.getDecoder().decode(padded)
            }
        }

        private const val GCM_IV = 12
        private const val GCM_TAG = 16
        private const val GCM_TAG_BITS = 128
    }

    internal companion object {
        const val BASE_URL = "https://hanime.tv"
        const val AUTH_URL = "https://auth.hanime.tv/api/v11/handshake"
        const val SEARCH_URL = "https://guest.freeanimehentai.net/api/v11/search_hvs"

        const val ORIGIN = "https://hanime.tv"
        const val SALT_1 = "Xkdi29"
        const val SALT_2 = "mn2"

        const val DATASET_TTL_MS = 10L * 60_000
        const val STALE_TTL_MS = 45L * 60_000
        const val SIG_TTL_SEC = 240L
        const val TITLE_MIN = 4

        private val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val EPISODE_SUFFIX = Regex("""(?:\s+E[Pp]\s*\.?\s*\d+(?:\s*[-–]\s*\d+)?)$|-?\s+\d{1,3}$""")
        private val COMPOUND_PREFIX = Regex("""(?:X+|XX|Vol(?:ume)?|Part|CD|Disk|Disc)\s*$""", RegexOption.IGNORE_CASE)
    }
}
