package dev.anime.util

import dev.anime.source.TvmazeEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeOrderTest {
    private fun seasons(vararg specs: Pair<Int, IntArray>) = specs.map { (season, numbers) ->
        season to numbers.map { TvmazeEpisode(season = season, number = it) }
    }

    @Test
    fun `next episode stays inside the season`() {
        val list = seasons(1 to intArrayOf(1, 2, 3), 2 to intArrayOf(1, 2))
        assertEquals(EpisodePointer(1, 2, 2), nextEpisode(list, 1, 1))
        assertEquals(EpisodePointer(1, 3, 3), nextEpisode(list, 1, 2))
    }

    @Test
    fun `next episode crosses seasons with a continuous absolute index`() {
        val list = seasons(1 to intArrayOf(1, 2, 3), 2 to intArrayOf(1, 2))
        assertEquals(EpisodePointer(2, 1, 4), nextEpisode(list, 1, 3))
        assertEquals(EpisodePointer(2, 2, 5), nextEpisode(list, 2, 1))
    }

    @Test
    fun `no next episode at the end or for unknown positions`() {
        val list = seasons(1 to intArrayOf(1, 2))
        assertNull(nextEpisode(list, 1, 2))
        assertNull(nextEpisode(list, 9, 1))
        assertNull(nextEpisode(emptyList(), 1, 1))
    }

    @Test
    fun `absolute index matches episode pointers`() {
        val list = seasons(1 to intArrayOf(1, 2), 2 to intArrayOf(1, 2))
        val pointer = nextEpisode(list, 1, 1)!!
        assertEquals(pointer.absolute, absoluteEpisodeIndex(list, pointer.season, pointer.episode))
    }
}
