package dev.anime.source.play

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllAnimeTest {
    private val provider = AllAnimeProvider()
    private val crypto = AllAnimeProvider.Crypto

    // ── Epoch candidates ─────────────────────────────────────────────────

    @Test
    fun `epoch candidates include the previous bucket inside its first day`() {
        val bucket = 604_800_000L
        val base = 10 * bucket
        // A fresh bucket keeps the previous one alive in its first day (web client qS).
        assertEquals(listOf(9L, 10L), provider.epochCandidates(bucket, base))
        assertEquals(listOf(9L, 10L), provider.epochCandidates(bucket, base + 1))
        assertEquals(listOf(10L), provider.epochCandidates(bucket, base + bucket - 1))
        assertEquals(listOf(0L), provider.epochCandidates(bucket, bucket - 1))
    }

    // ── Key derivation (cross-checked against the web client) ────────────

    @Test
    fun `key derivation is base64-partB XOR mask`() {
        val key = requireNotNull(
            crypto.deriveKey(
                "UNQhzl5g9ARRgv6O25blL+lPqsuRMOJ2EA+mLXX2pWk=",
                "a39b86dbbcf57f884f3e9074969e7fe26656c74012e4545605896621ffa441c1",
            ),
        )
        assertEquals(0x50 xor 0xa3, key[0].toInt() and 0xFF)
        assertEquals(32, key.size)
        assertNull(crypto.deriveKey("AAAA", "a39b"))
    }

    // ── Source URL deobfuscation (ani-cli sed table pairs) ───────────────

    @Test
    fun `obfuscated ids decode with XOR 0x38 and clock rewrite`() {
        val path = "/apivtwo/clock?id=abc"
        val obfuscated = "--" + path.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02x".format(it.toInt() xor 0x38) }
        assertEquals("/apivtwo/clock.json?id=abc", crypto.deobfuscateSourceUrl(obfuscated))
        assertEquals("A", crypto.deobfuscateSourceUrl("--79"))
        assertEquals("0", crypto.deobfuscateSourceUrl("--08"))
        assertEquals("1", crypto.deobfuscateSourceUrl("--09"))
        // Non-obfuscated ids stay verbatim without the /clock rewrite.
        assertEquals("/apivtwo/clock?id=abc", crypto.deobfuscateSourceUrl("/apivtwo/clock?id=abc"))
        // Direct URLs and malformed hex are not clock refs.
        assertNull(crypto.deobfuscateSourceUrl("https://tools.fast4speed.rsvp/video/xyz"))
        assertNull(crypto.deobfuscateSourceUrl("--0g"))
    }

    // ── tobeparsed CTR layout ────────────────────────────────────────────

    @Test
    fun `tobeparsed layout is marker iv plaintext then ignored tag`() {
        val key = requireNotNull(
            crypto.deriveKey(
                "UNQhzl5g9ARRgv6O25blL+lPqsuRMOJ2EA+mLXX2pWk=",
                "a39b86dbbcf57f884f3e9074969e7fe26656c74012e4545605896621ffa441c1",
            ),
        )
        val payload = """{"episode":{"sourceUrls":[]}}"""
        val blob = buildTobeparsedFixture(payload, key)
        val token = Base64.getEncoder().encodeToString(blob)
        assertEquals(payload, crypto.decryptTobeparsed(token, key))
        assertNull(crypto.decryptTobeparsed(Base64.getEncoder().encodeToString(ByteArray(10)), key))
    }

    private fun buildTobeparsedFixture(payload: String, key: ByteArray): ByteArray {
        val iv = byteArrayOf(7, 1, 4, 2, 0, 9, 3, 3, 5, 8, 6, 2)
        val counter = ByteArray(16).also { iv.copyInto(it, 0, 0, 12) }
        counter[12] = 0; counter[13] = 0; counter[14] = 0; counter[15] = 2
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(counter))
        val ciphertext = cipher.update(payload.toByteArray(Charsets.UTF_8))
        return byteArrayOf(0x01) + iv + ciphertext + ByteArray(16) { 0xAA.toByte() }
    }

    // ── x-aa-boot signatures (captured from the live web client) ─────────

    @Test
    fun `x-aa-boot matches legacy web client`() {
        val got = crypto.signAaBoot(
            maskHex = "ff65f1ba05d2556424dfec9f38f816e0a7d284a951845c865a609cb83bee7690",
            label = "aa-boot:",
            sigTemplate = "{build_id}:{key_group}:{referer_host}:{epoch}:{lane}",
            buildId = "75",
            keyGroup = "mkissa",
            refererHost = "mkissa.to",
            epoch = 6887,
            lane = "k7",
        )
        assertEquals("7eb17241a906398c25974817c90cbcf1ad9333ba5851161c5b2ac1c25f34e898", got)
    }

    @Test
    fun `x-aa-boot matches the 2026-09-09 client scheme`() {
        val got = crypto.signAaBoot(
            maskHex = "93bf9583f597adb57f0823c99532cdc02a359c1a0f04a8a6b935d1e34587a27b",
            label = "ld1faaOf3G:",
            sigTemplate = "{key_group}:{lane}:{epoch}:{referer_host}:{build_id}",
            buildId = "166",
            keyGroup = "mkissa",
            refererHost = "mkissa.to",
            epoch = 2957,
            lane = "k7",
        )
        assertEquals("59199a3a63e6c8cb88c9204aa2aa2321401005d5b0852d6a027bb7973d3e5474", got)
    }

    @Test
    fun `x-aa-boot matches the 2026-09-18 plus-joined scheme`() {
        val got = crypto.signAaBoot(
            maskHex = "3777df816330efb2095bfae2b7942b88183a73fc59532b629e4c0fd2f5a9f841",
            label = "3HZDdfe:",
            sigTemplate = "{build_id}+{lane}+{epoch}+{referer_host}+{key_group}",
            buildId = "174",
            keyGroup = "mkissa",
            refererHost = "mkissa.to",
            epoch = 2959,
            lane = "k7",
        )
        assertEquals("04cb708eea31ffee4d31b5b4b53b9c824c2bc78be740d36446d01be13c43e399", got)
    }

    // ── aaReq envelope shape ─────────────────────────────────────────────

    @Test
    fun `aaReq envelope decrypts back to the documented payload`() {
        val key = ByteArray(32) { 7 }
        val qh = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"
        val base = 1_784_600_000_001L
        val ts = base / (5L * 60_000L) * (5L * 60_000L)
        val token = requireNotNull(
            crypto.signAaReq(key, "{epoch}:{build_id}:{qh}:{ts}:{lane}", 6884, "75", qh, "k7", base),
        ) { "aaReq token expected" }
        val blob = Base64.getDecoder().decode(token)
        assertEquals(0x01, blob[0].toInt())

        val expectedNonce = java.security.MessageDigest.getInstance("SHA-256")
            .digest("6884:75:$qh:$ts:k7".toByteArray(Charsets.UTF_8))
        blob.copyOfRange(1, 13).forEachIndexed { i, b ->
            assertEquals(expectedNonce[i], b, "nonce byte $i")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, blob.copyOfRange(1, 13)),
        )
        val plaintext = cipher.doFinal(blob.copyOfRange(13, blob.size))
        assertEquals(
            """{"v":1,"ts":$ts,"epoch":6884,"buildId":"75","qh":"$qh","k":"k7"}""",
            String(plaintext, Charsets.UTF_8),
        )
    }

    // ── Config defaults ──────────────────────────────────────────────────

    @Test
    fun `config defaults come from the current constants`() {
        val cfg = AllAnimeProvider.Config()
        assertEquals("182", cfg.buildId)
        assertEquals("k7", cfg.episodeLane)
        assertEquals(604_800_000L, cfg.epochBucketMs)
    }


    @Test
    fun `clock links expose subtitle tracks`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val body = """{"links":[{"link":"https://a/x/play.m3u8","resolutionStr":"1080","subtitles":[
            {"src":"https://subs/a.vtt","lang":"en","label":"English"},
            {"url":"https://subs/b.srt","lang":"de"}
        ]}]}"""
        val link = json.decodeFromString<AllAnimeProvider.ClockResponse>(body).links.first()
        assertEquals(2, link.subtitles.size)
        assertEquals("https://subs/a.vtt", link.subtitles.first().src)
        assertEquals("English", link.subtitles.first().label)
        assertEquals("de", link.subtitles.last().lang)
    }

    // ── Clock link model ────────────────────────────────────────────────

    @Test
    fun `clock response parses links with resolutions`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val body = """{"links":[
            {"link":"https://a/x/play.m3u8","resolutionStr":"1080","hls":true},
            {"link":"https://a/embed/abc","resolutionStr":"auto"}
        ]}"""
        val links = json.decodeFromString<AllAnimeProvider.ClockResponse>(body).links
        assertEquals(2, links.size)
        assertEquals("1080", links.first().resolutionStr)
        assertEquals("https://a/embed/abc", links.last().link)
    }
}
