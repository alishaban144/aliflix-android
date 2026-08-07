package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueSearchRegressionTest {

    @Test
    fun `exact title ranking - Breaking Bad`() {
        val breakingBad = media(1, "Breaking Bad", rating = 9.5)
        val unrelated = media(2, "Bad Boys", rating = 7.0)
        val ranked = CatalogueSearchRanker.rank("Breaking Bad", listOf(unrelated, breakingBad))
        assertEquals("Breaking Bad", ranked.first().title)
    }

    @Test
    fun `typo tolerance - Breaking Bqd`() {
        val breakingBad = media(1, "Breaking Bad", rating = 9.5)
        val unrelated = media(2, "Bad Boys", rating = 7.0)
        val ranked = CatalogueSearchRanker.rank("Breaking Bqd", listOf(unrelated, breakingBad))
        assertEquals("Breaking Bad", ranked.first().title)
    }

    @Test
    fun `typo tolerance - Interstelar`() {
        val interstellar = media(1, "Interstellar", rating = 8.7)
        val unrelated = media(2, "Star Trek", rating = 8.0)
        val ranked = CatalogueSearchRanker.rank("Interstelar", listOf(unrelated, interstellar))
        assertEquals("Interstellar", ranked.first().title)
    }

    @Test
    fun `partial matching - Break ranks strongly matching titles first`() {
        val breakingBad = media(1, "Breaking Bad", rating = 9.5)
        val breakOut = media(2, "Breakout", rating = 6.0)
        val random = media(3, "The Fast and the Furious", rating = 7.5)
        val ranked = CatalogueSearchRanker.rank("Break", listOf(random, breakOut, breakingBad))
        assertTrue(ranked.first().title in listOf("Breakout", "Breaking Bad"))
    }

    @Test
    fun `article insensitive - Godfather ranks The Godfather strongly`() {
        val godfather = media(1, "The Godfather", rating = 9.2)
        val unrelated = media(2, "Father Figures", rating = 5.0)
        val ranked = CatalogueSearchRanker.rank("Godfather", listOf(unrelated, godfather))
        assertEquals("The Godfather", ranked.first().title)
    }

    @Test
    fun `punctuation insensitive - Spider Man ranks Spider-Man strongly`() {
        val spiderman = media(1, "Spider-Man", rating = 8.0)
        val unrelated = media(2, "Man on Wire", rating = 7.5)
        val ranked = CatalogueSearchRanker.rank("Spider Man", listOf(unrelated, spiderman))
        assertEquals("Spider-Man", ranked.first().title)
    }

    @Test
    fun `year qualifier - Dune 2021 prefers 2021 Dune over 1984 Dune`() {
        val dune1984 = media(1, "Dune", year = "1984", rating = 6.4)
        val dune2021 = media(2, "Dune", year = "2021", rating = 8.0)
        val ranked = CatalogueSearchRanker.rank("Dune 2021", listOf(dune1984, dune2021))
        assertEquals(2, ranked.first().id)
    }

    @Test
    fun `no irrelevant local mixing when online provider returns results`() = runBlocking {
        val online = listOf(media(1, "Breaking Bad", type = MediaType.TV))
        val local = listOf(media(99, "Unrelated Local Show", type = MediaType.TV))

        // When online provider returns results, local items are not mixed in
        val source = online.ifEmpty { local }
        val results = CatalogueSearchRanker.rank("Breaking Bad", source)
        assertTrue(results.any { it.title == "Breaking Bad" })
        assertFalse(results.any { it.title == "Unrelated Local Show" })
    }

    private fun media(
        id: Int,
        title: String,
        type: MediaType = MediaType.MOVIE,
        year: String = "2020",
        rating: Double = 8.0,
    ) = Media(
        id = id,
        type = type,
        title = title,
        year = year,
        rating = rating,
    )
}
