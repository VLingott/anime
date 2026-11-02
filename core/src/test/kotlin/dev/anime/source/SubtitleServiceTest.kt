package dev.anime.source

import dev.anime.model.DetailResult
import dev.anime.model.DetailTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleServiceTest {
    private val service = SubtitleService()
    private fun detail(
        format: String?,
        year: Int?,
        english: String?,
        romaji: String? = null,
    ) = DetailResult(
        id = 1L,
        title = DetailTitle(romaji = romaji, english = english),
        format = format,
        seasonYear = year,
    )

    private fun suggestionOf(id: String, title: String, year: Int?, type: String?) =
        SubtitleService.Suggestion(id = id, l = title, y = year, qid = type)

    @Test
    fun `movie matches by title and year`() {
        val score = service.score(
            suggestionOf("tt9507276", "Saga of Tanya the Evil: The Movie", 2019, "movie"),
            detail("MOVIE", 2019, "Saga of Tanya the Evil - the Movie -"),
        )
        assertNotNull(score)
    }

    @Test
    fun `series matches with differing season suffix`() {
        val score = service.score(
            suggestionOf("tt6455986", "Saga of Tanya the Evil", 2017, "tvMiniSeries"),
            detail("TV", 2017, "Saga of Tanya the Evil"),
        )
        assertNotNull(score)
        assertNotNull(
            service.score(
                suggestionOf("tt6113488", "KonoSuba: God's Blessing on This Wonderful World!", 2016, "tvSeries"),
                detail("TV", 2017, "KONOSUBA -God's blessing on this wonderful world! 2"),
            ),
        )
    }

    @Test
    fun `wrong title or year is rejected`() {
        assertNull(
            service.score(
                suggestionOf("tt9507276", "Some Other Movie", 2019, "movie"),
                detail("MOVIE", 2019, "Saga of Tanya the Evil"),
            ),
        )
        assertNull(
            service.score(
                suggestionOf("tt9507276", "Saga of Tanya the Evil: The Movie", 1999, "movie"),
                detail("MOVIE", 2019, "Saga of Tanya the Evil - the Movie -"),
            ),
        )
        assertTrue("name" !in SubtitleService.ID_TYPES)
    }

    @Test
    fun `subtitle languages map to api codes and back`() {
        assertEquals("eng", SubtitleService.languageCode("English"))
        assertEquals("ger", SubtitleService.languageCode("German"))
        assertEquals("jpn", SubtitleService.languageCode("Japanese"))
        assertEquals("English", SubtitleService.languageName("eng"))
        assertEquals("German", SubtitleService.languageName("ger"))
    }
}
