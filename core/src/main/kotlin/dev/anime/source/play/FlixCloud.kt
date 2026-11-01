package dev.anime.source.play

import dev.anime.source.SourceContext
import dev.anime.store.str
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * FlixCloud extraction + context (port of the Re:ANIME Aniyomi extension).
 * Segments are AES-128 encrypted; some variants additionally carry a fake
 * WebP/PNG header and are XOR-masked with a per-embed 16-byte mask.
 */
object FlixCloud {
    const val BASE = "https://flixcloud.cc"
    const val DEC = "https://enc-dec.app/api"

    private const val UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val EMBED_DATA_REGEX = Regex("""type:\s*"data",\s*data:\s*(\{.*?\})\s*,\s*uses:""", RegexOption.DOT_MATCHES_ALL)
    private val HLS_SCRIPT_REGEX = Regex("""href="([^"]*hls\.js[^"]*)"""")
    private val XOR_MASK_REGEX = Regex("""for\(var f=\[(\d{1,3}(?:,\d{1,3}){15})]""")
    private val JSON5_KEY_REGEX = Regex("""([{,]\s*)([A-Za-z_$][\w$]*)\s*:""")
    private val JSON5_TRAILING_COMMA_REGEX = Regex("""(,)\s*([}\]])""")
    private val JSON5_UNDEFINED_REGEX = Regex(""":\s*undefined""")
    private val VIDEO_ID_REGEX = Regex("""/_v7/([0-9a-fA-F-]{20,})/""")
    private val WPAYLOAD_REGEX = Regex("""w_payload=([^&]+)""")

    private val fallbackMask = byteArrayOf(
        157.toByte(), 42.toByte(), 241.toByte(), 71.toByte(), 179.toByte(), 142.toByte(), 92.toByte(), 112.toByte(),
        166.toByte(), 25.toByte(), 228.toByte(), 59.toByte(), 216.toByte(), 98.toByte(), 15.toByte(), 197.toByte(),
    )

    data class StreamContext(val wPayload: String, val mask: ByteArray)

    private val contexts = ConcurrentHashMap<String, StreamContext>()

    fun register(streamUrl: String, wPayload: String, mask: ByteArray) {
        val id = VIDEO_ID_REGEX.find(streamUrl)?.groupValues?.get(1) ?: return
        contexts[id] = StreamContext(wPayload, mask)
    }

    fun contextFor(url: String): StreamContext? {
        val decoded = runCatching { java.net.URLDecoder.decode(url, Charsets.UTF_8) }.getOrNull() ?: url
        val id = VIDEO_ID_REGEX.find(decoded)?.groupValues?.get(1)
        if (id != null) contexts[id]?.let { return it }
        val wp = WPAYLOAD_REGEX.find(decoded)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
            ?: return null
        return StreamContext(wp, fallbackMask)
    }

    suspend fun extract(dataLink: String, ctx: SourceContext): Pair<String, StreamContext>? {
        val html = ctx.http.get(dataLink) {
            header(HttpHeaders.UserAgent, UA)
            header(HttpHeaders.Accept, "*/*")
            header(HttpHeaders.Origin, BASE)
            header(HttpHeaders.Referrer, "$BASE/")
        }.bodyAsText()

        val mask = fetchMask(html, ctx) ?: fallbackMask
        val dataMatch = EMBED_DATA_REGEX.find(html) ?: return null
        val embedJson = runCatching { Json.parseToJsonElement(json5ToJson(dataMatch.groupValues[1])) }.getOrNull()
            ?: return null

        val tokenText = ctx.http.post("$DEC/dec-flixcloud?type=token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, UA)
            header(HttpHeaders.Accept, "*/*")
            setBody(buildJsonObject { put("data", embedJson) }.toString())
        }.bodyAsText()
        val tokenJson = runCatching { Json.parseToJsonElement(tokenText).jsonObject }.getOrNull() ?: return null
        val token = tokenJson["result"]?.jsonObject?.get("token")?.str() ?: return null
        val tokenContext = tokenJson["result"]?.jsonObject?.get("context")?.jsonObject ?: return null

        val m3u8Text = ctx.http.get("$BASE/api/m3u8/$token") {
            header(HttpHeaders.UserAgent, UA)
            header(HttpHeaders.Accept, "*/*")
            header(HttpHeaders.Origin, BASE)
            header(HttpHeaders.Referrer, "$BASE/")
        }.bodyAsText()
        val m3u8Json = runCatching { Json.parseToJsonElement(m3u8Text).jsonObject }.getOrNull() ?: return null

        val streamText = ctx.http.post("$DEC/dec-flixcloud?type=stream") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, UA)
            header(HttpHeaders.Accept, "*/*")
            setBody(
                buildJsonObject {
                    putJsonObject("data") {
                        put("context", tokenContext)
                        put("stream_response", m3u8Json)
                    }
                }.toString(),
            )
        }.bodyAsText()
        val result = runCatching { Json.parseToJsonElement(streamText).jsonObject["result"]?.jsonObject }.getOrNull()
            ?: return null
        val streamUrl = result["stream"]?.str() ?: return null
        val wPayload = result["context"]?.jsonObject?.get("w_payload")?.str() ?: return null

        return streamUrl to StreamContext(wPayload, mask)
    }

    private suspend fun fetchMask(html: String, ctx: SourceContext): ByteArray? {
        val href = HLS_SCRIPT_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val scriptUrl = if (href.startsWith("http")) href else BASE + href
        val js = runCatching {
            ctx.http.get(scriptUrl) {
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Referrer, "$BASE/")
            }.bodyAsText()
        }.getOrNull() ?: return null
        val nums = XOR_MASK_REGEX.find(js)?.groupValues?.get(1)?.split(",") ?: return null
        return runCatching { ByteArray(nums.size) { nums[it].trim().toInt().toByte() } }.getOrNull()
    }

    fun json5ToJson(raw: String): String = raw
        .replace(JSON5_KEY_REGEX) { "${it.groupValues[1]}\"${it.groupValues[2]}\":" }
        .replace(JSON5_TRAILING_COMMA_REGEX, "$1")
        .replace(JSON5_UNDEFINED_REGEX, ": null")
}
