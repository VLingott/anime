package dev.anime.web

import com.sun.net.httpserver.HttpServer
import dev.anime.source.SourceContext
import dev.anime.source.buildHttpClient
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
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/segment.ts") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
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
