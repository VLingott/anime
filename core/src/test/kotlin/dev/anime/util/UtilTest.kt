package dev.anime.util

import dev.anime.source.TvmazeEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilTest {
    @Test
    fun versionComparison() {
        assertTrue(isNewerVersion("1.0.0", "v1.0.1"))
        assertTrue(isNewerVersion("1.0.0", "1.1"))
        assertTrue(isNewerVersion("1.9.9", "2.0.0"))
        assertFalse(isNewerVersion("1.0.1", "v1.0.1"))
        assertFalse(isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun absoluteEpisodeIndexCountsAcrossSeasons() {
        val seasons = listOf(
            2 to listOf(TvmazeEpisode(2, 1), TvmazeEpisode(2, 2)),
            1 to listOf(TvmazeEpisode(1, 1), TvmazeEpisode(1, 2), TvmazeEpisode(1, 3)),
        )
        assertEquals(1, absoluteEpisodeIndex(seasons, 1, 1))
        assertEquals(3, absoluteEpisodeIndex(seasons, 1, 3))
        assertEquals(4, absoluteEpisodeIndex(seasons, 2, 1))
        assertEquals(5, absoluteEpisodeIndex(seasons, 2, 2))
        assertNull(absoluteEpisodeIndex(seasons, 3, 1))
    }
}

class TitleParsingTest {
    @Test
    fun detectsSeasonMarkers() {
        assertTrue(hasSeasonMarker("Gintama Season 3"))
        assertTrue(hasSeasonMarker("Jujutsu Kaisen 2nd Season"))
        assertTrue(hasSeasonMarker("Mob Psycho 100 II"))
        assertTrue(hasSeasonMarker("Attack on Titan Part 2"))
        assertFalse(hasSeasonMarker("Frieren: Beyond Journey's End"))
        assertFalse(hasSeasonMarker("Mob Psycho 100"))
    }

    @Test
    fun stripsSeasonMarkers() {
        assertEquals("Gintama", stripSeasonMarkers("Gintama Season 3"))
        assertEquals("Jujutsu Kaisen", stripSeasonMarkers("Jujutsu Kaisen 2nd Season"))
        assertEquals("Mob Psycho 100", stripSeasonMarkers("Mob Psycho 100 II"))
    }
}
