package dev.anime.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowsTest {
    private val elfenLiedOva =
        "Elfen Lied: Tooriame nite - Arui wa, Shoujo wa Ikani Shite Sono Shinjou ni Itatta ka?"

    @Test
    fun `exact and season-stripped titles are strong matches`() {
        assertEquals(4, Shows.titleScore("Elfen Lied", "Elfen Lied"))
        assertEquals(4, Shows.titleScore("Frieren: Beyond Journey's End", "Frieren: Beyond Journey's End"))
        assertEquals(3, Shows.titleScore("Sousou no Frieren", "Sousou no Frieren 2nd Season"))
        assertEquals(3, Shows.titleScore("Mob Psycho 100", "Mob Psycho 100 II"))
        assertEquals(3, Shows.titleScore("Attack on Titan", "Attack on Titan Part 2"))
    }

    @Test
    fun `ova side-story title is only a weak match against the parent show`() {
        // The bug: the OVA title starts with the TV show's name.
        assertEquals(1, Shows.titleScore("Elfen Lied", elfenLiedOva))
        // A TVmaze premiere a year off must not corroborate a weak match.
        assertFalse(Shows.acceptable(1, yearDistance = 1))
        assertFalse(Shows.acceptable(1, yearDistance = null))
        assertTrue(Shows.acceptable(1, yearDistance = 0))
    }

    @Test
    fun `unrelated and short titles never match`() {
        assertEquals(0, Shows.titleScore("Elfen Lied", "Naruto"))
        assertEquals(1, Shows.titleScore("Naruto", "Naruto: Shippuuden"))
        assertFalse(Shows.acceptable(1, yearDistance = 5))
        assertEquals(0, Shows.titleScore("Elf", "Elfin"))
        assertEquals(0, Shows.titleScore("", "Frieren"))
    }

    @Test
    fun `strong matches do not need a year check`() {
        assertTrue(Shows.acceptable(4, yearDistance = 3))
        assertTrue(Shows.acceptable(3, yearDistance = null))
        assertFalse(Shows.acceptable(0, yearDistance = 0))
    }
}
