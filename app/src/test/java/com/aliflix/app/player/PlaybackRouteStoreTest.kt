package com.aliflix.app.player

import com.aliflix.app.model.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PlaybackRouteStoreTest {
    @Test fun successfulMovieAndEpisodeRoutesSurviveNewStoreInstances() {
        val directory = Files.createTempDirectory("routes").toFile()
        try {
            val movie = PlaybackSelection(Media(17, MediaType.MOVIE, "Same Name"))
            val series = PlaybackSelection(Media(17, MediaType.TV, "Same Name"), seasonNumber = 2, episodeNumber = 4)
            val next = series.copy(episodeNumber = 5)
            val request = NativePlaybackRequest("https://video.example/movie.m3u8", "application/x-mpegURL",
                "https://video.example/", "test", "session=x", "Same Name", 0, true)
            PlaybackRouteStore(directory) { 1000 }.save(movie, "Vid", request)
            PlaybackRouteStore(directory) { 1000 }.save(series, "Mist", request.copy(url = "https://video.example/episode.m3u8"))
            val reloaded = PlaybackRouteStore(directory) { 2000 }
            assertEquals("Vid", reloaded.load(movie)?.server)
            assertEquals("Mist", reloaded.load(series)?.server)
            assertEquals("session=x", reloaded.load(series)?.request?.cookie)
            assertNull(reloaded.load(next))
            val expired = PlaybackRouteStore(directory) { PlaybackRouteStore.DIRECT_STREAM_MAX_AGE_MS + 2000 }
            assertEquals("Mist", expired.load(series)?.server)
            assertNull(expired.load(series)?.request)
            reloaded.invalidateStream(movie)
            assertNull(reloaded.load(movie)?.request)
            assertEquals("Vid", reloaded.load(movie)?.server)
        } finally { directory.deleteRecursively() }
    }

    @Test fun playerLevelGestureCoversPortraitAndFullscreenSurfaces() {
        fun allowed(x: Float, y: Float, width: Float, height: Float) =
            playerLevelGestureAllowed(x, y, width, height, 28f)

        assertFalse(allowed(28f, 500f, 1000f, 1800f))
        assertTrue(allowed(29f, 500f, 1000f, 1800f))
        assertTrue(allowed(500f, 900f, 1000f, 1800f))
        assertTrue(allowed(971f, 1700f, 1000f, 1800f))
        assertFalse(allowed(500f, 1772f, 1000f, 1800f))

        assertFalse(allowed(40f, 28f, 1800f, 1000f))
        assertTrue(allowed(40f, 29f, 1800f, 1000f))
        assertTrue(allowed(900f, 500f, 1800f, 1000f))
        assertTrue(allowed(1759f, 971f, 1800f, 1000f))
        assertFalse(allowed(1772f, 500f, 1800f, 1000f))
    }

    @Test fun playerLevelGestureRespectsSystemInsets() {
        assertTrue(playerLevelGestureAllowed(50f, 500f, 1000f, 1800f, 28f, leftInset = 48f))
        assertFalse(playerLevelGestureAllowed(47f, 500f, 1000f, 1800f, 28f, leftInset = 48f))
        assertTrue(playerLevelGestureAllowed(500f, 60f, 1000f, 1800f, 28f, topInset = 48f))
        assertFalse(playerLevelGestureAllowed(500f, 47f, 1000f, 1800f, 28f, topInset = 48f))
    }
}
