package dev.anime.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FiltersTest {
    @Test
    fun cyclesFromNeutralToIncludedToExcludedToNeutral() {
        var state = cycleFilter(emptySet(), emptySet(), "Isekai")
        assertEquals(setOf("Isekai"), state.first)
        assertEquals(emptySet(), state.second)

        state = cycleFilter(state.first, state.second, "Isekai")
        assertEquals(emptySet(), state.first)
        assertEquals(setOf("Isekai"), state.second)

        state = cycleFilter(state.first, state.second, "Isekai")
        assertEquals(emptySet(), state.first)
        assertEquals(emptySet(), state.second)
    }

    @Test
    fun togglingSecondValueKeepsFirstState() {
        val state = cycleFilter(setOf("Action"), setOf("Horror"), "Isekai")
        assertEquals(setOf("Action", "Isekai"), state.first)
        assertEquals(setOf("Horror"), state.second)
    }
}
