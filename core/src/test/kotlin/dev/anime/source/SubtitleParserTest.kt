package dev.anime.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleParserTest {
    @Test
    fun `parses srt cues with comma timestamps`() {
        val cues = SubtitleParser.parse(
            """
            1
            00:00:01,500 --> 00:00:04,000
            Hello <i>there</i>

            2
            00:01:10,250 --> 00:01:12,000
            Second line
            continues here
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
        assertEquals("Hello there", cues[0].text)
        assertEquals(70_250L, cues[1].startMs)
        assertEquals("Second line\ncontinues here", cues[1].text)
    }

    @Test
    fun `parses webvtt with dot timestamps and headers`() {
        val cues = SubtitleParser.parse(
            """
            WEBVTT
            NOTE generated

            00:00:00.000 --> 00:00:02.500 align:middle
            {\an8}Top text

            00:00:02.500 --> 00:00:05.000
            Bottom
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals("Top text", cues[0].text)
        assertEquals(2_500L, cues[1].startMs)
    }

    @Test
    fun `ignores malformed blocks and reverse ranges`() {
        val cues = SubtitleParser.parse(
            """
            1
            not a time

            2
            00:00:05,000 --> 00:00:01,000
            backwards

            3
            00:00:06,000 --> 00:00:07,000
            ok
            """.trimIndent(),
        )
        assertEquals(1, cues.size)
        assertEquals("ok", cues.first().text)
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(SubtitleParser.parse("").isEmpty())
        assertTrue(SubtitleParser.parse("WEBVTT\n").isEmpty())
    }
}
