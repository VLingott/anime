package dev.anime.web

import dev.anime.source.SourceContext
import dev.anime.util.AppLog
import dev.anime.source.play.FlixCloud
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class HlsProxy(
    private val ctx: SourceContext,
    dir: String = "data/hls-cache",
    private val capBytes: Long = 512L * 1024 * 1024,
    private val urlFor: (String) -> String = { "/api/hls?u=" + URLEncoder.encode(it, Charsets.UTF_8) },
) {
    sealed interface Result {
        data class Playlist(val text: String) : Result
        data class Binary(val bytes: ByteArray, val contentType: String) : Result
        data class FileData(val file: File, val contentType: String) : Result
    }

    private val root = File(dir).apply { mkdirs() }
    private val gate = Semaphore(8)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    private val targets = ConcurrentHashMap<String, String>()

    @Volatile private var total = 0L
    @Volatile private var entries = 0

    init {
        val files = root.listFiles() ?: emptyArray()
        entries = files.size
        total = files.sumOf { it.length() }
    }

    suspend fun openLink(dataLink: String): Result? {
        val extracted = FlixCloud.extract(dataLink, ctx) ?: return null
        val (streamUrl, streamCtx) = extracted
        FlixCloud.register(streamUrl, streamCtx.wPayload, streamCtx.mask)
        val decrypted = FlixCloud.DEC + "/parse-flixcloud?url=" +
            URLEncoder.encode(streamUrl, Charsets.UTF_8) +
            "&w_payload=" + URLEncoder.encode(streamCtx.wPayload, Charsets.UTF_8)
        return handle(decrypted)
    }

    private fun resolveTarget(rawUrl: String): String? {
        val requested = rawUrl.trim()
        return if (requested.startsWith("http://") || requested.startsWith("https://")) {
            requested
        } else {
            targets[requested]
        }
    }

    suspend fun handleFile(rawUrl: String): Result? {
        val url = resolveTarget(rawUrl) ?: return null
        if (isPlaylistPath(url)) {
            return handle(url)
        }
        val file = cachedFile(url) ?: return null
        val contentType = if (isKeyPath(url)) "application/octet-stream" else "video/mp2t"
        return Result.FileData(file, contentType)
    }

    suspend fun handle(rawUrl: String): Result? {
        val url = resolveTarget(rawUrl) ?: return null

        if (isPlaylistPath(url)) {
            val text = withTimeoutOrNull(20_000) {
                ctx.http.get(url) {
                    header(HttpHeaders.UserAgent, UA)
                    header(HttpHeaders.Accept, "*/*")
                    if (url.contains("enc-dec.app")) {
                        header(HttpHeaders.Origin, "https://enc-dec.app")
                        header(HttpHeaders.Referrer, "https://enc-dec.app/")
                    } else {
                        header(HttpHeaders.Origin, FlixCloud.BASE)
                        header(HttpHeaders.Referrer, FlixCloud.BASE + "/")
                    }
                }.bodyAsText()
            } ?: return null
            if (!text.trimStart().startsWith("#EXTM3U")) return null
            return Result.Playlist(rewritePlaylist(text, effectiveBase(url)))
        }

        val bytes = cached(url) ?: return null
        val contentType = if (isKeyPath(url)) "application/octet-stream" else "video/mp2t"
        return Result.Binary(bytes, contentType)
    }

    private fun isPlaylistPath(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        return path.endsWith(".m3u8") || path.endsWith("master.txt") || url.contains("parse-flixcloud")
    }

    private fun isKeyPath(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        return path.substringAfterLast('/').startsWith("key")
    }

    private fun effectiveBase(url: String): String {
        if (!url.contains("enc-dec.app")) return url
        val decoded = runCatching { URLDecoder.decode(url, Charsets.UTF_8) }.getOrNull() ?: return url
        val m = Regex("""[?&]url=([^&]+)""").find(decoded) ?: return url
        return runCatching { URLDecoder.decode(m.groupValues[1], Charsets.UTF_8) }.getOrDefault(url)
    }

    private fun rewritePlaylist(text: String, base: String): String {
        val uriAttr = Regex("""URI="([^"]*)"""")
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MEDIA") || t.startsWith("#EXT-X-MAP") ->
                    uriAttr.replace(line) { m -> "URI=\"" + proxyPath(resolve(base, m.groupValues[1])) + "\"" }
                t.isEmpty() || t.startsWith("#") -> line
                else -> proxyPath(resolve(base, t))
            }
        }
    }

    private fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    private fun proxyPath(abs: String): String {
        val id = sha1(abs)
        targets[id] = abs
        val target = urlFor(id)
        if (!target.startsWith("http://") && !target.startsWith("https://") && !target.startsWith("/")) return target
        val suffix = when {
            isPlaylistPath(abs) -> "stream.m3u8"
            isKeyPath(abs) -> "key.ts"
            else -> "segment.ts"
        }
        val query = target.indexOf('?')
        return if (query < 0) "$target/$suffix" else target.substring(0, query) + "/" + suffix + target.substring(query)
    }

    private suspend fun cachedFile(url: String): File? {
        val file = File(root, sha1(url) + ".bin")
        if (file.length() > 0) return file
        cached(url) ?: return null
        return file.takeIf { it.length() > 0 }
    }

    private suspend fun cached(url: String): ByteArray? {
        val file = File(root, sha1(url) + ".bin")
        if (file.length() > 0) return runCatching { file.readBytes() }.getOrNull()

        val mine = CompletableDeferred<ByteArray?>()
        val running = inFlight.putIfAbsent(file.name, mine)
        if (running != null) return running.await()

        val bytes = try {
            fetch(url, file)
        } catch (error: Exception) {
            val host = runCatching { URI(url).host }.getOrNull() ?: "upstream"
            AppLog.w("hls", "Segment fetch failed from $host", error)
            null
        } finally {
            inFlight.remove(file.name)
        }
        mine.complete(bytes)
        return bytes
    }

    private suspend fun fetch(url: String, file: File): ByteArray? {
        val raw = withTimeoutOrNull(25_000) {
            gate.withPermit {
                ctx.http.get(url) {
                    header(HttpHeaders.UserAgent, UA)
                    header(HttpHeaders.Accept, "*/*")
                    header(HttpHeaders.Origin, FlixCloud.BASE)
                    header(HttpHeaders.Referrer, FlixCloud.BASE + "/")
                }.bodyAsBytes()
            }
        } ?: return null
        if (raw.isEmpty() || raw.size > 40 * 1024 * 1024) return null

        val decoded = decode(raw, url)
        runCatching { file.writeBytes(decoded) }
        total += decoded.size
        entries += 1
        if (total > capBytes) prune()
        return decoded
    }

    private fun decode(raw: ByteArray, url: String): ByteArray {
        val headerSize = when {
            raw.size >= 12 && raw[0] == 0x52.toByte() && raw[1] == 0x49.toByte() &&
                raw[2] == 0x46.toByte() && raw[3] == 0x46.toByte() &&
                raw[8] == 0x57.toByte() && raw[9] == 0x45.toByte() &&
                raw[10] == 0x42.toByte() && raw[11] == 0x50.toByte() -> 12
            raw.size >= 8 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() &&
                raw[2] == 0x4E.toByte() && raw[3] == 0x47.toByte() -> 8
            else -> return raw
        }
        val body = raw.copyOfRange(headerSize, raw.size)
        val mask = FlixCloud.contextFor(url)?.mask ?: MASK
        for (i in body.indices) body[i] = (body[i].toInt() xor mask[i and 15].toInt()).toByte()
        return body
    }

    private fun prune() {
        val target = (capBytes * 0.85).toLong()
        val files = root.listFiles()?.sortedBy { it.lastModified() } ?: return
        var live = total
        for (f in files) {
            if (live <= target) break
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                live -= size
                entries -= 1
            }
        }
        total = live
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val MASK = byteArrayOf(
            157.toByte(), 42.toByte(), 241.toByte(), 71.toByte(), 179.toByte(), 142.toByte(), 92.toByte(), 112.toByte(),
            166.toByte(), 25.toByte(), 228.toByte(), 59.toByte(), 216.toByte(), 98.toByte(), 15.toByte(), 197.toByte(),
        )
    }
}
