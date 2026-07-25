package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankerTest {
    @Test
    fun `exact title beats a more popular prefix match`() {
        val results = SearchRanker.rank(
            "Matrix",
            listOf(
                media(1, "The Matrix Reloaded", rating = 9.9),
                media(2, "Matrix", rating = 1.0),
                media(3, "Matrix Revolutions", rating = 9.8),
            ),
        )

        assertEquals(2, results.first().id)
    }

    @Test
    fun `leading articles and accents do not weaken exact title matches`() {
        val articleResults = SearchRanker.rank(
            "dark knight",
            listOf(
                media(1, "Dark Knight Rises", rating = 9.5),
                media(2, "The Dark Knight", rating = 7.0),
            ),
        )
        val accentResults = SearchRanker.rank(
            "amelie",
            listOf(
                media(3, "Amelie Poulain", rating = 9.5),
                media(4, "Amélie", rating = 7.0),
            ),
        )

        assertEquals(2, articleResults.first().id)
        assertEquals(4, accentResults.first().id)
    }

    @Test
    fun `phrase prefix beats a scattered token match`() {
        val results = SearchRanker.rank(
            "star wars",
            listOf(
                media(1, "A New Hope - Star Wars"),
                media(2, "Star Wars Rebels"),
            ),
        )

        assertEquals(2, results.first().id)
    }

    @Test
    fun `all title tokens rank above partial token matches regardless of order`() {
        val results = SearchRanker.rank(
            "knight dark",
            listOf(
                media(1, "Knight and Day", rating = 9.8),
                media(2, "The Dark Knight", rating = 4.0),
                media(3, "Dark Waters", rating = 9.9),
            ),
        )

        assertEquals(2, results.first().id)
    }

    @Test
    fun `small spelling mistakes still find the intended title`() {
        val results = SearchRanker.rank(
            "interstelar",
            listOf(
                media(1, "International", rating = 9.9),
                media(2, "Interstellar", rating = 2.0),
                media(3, "Interceptor", rating = 9.8),
            ),
        )

        assertEquals(2, results.first().id)
    }

    @Test
    fun `trailing year selects the matching release`() {
        val results = SearchRanker.rank(
            "Dune 1984",
            listOf(
                media(1, "Dune", year = "2021"),
                media(2, "Dune", year = "1984-12-14"),
            ),
        )

        assertEquals(2, results.first().id)
    }

    @Test
    fun `movie and series qualifiers select the requested media type`() {
        val office = listOf(
            media(1, "The Office", type = MediaType.MOVIE),
            media(2, "The Office", type = MediaType.TV),
        )

        assertEquals(2, SearchRanker.rank("the office series", office).first().id)
        assertEquals(2, SearchRanker.rank("the office tv show", office).first().id)
        assertEquals(1, SearchRanker.rank("the office movie", office).first().id)
    }

    @Test
    fun `a year-looking word inside a title remains part of the title`() {
        val results = SearchRanker.rank(
            "2001 A Space Odyssey",
            listOf(
                media(1, "Space Odyssey 2001", rating = 9.9),
                media(2, "2001: A Space Odyssey", rating = 1.0),
            ),
        )

        assertEquals(2, results.first().id)
    }

    @Test
    fun `rating is minor and provider order is the final tie breaker`() {
        val relevanceResults = SearchRanker.rank(
            "Alien",
            listOf(
                media(1, "Alien Nation", rating = 10.0),
                media(2, "Alien", rating = 0.1),
            ),
        )
        val tiedResults = SearchRanker.rank(
            "Arrival",
            listOf(
                media(4, "Arrival", rating = 8.0),
                media(3, "Arrival", rating = 8.0),
            ),
        )

        assertEquals(2, relevanceResults.first().id)
        assertEquals(listOf(4, 3), tiedResults.map(Media::id))
    }

    @Test
    fun `blank queries leave provider order untouched`() {
        val source = listOf(
            media(2, "Second"),
            media(1, "First"),
        )

        assertEquals(source, SearchRanker.rank("   ", source))
    }

    private fun media(
        id: Int,
        title: String,
        type: MediaType = MediaType.MOVIE,
        year: String = "",
        rating: Double = 0.0,
    ): Media = Media(
        id = id,
        type = type,
        title = title,
        year = year,
        rating = rating,
    )
}
