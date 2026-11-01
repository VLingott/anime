package dev.anime.web

import dev.anime.source.SourceContext
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_W = 128
private const val MAX_H = 192

class ThumbService(
    dir: String,
    private val ctx: SourceContext,
    private val capBytes: Long = 32L * 1024 * 1024,
) {
    private val root = File(dir).apply { mkdirs() }
    private val gate = Semaphore(8)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    @Volatile
    private var total = 0L

    @Volatile
    private var entries = 0

    init {
        val files = root.listFiles() ?: emptyArray()
        entries = files.size
        total = files.sumOf { it.length() }
    }

    fun stats(): Pair<Long, Int> = total to entries

    fun cap(): Long = capBytes

    suspend fun get(rawUrl: String, maxW: Int = MAX_W, maxH: Int = MAX_H): ByteArray? {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        val w = maxW.coerceIn(16, 1536)
        val h = maxH.coerceIn(16, 1536)

        val file = File(root, sha1("$url@${w}x$h") + ".jpg")
        if (file.length() > 0) return runCatching { file.readBytes() }.getOrNull()

        val mine = CompletableDeferred<ByteArray?>()
        val running = inFlight.putIfAbsent(file.name, mine)
        if (running != null) return running.await()

        val bytes = try {
            fetchAndStore(url, file, w, h)
        } catch (_: Exception) {
            null
        } finally {
            inFlight.remove(file.name)
        }
        mine.complete(bytes)
        return bytes
    }

    private suspend fun fetchAndStore(url: String, file: File, maxW: Int, maxH: Int): ByteArray? {
        val raw = try {
            withTimeoutOrNull(5_000) {
                gate.withPermit { ctx.http.get(url).bodyAsBytes() }
            }
        } catch (_: Exception) {
            null
        } ?: return null
        if (raw.isEmpty()) return null

        val out = resize(raw, maxW, maxH) ?: return null
        withContext(Dispatchers.IO) {
            runCatching { file.writeBytes(out) }
        }
        total += out.size
        entries += 1
        if (total > capBytes) prune()
        return out
    }

    private fun resize(raw: ByteArray, maxW: Int, maxH: Int): ByteArray? = try {
        val src = ImageIO.read(ByteArrayInputStream(raw))
            ?: return null
        val scale = min(min(maxW.toDouble() / src.width, maxH.toDouble() / src.height), 1.0)
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())

        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color(0x10, 0x10, 0x14)
        g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()

        ByteArrayOutputStream(4096).use { baos ->
            ImageIO.write(dst, "jpg", baos)
            baos.toByteArray()
        }
    } catch (_: Exception) {
        null
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
}
