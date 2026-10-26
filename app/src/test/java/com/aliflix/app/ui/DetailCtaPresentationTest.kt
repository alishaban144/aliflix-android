package com.aliflix.app.ui

import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class DetailCtaPresentationTest {
    private val series = Media(21, MediaType.TV, "One Piece", originalLanguage = "ja")
    private val movie = Media(550, MediaType.MOVIE, "Fight Club")

    @Test fun resumedSeriesNamesTheSeasonAndEpisodeItResumes() {
        assertEquals(
            "Resume S1E2",
            detailCtaText(series, Episode(1, 2, "They Call Him Straw Hat Luffy"), true),
        )
    }

    @Test fun unwatchedSeriesNamesTheEpisodeItWouldStart() {
        assertEquals("Play S8E243", detailCtaText(series, Episode(8, 243, "Episode 243"), false))
    }

    @Test fun seasonNumbersAboveNineAreNotPaddedOrTruncated() {
        assertEquals("Resume S11E405", detailCtaText(series, Episode(11, 405, "Episode 405"), true))
    }

    @Test fun moviesKeepThePlainActionBecauseTheyHaveNoEpisode() {
        assertEquals("Play", detailCtaText(movie, null, false))
        assertEquals("Resume", detailCtaText(movie, null, true))
    }

    @Test fun aSeriesWithoutAKnownEpisodeDegradesToThePlainAction() {
        assertEquals("Play", detailCtaText(series, null, false))
        assertEquals("Resume", detailCtaText(series, null, true))
    }

    @Test fun aSeriesNeverFallsBackToAnUnlabelledButton() {
        for (season in 1..22) {
            for (episode in listOf(1, 2, 99, 243, 405)) {
                val label = detailCtaText(series, Episode(season, episode, "Episode $episode"), episode % 2 == 1)
                assertTrue(label, label.startsWith(if (episode % 2 == 1) "Resume S" else "Play S"))
                assertTrue(label, label.endsWith("E$episode"))
            }
        }
    }
}
