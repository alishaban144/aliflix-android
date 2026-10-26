package com.aliflix.app.player

import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class MobileResumePositionTest {
    private val media = Media(218, MediaType.MOVIE, "The Terminator")
    @Test fun watchedStatusDoesNotEraseAnUnfinishedResumePosition() {
        val progress = PlaybackProgress("movie:218", media, positionSeconds = 5500.0, durationSeconds = 6460.0,
            updatedAtMillis = 1, completed = true)
        assertEquals(5_500_000, mobileSavedResumeMs(progress))
        assertEquals(0, mobileSavedResumeMs(progress.copy(positionSeconds = 6460.0)))
        assertEquals(0, mobileSavedResumeMs(progress.copy(positionSeconds = 0.0, explicitlyRestarted = true)))
    }
    @Test fun providerAndServerDoNotChangeResumeIdentityButAnotherEpisodeDoes() {
        val first = PlaybackSelection(media)
        assertTrue(mobileSameContent(first, first.copy(source = PlaybackSource(PlaybackProviderId.CINEJOY, "https://cinejoy.test"))))
        val series = PlaybackSelection(media.copy(type = MediaType.TV), 1, 1)
        assertFalse(mobileSameContent(series, series.copy(episodeNumber = 2)))
    }
}
