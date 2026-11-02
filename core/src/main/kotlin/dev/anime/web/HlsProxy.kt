package dev.anime.web

import dev.anime.source.SourceContext
import dev.anime.util.AppLog
import dev.anime.source.play.FlixCloud
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
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
    private val maxSegmentBytes: Long = 40L * 1024 * 1024,
    private val urlFor: (String) -> String = { "/api/hls?u=" + URLEncoder.encode(it, Charsets.UTF_8) },
) {
    sealed interface Result {
        data class Playlist(val text: String) : Result
        data class FileData(val file: File, val contentType: String) : Result
    }

    private val root = File(dir).apply { mkdirs() }
    private val gate = Semaphore(8)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val targets = ConcurrentHashMap<String, String>()

    @Volatile private var total = 0L
    @Volatile private var entries = 0

    init {
        val files = root.listFiles() ?: emptyArray()
        files.filter { !it.name.endsWith(".bin") }.forEach { it.delete() }
        val cached = files.filter { it.name.endsWith(".bin") }
        entries = cached.size
        total = cached.sumOf { it.length() }
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

    suspend fun openLinkFile(dataLink: String): Result? {
        val extracted = FlixCloud.extract(dataLink, ctx) ?: return null
        val (streamUrl, streamCtx) = extracted
        FlixCloud.register(streamUrl, streamCtx.wPayload, streamCtx.mask)
        val decrypted = FlixCloud.DEC + "/parse-flixcloud?url=" +
            URLEncoder.encode(streamUrl, Charsets.UTF_8) +
            "&w_payload=" + URLEncoder.encode(streamCtx.wPayload, Charsets.UTF_8)
        return handleFile(decrypted)
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
                    val (origin, referer) = headersFor(url)
                    header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.Referrer, referer)
                }.bodyAsText()
            } ?: return null
            if (!text.trimStart().startsWith("#EXTM3U")) return null
            return Result.Playlist(rewritePlaylist(text, effectiveBase(url)))
        }

        val file = cachedFile(url) ?: return null
        val contentType = if (isKeyPath(url)) "application/octet-stream" else "video/mp2t"
        return Result.FileData(file, contentType)
    }

    /** Origin/referer per upstream host; hanime rejects and times out other hosts. */
    internal fun headersFor(url: String): Pair<String, String> = when {
        isHanimeHost(url) -> "https://hanime.tv" to "https://hanime.tv/"
        else -> FlixCloud.BASE to FlixCloud.BASE + "/"
    }

    private fun isHanimeHost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "hanime.tv" || host.endsWith(".hanime.tv") ||
            host == "htv-tsukuyomi.com" || host.endsWith(".htv-tsukuyomi.com") ||
            host == "htv-services.com" || host.endsWith(".htv-services.com") ||
            host.endsWith("freeanimehentai.net")
    }

    private fun isPlaylistPath(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        return path.endsWith(".m3u8") || path.endsWith("master.txt") || url.contains("parse-flixcloud") ||
            (path.startsWith("/hls/") && isHanimeHost(url))
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
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }

        val mine = CompletableDeferred<File?>()
        val running = inFlight.putIfAbsent(file.name, mine)
        if (running != null) return running.await()

        val result = try {
            fetchToFile(url, file)
        } catch (error: Exception) {
            val host = runCatching { URI(url).host }.getOrNull() ?: "upstream"
            AppLog.w("hls", "Segment fetch failed from $host", error)
            null
        } finally {
            inFlight.remove(file.name)
        }
        mine.complete(result)
        return result
    }

    private suspend fun fetchToFile(url: String, file: File): File? {
        val temp = File(file.parentFile, file.name + ".part")
        val decoded = File(file.parentFile, file.name + ".decoded")
        val downloaded = withTimeoutOrNull(25_000) {
            gate.withPermit {
                ctx.http.prepareGet(url) {
                    header(HttpHeaders.UserAgent, UA)
                    header(HttpHeaders.Accept, "*/*")
                    val (origin, referer) = headersFor(url)
                    header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.Referrer, referer)
                }.execute { response ->
                    if (response.status.value !in 200..299) return@execute false
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    temp.outputStream().use { output ->
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            written += read
                            if (written > maxSegmentBytes) return@execute false
                            output.write(buffer, 0, read)
                        }
                    }
                    true
                }
            }
        } ?: false
        if (!downloaded || temp.length() == 0L) {
            temp.delete()
            return null
        }

        return try {
            decodeToFile(temp, decoded, url)
            if (!decoded.renameTo(file)) {
                decoded.copyTo(file, overwrite = true)
                decoded.delete()
            }
            total += file.length()
            entries += 1
            if (total > capBytes) prune()
            file
        } catch (error: Exception) {
            AppLog.w("hls", "Segment decode failed", error)
            file.delete()
            null
        } finally {
            temp.delete()
            decoded.delete()
        }
    }

    private fun decodeToFile(raw: File, target: File, url: String) {
        val header = ByteArray(12)
        val input = raw.inputStream()
        try {
            val read = input.read(header)
            val headerSize = when {
                read >= 12 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> 12
                read >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> 8
                else -> 0
            }
            val mask = if (headerSize > 0) FlixCloud.contextFor(url)?.mask ?: MASK else null
            target.outputStream().use { output ->
                if (headerSize == 0 && read > 0) output.write(header, 0, read)
                val buffer = ByteArray(64 * 1024)
                var offset = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    if (mask != null) {
                        for (i in 0 until count) {
                            buffer[i] = (buffer[i].toInt() xor mask[(offset + i) and 15].toInt()).toByte()
                        }
                    }
                    output.write(buffer, 0, count)
                    offset += count
                }
            }
        } finally {
            input.close()
        }
    }

    private fun prune() {
        val target = (capBytes * 0.85).toLong()
        val files = root.listFiles()?.filter { it.name.endsWith(".bin") }?.sortedBy { it.lastModified() } ?: return
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
