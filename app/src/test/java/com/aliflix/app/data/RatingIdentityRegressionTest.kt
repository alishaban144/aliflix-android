package com.aliflix.app.data

import com.aliflix.app.mergeStableMobileDetailUpdate
import com.aliflix.app.model.*
import com.aliflix.app.data.omdb.OmdbTitleMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RatingIdentityRegressionTest {
    private val movie = Media(1, MediaType.MOVIE, "Same Name", year = "2024", imdbId = "tt1234567")
    private fun repository(page: suspend (String) -> String = { error("Unexpected title search") }) = DefaultImdbRatingRepository(
        null, page, ImdbGraphQlTransport { _, _, _ -> """{"data":{"title":{
            "id":"tt1234567","titleText":{"text":"Same Name"},"releaseYear":{"year":2024},
            "titleType":{"id":"movie"},"ratingsSummary":{"aggregateRating":7.4,"voteCount":1000}}}}""" })

    @Test fun knownExternalIdDoesNotGetReplacedByPopularSameNameSuggestion() = runTest {
        val result = repository().ratingFor(movie)
        assertEquals("tt1234567", result.identity.imdbId)
        assertEquals(7.4, result.rating!!, .001)
    }

    @Test fun suggestionSearchRequiresReleaseYearEvenWhenWrongYearIsMorePopular() = runTest {
        val repo = repository { """{"d":[
            {"id":"tt9999999","l":"Same Name","qid":"movie","q":"feature","y":2023,"rank":1},
            {"id":"tt1234567","l":"Same Name","qid":"movie","q":"feature","y":2024,"rank":99999}]}""" }
        assertEquals("tt1234567", repo.resolveIdentity(movie.copy(imdbId = null))?.imdbId)
        assertNull(repo.resolveIdentity(movie.copy(year = "2022", imdbId = null)))
    }

    @Test fun cacheWithDifferentIdCannotPassOnMatchingTitleAndYear() {
        assertFalse(repository().cachedIdentityMatches(movie, ImdbRatingSnapshot(
            ImdbTitleIdentity("tt9999999", "Same Name", 2024, MediaType.MOVIE), 9.0, 100, RatingSourceState.VERIFIED)))
    }

    @Test fun recommendationRatingOnCanonicalPageIsNotTheTitleRating() {
        val html = """<link rel="canonical" href="https://www.imdb.com/title/tt1234567/">
            <script type="application/ld+json">{"@type":"Movie","name":"Different Movie",
            "url":"https://www.imdb.com/title/tt9999999/","datePublished":"2024-01-01",
            "aggregateRating":{"ratingValue":9.8}}</script>"""
        assertNull(repository().parseImdbPageRating(html, ImdbTitleIdentity("tt1234567", "Same Name", 2024, MediaType.MOVIE)))
    }

    @Test fun explicitRatingRefreshCanClearWrongPersistedValues() {
        val old = movie.copy(imdbRating = 9.8, rottenTomatoesRating = 99)
        val refreshed = old.mergeStableMobileDetailUpdate(movie.copy(
            imdbRatingState = RatingSourceState.UNAVAILABLE, rottenTomatoesState = RatingSourceState.UNAVAILABLE))
        assertNull(refreshed.imdbRating); assertNull(refreshed.rottenTomatoesRating)
    }

    @Test fun omdbCannotBypassYearValidationUsingACarriedWrongExternalId() {
        val wrong = OmdbTitleMetadata(found = true, imdbId = movie.imdbId, title = "Same Name", year = 2021,
            type = "movie", imdbRating = 9.9)
        assertNull(movie.mergeWithOmdb(wrong).imdbRating)
        assertEquals(9.9, movie.mergeWithOmdb(wrong.copy(year = 2024)).imdbRating!!, .001)
    }

    @Test fun rottenTomatoesCannotMatchAnUndatedRemakeOrAdjacentYear() {
        val client = RottenTomatoesClient { "" }
        fun html(year: String) = """<title>Same Name $year - Rotten Tomatoes</title>
            <link rel="canonical" href="https://www.rottentomatoes.com/m/same_name"><score-board tomatometerscore="99"/>"""
        assertFalse(client.isIdentityVerified(html(""), movie))
        assertFalse(client.isIdentityVerified(html("(2023)"), movie))
        assertTrue(client.isIdentityVerified(html("(2024)"), movie))
    }
}
