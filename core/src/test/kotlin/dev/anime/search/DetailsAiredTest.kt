package dev.anime.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailsAiredTest {
    @Test
    fun `next airing episode minus one is the aired count`() {
        assertEquals(8, airedEpisodeCount("RELEASING", 9))
        assertEquals(11, airedEpisodeCount("releasing", 12))
    }

    @Test
    fun `no aired count for the first episode or missing schedules`() {
        assertNull(airedEpisodeCount("RELEASING", 1))
        assertNull(airedEpisodeCount("RELEASING", null))
        assertNull(airedEpisodeCount("FINISHED", 9))
        assertNull(airedEpisodeCount(null, 9))
    }
}
