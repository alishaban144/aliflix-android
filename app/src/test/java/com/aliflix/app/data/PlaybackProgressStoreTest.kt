package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressStoreTest {
    private val series = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")

    @Test
    fun differentEpisodesHaveDifferentContentKeys() {
        val episodeTwo = PlaybackSelection(series, seasonNumber = 2, episodeNumber = 2)
        val episodeThree = PlaybackSelection(series, seasonNumber = 2, episodeNumber = 3)

        assertEquals("tv:66732:s2:e2", playbackProgressKey(episodeTwo))
        assertEquals("tv:66732:s2:e3", playbackProgressKey(episodeThree))
    }

    @Test
    fun progressKeyDoesNotDependOnProviderOrStreamingServer() {
        val normal = PlaybackSelection(
            series,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.moviepire(),
        )
        val nativeMirror = normal.copy(
            source = PlaybackSource.moviepireNative("https://mirror.example/"),
        )

        assertEquals(playbackProgressKey(normal), playbackProgressKey(nativeMirror))
    }

    @Test
    fun progressSerializesAndDeserializesWithExactEpisodeIdentity() {
        val progress = progress(updatedAt = 1_234L, position = 600.0)

        assertEquals(progress, playbackProgressFromJson(progress.toJson()))
    }

    @Test
    fun completionAndResumeThresholdsAreDeterministic() {
        assertFalse(playbackCompleted(919.0, 1_000.0))
        assertTrue(playbackCompleted(920.0, 1_000.0))
        assertFalse(progress(position = 24.0).resumeEligible)
        assertTrue(progress(position = 25.0).resumeEligible)
        assertFalse(progress(position = 920.0, completed = true).resumeEligible)
    }

    @Test
    fun newestValidProgressWinsDuringCloudMerge() {
        val oldLocal = progress(updatedAt = 1_000L, position = 600.0)
        val newerCloud = progress(updatedAt = 2_000L, position = 720.0)

        assertEquals(newerCloud, mergePlaybackProgress(listOf(oldLocal), listOf(newerCloud)).single())
    }

    private fun progress(
        updatedAt: Long = 1_000L,
        position: Double = 600.0,
        completed: Boolean = false,
    ) = PlaybackProgress(
        key = "tv:66732:s2:e3",
        media = series,
        seasonNumber = 2,
        episodeNumber = 3,
        episodeTitle = "The Pollywog",
        positionSeconds = position,
        durationSeconds = 1_000.0,
        updatedAtMillis = updatedAt,
        completed = completed,
    )
}
