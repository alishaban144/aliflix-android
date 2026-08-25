package com.aliflix.app.ui

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRatingsPresentationTest {
    private val title = Media(
        id = 238,
        type = MediaType.MOVIE,
        title = "The Godfather",
        rating = 9.0,
    )

    @Test
    fun `tmdb imdb and rt stay together while either external source is loading`() {
        val imdbFirst = title.copy(
            imdbRating = 9.2,
            imdbRatingState = RatingSourceState.VERIFIED,
            rottenTomatoesState = RatingSourceState.LOADING,
        )

        assertFalse(externalRatingsReady(imdbFirst))
        assertEquals(
            ExternalRatingsPresentation(
                imdb = "Loading...",
                rottenTomatoes = "Loading...",
                tmdb = "Loading...",
                loading = true,
            ),
            externalRatingsPresentation(imdbFirst),
        )
    }

    @Test
    fun `tmdb imdb and rt appear together when both external sources resolve`() {
        val resolved = title.copy(
            imdbRating = 9.2,
            imdbRatingState = RatingSourceState.VERIFIED,
            rottenTomatoesRating = 97,
            rottenTomatoesState = RatingSourceState.VERIFIED,
        )

        assertTrue(externalRatingsReady(resolved))
        assertEquals(
            ExternalRatingsPresentation(
                imdb = "9.2",
                rottenTomatoes = "97%",
                tmdb = "9.0",
                loading = false,
            ),
            externalRatingsPresentation(resolved),
        )
    }

    @Test
    fun `unavailable source does not leave the loader running`() {
        val resolved = title.copy(
            imdbRatingState = RatingSourceState.UNAVAILABLE,
            rottenTomatoesRating = 97,
            rottenTomatoesState = RatingSourceState.VERIFIED,
        )

        assertTrue(externalRatingsReady(resolved))
    }
}
