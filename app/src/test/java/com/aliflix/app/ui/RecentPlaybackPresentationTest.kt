package com.aliflix.app.ui

import com.aliflix.app.DetailUiState
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPlaybackPresentationTest {
    private val series = Media(1405, MediaType.TV, "Dexter")

    @Test
    fun recentSeriesUsesNewestExactEpisodeAndItsOwnFraction() {
        val olderEpisode = progress(
            key = "tv:1405:s1:e7",
            season = 1,
            episode = 7,
            title = "Circle of Friends",
            position = 1_800.0,
            duration = 3_000.0,
            updatedAt = 1_000L,
        )
        val newestEpisode = progress(
            key = "tv:1405:s1:e8",
            season = 1,
            episode = 8,
            title = "Shrink Wrap",
            position = 600.0,
            duration = 2_400.0,
            updatedAt = 2_000L,
        )

        val presentation = recentPlaybackPresentation(
            item = series,
            playbackProgress = mapOf(
                olderEpisode.key to olderEpisode,
                newestEpisode.key to newestEpisode,
            ),
        )!!

        assertEquals(newestEpisode, presentation.progress)
        assertEquals(0.25, presentation.progress.progressFraction, 0.0001)
        assertEquals("S01  •  E08  •  Shrink Wrap", presentation.episodeDescription)
    }

    @Test
    fun progressFromAnotherTitleNeverLeaksIntoRecentCard() {
        val other = progress(
            key = "tv:66732:s2:e3",
            season = 2,
            episode = 3,
            title = "The Pollywog",
            position = 500.0,
            duration = 1_000.0,
            updatedAt = 3_000L,
            media = Media(66732, MediaType.TV, "Stranger Things"),
        )

        assertNull(recentPlaybackPresentation(series, mapOf(other.key to other)))
    }

    @Test
    fun playerEpisodeMenuStaysOnResumedSeasonWhenThatSeasonIsNotLoaded() {
        val selected = Episode(3, 7, "That Night, A Forest Grew")
        val menu = playerEpisodesFor(
            detail = DetailUiState(
                item = series,
                seasons = listOf(Season(1, "Season 1", 12), Season(3, "Season 3", 12)),
                selectedSeason = 1,
                episodes = listOf(Episode(1, 1, "Dexter")),
            ),
            selectedEpisode = selected,
        )

        assertEquals((1..12).toList(), menu.map(Episode::number))
        assertEquals(setOf(3), menu.map(Episode::seasonNumber).toSet())
        assertEquals(selected, menu.first { it.number == 7 })
    }

    @Test
    fun episodeRingAppearsForStartedAndCompletedPlaybackOnly() {
        val started = progress(
            key = "tv:1405:s1:e7",
            season = 1,
            episode = 7,
            title = "Circle of Friends",
            position = 12.0,
            duration = 3_000.0,
            updatedAt = 1_000L,
        )
        val completed = started.copy(positionSeconds = 3_000.0, completed = true)

        assertTrue(shouldShowPlaybackProgressRing(started))
        assertTrue(shouldShowPlaybackProgressRing(completed))
        assertFalse(shouldShowPlaybackProgressRing(started.copy(positionSeconds = 0.0)))
        assertFalse(shouldShowPlaybackProgressRing(null))
    }

    private fun progress(
        key: String,
        season: Int,
        episode: Int,
        title: String,
        position: Double,
        duration: Double,
        updatedAt: Long,
        media: Media = series,
    ) = PlaybackProgress(
        key = key,
        media = media,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = title,
        positionSeconds = position,
        durationSeconds = duration,
        updatedAtMillis = updatedAt,
        completed = false,
    )
}
