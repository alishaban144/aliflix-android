package com.aliflix.app.ui

import com.aliflix.app.model.Episode
import com.aliflix.app.model.RatingSourceState
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeRatingsPresentationTest {
    @Test
    fun verifiedEpisodeRatingsAreDisplayedFromTheirOwnSources() {
        val presentation = episodeRatingsPresentation(
            Episode(
                seasonNumber = 1,
                number = 3,
                title = "Long, Long Time",
                imdbRating = 8.9,
                imdbRatingState = RatingSourceState.VERIFIED,
                rottenTomatoesRating = 98,
                rottenTomatoesState = RatingSourceState.VERIFIED,
            ),
        )

        assertEquals(EpisodeRatingsPresentation("8.9", "98%"), presentation)
    }

    @Test
    fun loadingUnavailableAndConfirmedNotRatedAreNotConflated() {
        assertEquals(
            EpisodeRatingsPresentation("Loading", "Loading"),
            episodeRatingsPresentation(Episode(1, 1, "Pilot")),
        )
        assertEquals(
            EpisodeRatingsPresentation("Unavailable", "Not rated"),
            episodeRatingsPresentation(
                Episode(
                    seasonNumber = 1,
                    number = 1,
                    title = "Pilot",
                    imdbRatingState = RatingSourceState.UNAVAILABLE,
                    rottenTomatoesState = RatingSourceState.NOT_RATED,
                ),
            ),
        )
    }

    @Test
    fun zeroPercentTomatometerIsStillARealVerifiedValue() {
        assertEquals(
            "0%",
            episodeRatingsPresentation(
                Episode(
                    seasonNumber = 1,
                    number = 1,
                    title = "Pilot",
                    rottenTomatoesRating = 0,
                    rottenTomatoesState = RatingSourceState.VERIFIED,
                ),
            ).rottenTomatoes,
        )
    }
}
