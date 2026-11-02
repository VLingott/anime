package dev.anime.source.play

import dev.anime.model.DetailResult
import dev.anime.model.DetailTag
import dev.anime.model.DetailTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HanimeTest {
    private val provider = HanimeProvider()

    // ── Signature ────────────────────────────────────────────────────────

    @Test
    fun `signature is sha256 of timestamp with site salts`() {
        val sig = provider.signatureFor(1_700_000_000L)
        assertEquals(
            "bc2348fd32ead4c3d79e87ad1abecd3759e573bf2f31024f7313a2fe054703ee",
            sig.signature,
        )
        assertTrue(sig.signature.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(1_700_000_000L, sig.time)
    }

    @Test
    fun `signature headers carry all required keys`() {
        val headers = provider.signatureFor(1L).headers
        assertEquals(
            setOf(
                "x-signature", "x-time", "x-signature-version",
                "x-csrf-token", "x-session-token", "x-user-license", "x-license",
            ),
            headers.keys,
        )
        assertEquals("web2", headers["x-signature-version"])
    }

    // ── Handshake seal/open ──────────────────────────────────────────────

    @Test
    fun `seal-open roundtrip returns payload`() {
        val payload = """{"timestamp_unix":1,"directive":"htv_player_handshake","slug":"x"}"""
        assertEquals(payload, HanimeProvider.Seal.open(HanimeProvider.Seal.seal(payload)))
    }

    @Test
    fun `seal output uses the documented envelope shape`() {
        val token = HanimeProvider.Seal.seal("payload")
        val padded = token.replace('-', '+').replace('_', '/')
        val fixed = padded + "=".repeat((4 - padded.length % 4) % 4)
        val json = java.util.Base64.getDecoder().decode(fixed).decodeToString()
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"alg\":\"AES-256-GCM\""))
        assertTrue(json.contains("\"v\":1"))
    }

    @Test
    fun `real handshake token decrypts with documented algorithm`() {
        val token = javaClass.getResourceAsStream("/hanime/handshake-token.txt")
            ?.bufferedReader()?.readText()?.trim().orEmpty()
        assertTrue(token.length > 100, "fixture token missing")

        val payload = HanimeProvider.Seal.open(token)

        assertTrue(payload.contains("\"sources\""))
        assertTrue(payload.contains("\"kind\":\"normal\""))
        assertTrue(payload.contains("\"height\":720"))
    }

    // ── Adult gating ─────────────────────────────────────────────────────

    @Test
    fun `hentai detail is gated by adult flag genres and tags`() {
        val base = DetailResult(id = 1L, title = DetailTitle(romaji = "x"))
        assertFalse(provider.isHentai(base))
        assertTrue(provider.isHentai(base.copy(adult = true)))
        assertTrue(provider.isHentai(base.copy(genres = listOf("Hentai"))))
        assertTrue(provider.isHentai(base.copy(tags = listOf(DetailTag("Hentai", 60)))))
    }

    // ── Series name extraction ───────────────────────────────────────────

    @Test
    fun `series base strips episode and trailing number suffixes`() {
        assertEquals("Yabai Fukushuu Site", provider.seriesBase("Yabai Fukushuu Site Ep 2"))
        assertEquals("Yabai Fukushuu Site", provider.seriesBase("Yabai Fukushuu Site 2"))
        assertEquals("Kaseifu no Mita", provider.seriesBase("Kaseifu no Mita"))
        assertEquals("Bad Ace Season 2", provider.seriesBase("Bad Ace Season 2"))
    }

    // ── Matching ─────────────────────────────────────────────────────────

    private fun hit(
        name: String,
        slug: String,
        created: Long,
        views: Long = 100_000,
        searchTitles: String? = null,
    ): HanimeProvider.Hit = HanimeProvider.Hit(
        id = created,
        name = name,
        slug = slug,
        views = views,
        searchTitles = searchTitles,
        createdAtUnix = created,
        releasedAtUnix = created,
    )

    @Test
    fun `series match picks the requested episode in creation order`() {
        val hits = listOf(
            hit("AKArite Ep 1", "aka-1", 100, searchTitles = "Kagachi-sama"),
            hit("AKArite Ep 2", "aka-2", 200),
        )
        val detail = DetailResult(id = 9L, title = DetailTitle(romaji = "AKArite"))
        assertEquals("aka-2", provider.matchSlug(hits, detail, 2))
        assertEquals("aka-1", provider.matchSlug(hits, detail, 1))
        assertNull(provider.matchSlug(hits, detail, 3))
    }

    @Test
    fun `exact title match outranks weaker containment`() {
        val hits = listOf(
            hit("Related Title", "weak", 300, views = 900_000, searchTitles = "The Foundation of My Heart"),
            hit("The Foundation of My Heart", "exact", 100),
        )
        val detail = DetailResult(id = 9L, title = DetailTitle(english = "The Foundation of My Heart"))
        assertEquals("exact", provider.matchSlug(hits, detail, 1))
    }

    @Test
    fun `unrelated titles never match`() {
        val hits = listOf(hit("Shikkoku no Shaga the Animetion", "shaga", 5, searchTitles = "shaga"))
        val detail = DetailResult(id = 9L, title = DetailTitle(romaji = "Momoiro!! Ecchi!"))
        assertNull(provider.matchSlug(hits, detail, 1))
    }

    @Test
    fun `too short titles are rejected`() {
        val hits = listOf(hit("Ep 1", "a", 1))
        val detail = DetailResult(id = 9L, title = DetailTitle(romaji = "Ep"))
        assertNull(provider.matchSlug(hits, detail, 1))
    }
}
