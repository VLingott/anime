package dev.anime.source.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String =
    checkNotNull(AniWorldParserTest::class.java.getResourceAsStream("/$name")) { "missing $name" }
        .readBytes()
        .decodeToString()

class AniWorldParserTest {
    @Test
    fun parsesLanguageTargetsInPageOrder() {
        val languages = AniWorldParser.parseLanguages(fixture("aniworld-episode.html"))

        assertEquals(listOf(1, 2, 3), languages.keys.toList())
        assertEquals(
            listOf("/redirect/3780540", "/redirect/4116414", "/redirect/3948830", "/redirect/3413744"),
            languages[1],
        )
        assertEquals(4, languages[2]?.size)
        assertEquals(4, languages[3]?.size)
        assertEquals("/redirect/2441802", languages[3]?.first())
    }

    @Test
    fun parsesGermanAndEnglishEpisodeTitles() {
        val titles = AniWorldParser.episodeTitles(fixture("aniworld-episode.html"))

        assertEquals("Wo die Seelen ruhen", titles.german)
        assertEquals("The Land Where Souls Rest", titles.english)
    }

    @Test
    fun parsesSeasonsAndEpisodeNumbers() {
        val html = fixture("aniworld-series.html")

        assertEquals(listOf(1, 2), AniWorldParser.seasonNumbers(html))
        assertEquals(listOf(1, 2, 28), AniWorldParser.episodeNumbers(html, 1))
        assertEquals(listOf(1, 12), AniWorldParser.episodeNumbers(html, 2))
        assertEquals(emptyList(), AniWorldParser.episodeNumbers(html, 3))
    }

    @Test
    fun readsSeasonFromTitle() {
        assertEquals(3, AniWorldParser.titleSeason("Gintama Season 3"))
        assertEquals(2, AniWorldParser.titleSeason("Jujutsu Kaisen 2nd Season"))
        assertEquals(2, AniWorldParser.titleSeason("Attack on Titan Part 2"))
        assertEquals(1, AniWorldParser.titleSeason("Frieren: Beyond Journey's End"))
    }

    @Test
    fun decodesEntitiesAndDropsEpisodeSuffix() {
        assertEquals(
            "Tom & Jerry 'Special' \"x\" <y> A",
            AniWorldParser.decodeEntities("Tom &amp; Jerry &#039;Special&#039; &quot;x&quot; &lt;y&gt; &#65;"),
        )
        assertEquals("Pilot", AniWorldParser.cleanEpisodeTitle("Pilot [Episode 12]"))
        assertNull(AniWorldParser.cleanEpisodeTitle("   "))
    }
}

class VoeDecoderTest {
    @Test
    fun decodesCapturedApplicationJsonPayload() {
        val url = VoeDecoder.decodePage(fixture("voe.html"))

        checkNotNull(url)
        assertTrue(url.startsWith("https://"), url)
        assertTrue(url.contains("/master.m3u8"), url)
    }

    @Test
    fun ignoresMalformedPayload() {
        assertNull(VoeDecoder.decodePage("""<script type="application/json">["not a payload"]</script>"""))
        assertNull(VoeDecoder.decode("not a payload"))
    }

    @Test
    fun findsDirectPlaylistLink() {
        val html = """<script>var src = "https://cdn.example.test/hls/master.m3u8?token=abc";</script>"""

        assertEquals(
            "https://cdn.example.test/hls/master.m3u8?token=abc",
            VoeDecoder.findM3u8(html),
        )
        assertNull(VoeDecoder.findM3u8("no playlist here"))
    }
}
