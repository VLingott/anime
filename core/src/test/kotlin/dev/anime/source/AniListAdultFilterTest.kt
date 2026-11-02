package dev.anime.source

import dev.anime.model.SearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AniListAdultFilterTest {
    private val source = AniListSource()

    private fun hit(title: String, genres: List<String>) = SearchHit(
        sourceId = "anilist",
        sourceName = "AniList",
        title = title,
        url = "https://anilist.co/anime/1",
        genres = genres,
    )

    @Test
    fun `hentai entries are dropped while adult content is hidden`() {
        val hits = listOf(
            hit("Frieren", listOf("Adventure", "Drama")),
            hit("Some Hentai", listOf("Hentai", "Romance")),
        )
        assertEquals(listOf("Frieren"), source.filterAdultHits(hits, includeAdult = false).map { it.title })
    }

    @Test
    fun `adult hits pass through when allowed`() {
        val hits = listOf(hit("Some Hentai", listOf("Hentai")))
        assertEquals(1, source.filterAdultHits(hits, includeAdult = true).size)
    }

    @Test
    fun `genre catalog is complete and marks the adult genre`() {
        val expected = setOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Hentai",
            "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi",
            "Slice of Life", "Sports", "Supernatural", "Thriller",
        )
        assertEquals(expected, AniListSource.GENRES.toSet())
        assertTrue(AniListSource.ADULT_GENRE in AniListSource.GENRES)
    }
}
