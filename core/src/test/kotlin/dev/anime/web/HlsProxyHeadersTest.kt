package dev.anime.web

import dev.anime.source.SourceContext
import dev.anime.source.buildHttpClient
import dev.anime.source.play.FlixCloud
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HlsProxyHeadersTest {
    private val proxy = HlsProxy(
        SourceContext(buildHttpClient("test")),
        dir = Files.createTempDirectory("hls-proxy-headers").toFile().absolutePath,
    )

    @Test
    fun hanimeHostsUseHanimeReferer() {
        for (url in listOf(
            "https://hanime.tv/hls/0005/abc",
            "https://auth.hanime.tv/api/v11/handshake",
            "https://p00.htv-tsukuyomi.com/0/0/0/5/v2x/segs/b0/2/s.html",
            "https://ct.htv-services.com/sign.bin",
            "https://guest.freeanimehentai.net/api/v11/search_hvs",
        )) {
            assertEquals("https://hanime.tv" to "https://hanime.tv/", proxy.headersFor(url), url)
        }
    }

    @Test
    fun flixcloudHostsKeepExistingReferer() {
        val (origin, referer) = proxy.headersFor("https://enc-dec.app/api/p/flixcloud")
        assertEquals(FlixCloud.BASE, origin)
        assertEquals(FlixCloud.BASE + "/", referer)
    }
}
