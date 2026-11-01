package dev.anime.web

import com.sun.net.httpserver.HttpServer
import dev.anime.source.SourceContext
import dev.anime.source.buildHttpClient
import dev.anime.source.play.FlixCloud
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsProxyTest {
    private val requests = AtomicInteger()
    private val body = ByteArray(256 * 1024) { (it % 251).toByte() }
    private val mask = ByteArray(16) { (it * 11 + 3).toByte() }
    private val maskedBody = ByteArray(12 + body.size).also { payload ->
        val header = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)
        header.copyInto(payload)
        for (i in body.indices) {
            payload[12 + i] = (body[i].toInt() xor mask[i and 15].toInt()).toByte()
        }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/segment.ts") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/_v7/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, maskedBody.size.toLong())
            exchange.responseBody.use { it.write(maskedBody) }
        }
        start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun streamsSegmentToDiskAndServesCachedFile() = runBlocking {
        val dir = Files.createTempDirectory("hls-proxy").toFile()
        val proxy = HlsProxy(
            SourceContext(buildHttpClient("test")),
            dir = dir.absolutePath,
        )
        val url = "http://127.0.0.1:${server.address.port}/segment.ts"

        val first = proxy.handleFile(url)
        check(first is HlsProxy.Result.FileData)
        assertContentEquals(body, first.file.readBytes())
        assertEquals(1, requests.get())

        val second = proxy.handleFile(url)
        check(second is HlsProxy.Result.FileData)
        assertEquals(first.file.absolutePath, second.file.absolutePath)
        assertEquals(1, requests.get())
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.endsWith(".part") })
    }

    @Test
    fun decodesMaskedSegmentAcrossChunkBoundaries() = runBlocking {
        val dir = Files.createTempDirectory("hls-proxy-masked").toFile()
        val proxy = HlsProxy(
            SourceContext(buildHttpClient("test")),
            dir = dir.absolutePath,
        )
        val url = "http://127.0.0.1:${server.address.port}/_v7/0123456789abcdef01234567/segment.ts"
        FlixCloud.register(url, "payload", mask)

        val result = proxy.handleFile(url)
        check(result is HlsProxy.Result.FileData)
        assertContentEquals(body, result.file.readBytes())
    }

    @Test
    fun rejectsSegmentAboveSizeCap() = runBlocking {
        val dir = Files.createTempDirectory("hls-proxy-cap").toFile()
        val proxy = HlsProxy(
            SourceContext(buildHttpClient("test")),
            dir = dir.absolutePath,
            maxSegmentBytes = 64 * 1024,
        )

        assertNull(proxy.handleFile("http://127.0.0.1:${server.address.port}/segment.ts"))
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.endsWith(".bin") || it.name.endsWith(".part") })
    }
}
