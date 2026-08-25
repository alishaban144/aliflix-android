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
            ),
        )

        assertEquals(EpisodeRatingsPresentation("8.9"), presentation)
    }

    @Test
    fun loadingUnavailableAndConfirmedNotRatedAreNotConflated() {
        assertEquals(
            EpisodeRatingsPresentation("Loading"),
            episodeRatingsPresentation(Episode(1, 1, "Pilot")),
        )
        assertEquals(
            EpisodeRatingsPresentation("Unavailable"),
            episodeRatingsPresentation(
                Episode(
                    seasonNumber = 1,
                    number = 1,
                    title = "Pilot",
                    imdbRatingState = RatingSourceState.UNAVAILABLE,
                ),
            ),
        )
        assertEquals(
            EpisodeRatingsPresentation("Not rated"),
            episodeRatingsPresentation(
                Episode(
                    seasonNumber = 1,
                    number = 1,
                    title = "Pilot",
                    imdbRatingState = RatingSourceState.NOT_RATED,
                ),
            ),
        )
    }
}
