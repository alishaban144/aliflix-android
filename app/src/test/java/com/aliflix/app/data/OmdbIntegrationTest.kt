package com.aliflix.app.data.omdb

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmdbIntegrationTest {

    @Test
    fun testOmdbMetadataNormalization() {
        val json = org.json.JSONObject().apply {
            put("found", true)
            put("imdbId", "tt0068646")
            put("title", "The Godfather")
            put("year", 1972)
            put("type", "movie")
            put("genres", org.json.JSONArray(listOf("Crime", "Drama")))
            put("plot", "The aging patriarch...")
            put("runtimeMinutes", 175)
            put("director", "Francis Ford Coppola")
            put("actors", org.json.JSONArray(listOf("Marlon Brando", "Al Pacino")))
            put("imdbRating", 9.2)
            put("imdbVotes", 1900000)
            put("rottenTomatoesRating", 97)
            put("metascore", 100)
        }

        val meta = OmdbTitleMetadata.fromJson(json)
        assertTrue(meta.found)
        assertEquals("tt0068646", meta.imdbId)
        assertEquals("The Godfather", meta.title)
        assertEquals(1972, meta.year)
        assertEquals(175, meta.runtimeMinutes)
        assertEquals(listOf("Crime", "Drama"), meta.genres)
        assertEquals(9.2, meta.imdbRating!!, 0.01)
        assertEquals(1900000, meta.imdbVotes)
        assertEquals(97, meta.rottenTomatoesRating)
        assertEquals(100, meta.metascore)
    }

    @Test
    fun testMediaMergeWithOmdbPrecedence() {
        val baseMedia = Media(
            id = 238,
            type = MediaType.MOVIE,
            title = "The Godfather",
            overview = "Short overview",
            genres = listOf("Crime", "Drama"),
            year = "1972",
            rating = 8.7,
        )

        val omdb = OmdbTitleMetadata(
            found = true,
            imdbId = "tt0068646",
            title = "The Godfather",
            year = 1972,
            type = "movie",
            genres = listOf("Crime", "Drama"),
            plot = "Full epic crime plot...",
            runtimeMinutes = 175,
            imdbRating = 9.2,
            imdbVotes = 1900000,
            rottenTomatoesRating = 97,
            actors = listOf("Marlon Brando", "Al Pacino")
        )

        val merged = baseMedia.mergeWithOmdb(omdb)
        assertEquals("tt0068646", merged.imdbId)
        assertEquals(9.2, merged.imdbRating!!, 0.01)
        assertEquals(1900000, merged.imdbVoteCount)
        assertEquals(RatingSourceState.VERIFIED, merged.imdbRatingState)
        assertEquals(97, merged.rottenTomatoesRating)
        assertEquals(RatingSourceState.VERIFIED, merged.rottenTomatoesState)
        assertEquals("175 min", merged.runtime)
        assertEquals(listOf("Marlon Brando", "Al Pacino"), merged.cast)
        assertEquals("Full epic crime plot...", merged.omdbFullPlot)
    }

    @Test
    fun canonicalImdbIdRejectsMismatchedOmdbTitle() {
        val obsession = Media(
            id = 1_339_713,
            type = MediaType.MOVIE,
            title = "Obsession",
            year = "2026",
            rating = 8.2,
            imdbId = "tt37287335",
        )
        val wrongTitle = OmdbTitleMetadata(
            found = true,
            imdbId = "tt1234567",
            title = "Obsession",
            year = 2023,
            type = "movie",
            imdbRating = 6.2,
            rottenTomatoesRating = 41,
        )

        assertEquals(obsession, obsession.mergeWithOmdb(wrongTitle))
    }

    @Test
    fun testHardGenreValidationSpiderManRejection() {
        val requiredGenresNorm = listOf("horror")

        // Candidate A: Spider-Man (Action, Adventure, Sci-Fi)
        val spiderManGenres = listOf("Action", "Adventure", "Science Fiction")
        val spiderManOmdbGenres = listOf("Action", "Adventure", "Sci-Fi")
        val spiderManAvailable = (spiderManGenres + spiderManOmdbGenres).map { it.lowercase().trim() }.toSet()

        val satisfiesSpiderMan = requiredGenresNorm.all { req -> spiderManAvailable.contains(req) }
        assertFalse("Spider-Man must be REJECTED for Horror request", satisfiesSpiderMan)

        // Candidate B: The Shining (Horror, Drama)
        val shiningGenres = listOf("Horror", "Drama")
        val shiningAvailable = shiningGenres.map { it.lowercase().trim() }.toSet()

        val satisfiesShining = requiredGenresNorm.all { req -> shiningAvailable.contains(req) }
        assertTrue("The Shining must PASS Horror request", satisfiesShining)
    }
}
