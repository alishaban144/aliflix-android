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

    @Test fun portraitEdgesCannotBecomeVolumeOrBrightnessGestures() {
        fun allowed(x: Float, landscape: Boolean = false) = playerLevelGestureAllowed(x, 250f, 1000f,
            0f, 0f, 1000f, 500f, 28f, landscape)
        assertFalse(allowed(0f)); assertFalse(allowed(149f)); assertFalse(allowed(150f))
        assertTrue(allowed(151f)); assertTrue(allowed(500f)); assertTrue(allowed(849f))
        assertFalse(allowed(850f)); assertFalse(allowed(999f))
        assertTrue(allowed(100f, landscape = true))
        assertFalse(playerLevelGestureAllowed(500f, 20f, 1000f, 0f, 0f, 1000f, 500f, 28f, false))
    }
}
