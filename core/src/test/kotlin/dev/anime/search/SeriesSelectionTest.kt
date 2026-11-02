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
        episodesAired: Int? = null,
    ) = DetailResult(
        id = 376L,
        format = format,
        episodes = episodes,
        episodesAired = episodesAired,
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
    fun `releasing shows use the aired count instead of the planned one`() {
        // 12 planned, 8 out: the list and the check must target 8.
        assertEquals(8, SeriesRepository.run { detail("TV", 12, episodesAired = 8).expectedEpisodes() })
        assertEquals(8, SeriesRepository.syntheticSeasons(detail("TV", 12, episodesAired = 8)).first().second.size)
        assertTrue(SeriesRepository.acceptsEpisodes(detail("ONA", 12, episodesAired = 8), season(8)))
        assertFalse(SeriesRepository.acceptsEpisodes(detail("ONA", 12, episodesAired = 8), season(12)))

        // Finished shows keep the planned count when no aired count exists.
        assertEquals(12, SeriesRepository.run { detail("TV", 12).expectedEpisodes() })
        assertEquals(12, SeriesRepository.run { detail("TV", 12, episodesAired = 0).expectedEpisodes() })
    }


    @Test
    fun `schedules past the aired count are dropped`() {
        val airing = detail("TV", 12, episodesAired = 5)
        val trimmed = SeriesRepository.capToAired(
            airing,
            listOf(
                1 to (1..4).map { n -> TvmazeEpisode(season = 1, number = n) },
                2 to (1..3).map { n -> TvmazeEpisode(season = 2, number = n) },
            ),
        )
        assertEquals(5, trimmed.sumOf { it.second.size })
        assertEquals(1, trimmed.last().second.size)

        // Finished/unknown shows pass through untouched.
        val finished = detail("TV", 12)
        val all = listOf(1 to (1..12).map { n -> TvmazeEpisode(season = 1, number = n) })
        assertEquals(12, SeriesRepository.capToAired(finished, all).sumOf { it.second.size })
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
