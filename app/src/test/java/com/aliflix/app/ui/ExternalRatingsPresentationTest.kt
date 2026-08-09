package com.aliflix.app.ui

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRatingsPresentationTest {
    private val title = Media(id = 238, type = MediaType.MOVIE, title = "The Godfather")

    @Test
    fun `ratings stay together while either source is loading`() {
        val imdbFirst = title.copy(
            imdbRating = 9.2,
            imdbRatingState = RatingSourceState.VERIFIED,
            rottenTomatoesState = RatingSourceState.LOADING,
        )

        assertFalse(externalRatingsReady(imdbFirst))
    }

    @Test
    fun `ratings appear together when both sources resolve`() {
        val resolved = title.copy(
            imdbRating = 9.2,
            imdbRatingState = RatingSourceState.VERIFIED,
            rottenTomatoesRating = 97,
            rottenTomatoesState = RatingSourceState.VERIFIED,
        )

        assertTrue(externalRatingsReady(resolved))
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
