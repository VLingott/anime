package dev.anime.search

import dev.anime.model.DetailResult
import dev.anime.source.TvmazeEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesSelectionTest {
    private fun detail(
        format: String?,
        episodes: Int?,
    ) = DetailResult(
        id = 376L,
        format = format,
        episodes = episodes,
    )

    private fun season(count: Int) = listOf(
        1 to (1..count).map { n -> TvmazeEpisode(season = 1, number = n) },
    )

    @Test
    fun `ova never borrows the parent tv series episode list`() {
        // The reported bug: AniList OVA with 1 episode matched the 13-episode TV show.
        assertFalse(SeriesRepository.acceptsEpisodes(detail("OVA", 1), season(13)))
        assertTrue(SeriesRepository.acceptsEpisodes(detail("OVA", 1), season(1)))
        assertFalse(SeriesRepository.acceptsEpisodes(detail("SPECIAL", 2), season(13)))
        assertTrue(SeriesRepository.acceptsEpisodes(detail("ONA", 10), season(10)))
    }

    @Test
    fun `ova without an episode count is never matched to tvmaze`() {
        assertFalse(SeriesRepository.acceptsEpisodes(detail("OVA", null), season(1)))
        assertFalse(SeriesRepository.acceptsEpisodes(detail("SPECIAL", null), season(3)))
        assertTrue(SeriesRepository.acceptsEpisodes(detail("TV", null), season(12)))
    }

    @Test
    fun `tv shows tolerate incomplete tvmaze lists but not a different show`() {
        assertTrue(SeriesRepository.acceptsEpisodes(detail("TV", 28), season(12)))
        assertTrue(SeriesRepository.acceptsEpisodes(detail("TV", 12), season(12)))
        assertTrue(SeriesRepository.acceptsEpisodes(detail("TV", 12), season(24)))
        assertFalse(SeriesRepository.acceptsEpisodes(detail("TV", 12), season(40)))
        assertFalse(SeriesRepository.acceptsEpisodes(detail("TV", 12), emptyList()))
    }

    @Test
    fun `synthetic seasons follow the AniList episode count`() {
        val one = SeriesRepository.syntheticSeasons(detail("OVA", 1))
        assertEquals(1, one.size)
        assertEquals(1, one.first().second.size)

        val twelve = SeriesRepository.syntheticSeasons(detail("TV", 12))
        assertEquals(1, twelve.size)
        assertEquals(12, twelve.first().second.size)

        assertTrue(SeriesRepository.syntheticSeasons(detail("TV", null)).isEmpty())
        assertTrue(SeriesRepository.syntheticSeasons(detail("TV", 0)).isEmpty())
    }
}
