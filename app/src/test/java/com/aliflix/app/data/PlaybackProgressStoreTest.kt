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
            source = PlaybackSource.moviepire("https://mirror.example/"),
        )

        assertEquals(playbackProgressKey(normal), playbackProgressKey(nativeMirror))
    }

    @Test
    fun progressSerializesAndDeserializesWithExactEpisodeIdentity() {
        val progress = progress(updatedAt = 1_234L, position = 600.0)

        assertEquals(progress, playbackProgressFromJson(progress.toJson()))
    }

    @Test
    fun lateAndShortProgressRemainsResumable() {
        for (position in listOf(0.5, 24.0, 919.0, 920.0, 950.0, 980.0)) {
            assertFalse(playbackCompleted(position, 1_000.0))
            assertTrue(progress(position = position).resumeEligible)
        }
        assertTrue(playbackCompleted(1_000.0, 1_000.0))
        assertFalse(progress(position = 1_000.0, completed = true).resumeEligible)
    }

    @Test
    fun newerZeroAndInvalidSnapshotsCannotEraseRealProgress() {
        val saved = progress(updatedAt = 1000, position = 980.0)
        val zero = progress(updatedAt = 2000, position = 0.0)
        val invalid = progress(updatedAt = 3000, position = Double.NaN)
        assertEquals(saved, mergePlaybackProgress(listOf(saved), listOf(zero, invalid)).single())
        val rewind = progress(updatedAt = 2000, position = 120.0)
        assertEquals(rewind, mergePlaybackProgress(listOf(saved), listOf(rewind)).single())
    }

    @Test
    fun legacyThresholdCompletionIsMigratedWithoutLosingPosition() {
        val old = progress(position = 980.0, completed = true).toJson().apply { remove("completionVersion") }
        val restored = playbackProgressFromJson(old)!!
        assertEquals(980.0, restored.positionSeconds, 0.0)
        assertTrue(restored.resumeEligible)
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
