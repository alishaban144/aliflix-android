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
    fun `articles punctuation apostrophes and accents are normalized`() {
        assertFirst(
            query = "dark knight",
            expectedId = 2,
            media(1, "Dark Knight Rises", rating = 9.5),
            media(2, "The Dark Knight", rating = 1.0),
        )
        assertFirst(
            query = "amelie",
            expectedId = 4,
            media(3, "Amelie Poulain", rating = 9.5),
            media(4, "Amélie", rating = 1.0),
        )
        assertFirst(
            query = "spiderman",
            expectedId = 6,
            media(5, "Spiderhead", rating = 9.5),
            media(6, "Spider-Man", rating = 1.0),
        )
        assertFirst(
            query = "schindlers list",
            expectedId = 8,
            media(7, "The List", rating = 9.5),
            media(8, "Schindler's List", rating = 1.0),
        )
    }

    @Test
    fun `phrase prefix and base title beat a scattered or subtitle-only match`() {
        assertFirst(
            query = "star wars",
            expectedId = 2,
            media(1, "A New Hope - Star Wars", rating = 9.9),
            media(2, "Star Wars: Episode IV - A New Hope", rating = 1.0),
        )
        assertFirst(
            query = "mission impossible",
            expectedId = 4,
            media(3, "Impossible Missions", rating = 9.9),
            media(4, "Mission: Impossible - Fallout", rating = 1.0),
        )
    }

    @Test
    fun `word order can vary without allowing partial matches to win`() {
        assertFirst(
            query = "knight dark",
            expectedId = 2,
            media(1, "Knight and Day", rating = 9.8),
            media(2, "The Dark Knight", rating = 1.0),
            media(3, "Dark Waters", rating = 9.9),
        )
        assertFirst(
            query = "lord rings",
            expectedId = 5,
            media(4, "Lord of War", rating = 10.0),
            media(5, "The Lord of the Rings", rating = 1.0),
        )
    }

    @Test
    fun `acronyms predict well known multi word titles`() {
        assertFirst(
            query = "lotr",
            expectedId = 2,
            media(1, "Lottery Ticket", rating = 10.0),
            media(2, "The Lord of the Rings", rating = 1.0),
        )
        assertFirst(
            query = "mib",
            expectedId = 4,
            media(3, "Missing Boston", rating = 10.0),
            media(4, "Men in Black", rating = 1.0),
        )
        assertFirst(
            query = "t l o u",
            expectedId = 6,
            media(5, "The Love Between Us", rating = 10.0),
            media(6, "The Last of Us", rating = 1.0),
        )
    }

    @Test
    fun `roman numerals and regular digits are equivalent`() {
        assertFirst(
            query = "rocky 4",
            expectedId = 2,
            media(1, "Rocky III", rating = 10.0),
            media(2, "Rocky IV", rating = 1.0),
        )
        assertFirst(
            query = "godfather part 2",
            expectedId = 4,
            media(3, "The Godfather Part III", rating = 10.0),
            media(4, "The Godfather Part II", rating = 1.0),
        )
    }

    @Test
    fun `misspellings duplicate letters and adjacent transpositions are tolerated`() {
        assertFirst(
            query = "interstelar",
            expectedId = 2,
            media(1, "International", rating = 9.9),
            media(2, "Interstellar", rating = 1.0),
            media(3, "Interceptor", rating = 9.8),
        )
        assertFirst(
            query = "harry poter",
            expectedId = 5,
            media(4, "Harry and Tonto", rating = 10.0),
            media(5, "Harry Potter", rating = 1.0),
        )
        assertFirst(
            query = "spdier man",
            expectedId = 7,
            media(6, "Superman", rating = 10.0),
            media(7, "Spider-Man", rating = 1.0),
        )
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
    fun `year and media type filters work in either trailing order`() {
        val office = listOf(
            media(1, "The Office", type = MediaType.MOVIE, year = "1995"),
            media(2, "The Office", type = MediaType.TV, year = "2005"),
            media(3, "The Office", type = MediaType.TV, year = "2001"),
        )

        assertEquals(2, SearchRanker.rank("the office tv show 2005", office).first().id)
        assertEquals(2, SearchRanker.rank("the office 2005 series", office).first().id)
        assertEquals(1, SearchRanker.rank("the office 1995 feature film", office).first().id)
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
    fun `a year-looking word that is part of a literal title is protected`() {
        assertFirst(
            query = "2001 A Space Odyssey",
            expectedId = 2,
            media(1, "Space Odyssey 2001", year = "1968", rating = 10.0),
            media(2, "2001: A Space Odyssey", year = "1968", rating = 1.0),
        )
        assertFirst(
            query = "Blade Runner 2049",
            expectedId = 4,
            media(3, "Blade Runner", year = "2049", rating = 10.0),
            media(4, "Blade Runner 2049", year = "2017", rating = 1.0),
        )
        assertFirst(
            query = "Movie 43",
            expectedId = 6,
            media(5, "The 43", rating = 10.0),
            media(6, "Movie 43", rating = 1.0),
        )
    }

    @Test
    fun `intent API exposes normalized title year and type`() {
        val intent = SearchRanker.parseIntent("  Amélie (2001) MOVIE  ")

        assertEquals("amelie", intent.title)
        assertEquals("Amélie", intent.providerTitle)
        assertEquals(2001, intent.year)
        assertEquals(MediaType.MOVIE, intent.type)
    }

    @Test
    fun `provider title preserves words that look like roman numerals`() {
        assertEquals(
            "I Am Legend",
            SearchRanker.parseIntent("I Am Legend").providerTitle,
        )
        assertEquals(
            "V for Vendetta",
            SearchRanker.parseIntent("V for Vendetta").providerTitle,
        )
        assertEquals(
            "Rocky IV",
            SearchRanker.parseIntent("Rocky IV").providerTitle,
        )
        assertEquals(
            "Dune",
            SearchRanker.parseIntent("Dune — TV — 2021").providerTitle,
        )
    }

    @Test
    fun `a tiny reverse prefix does not become a strong prediction`() {
        assertEquals(
            SearchRanker.SearchConfidence.NONE,
            SearchRanker.confidence("upgraded", media(1, "Up")),
        )
    }

    @Test
    fun `confidence API distinguishes useful matches from noise and qualifier conflicts`() {
        assertEquals(
            SearchRanker.SearchConfidence.EXACT,
            SearchRanker.confidence("The Matrix", media(1, "The Matrix")),
        )
        assertEquals(
            SearchRanker.SearchConfidence.STRONG,
            SearchRanker.confidence("interstelar", media(2, "Interstellar")),
        )
        assertEquals(
            SearchRanker.SearchConfidence.NONE,
            SearchRanker.confidence("oz", media(3, "Frozen")),
        )
        assertEquals(
            SearchRanker.SearchConfidence.STRONG,
            SearchRanker.confidence(
                "Dune 1984",
                media(4, "Dune", year = "2021"),
            ),
        )
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

    private fun assertFirst(
        query: String,
        expectedId: Int,
        vararg items: Media,
    ) {
        assertEquals(
            expectedId,
            SearchRanker.rank(query, items.toList()).first().id,
        )
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
