package com.aliflix.app.model

import com.aliflix.app.data.RamoflixConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaTest {
    @Test
    fun ramoflixBuildsTitleSearchEntryUrl() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")

        assertEquals(
            "https://ramoflix.net/?s=Inception",
            PlaybackSelection(item).entryUrl,
        )
    }

    @Test
    fun bcineBuildsOfficialMovieAndTvWatchUrl() {
        val movie = Media(id = 1083381, type = MediaType.MOVIE, title = "Inception")
        val movieSelection = PlaybackSelection(
            media = movie,
            source = PlaybackSource.bcine(),
        )

        val tv = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val tvSelection = PlaybackSelection(
            media = tv,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.bcine(),
        )

        assertEquals(
            "https://bcine.ru/movie/1083381",
            movieSelection.entryUrl,
        )
        assertEquals(
            "https://bcine.ru/tv/66732/2/3",
            tvSelection.entryUrl,
        )
    }

    @Test
    fun playbackSelectionKeysAreDistinctAcrossProviders() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val ramoflix = PlaybackSelection(
            media = item,
            source = PlaybackSource.ramoflix(),
        )
        val bcine = PlaybackSelection(
            media = item,
            source = PlaybackSource.bcine(),
        )

        assertNotEquals(ramoflix.key, bcine.key)
        assertEquals("movie:27205:via:ramoflix@ramoflix.net", ramoflix.key)
        assertEquals("movie:27205:via:bcine@bcine.ru", bcine.key)
    }

    @Test
    fun playbackSelectionKeyIncludesCustomRamoflixHost() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.ramoflix(
                RamoflixConfig("https://ramo-mirror.example/"),
            ),
        )

        assertEquals(
            "https://ramo-mirror.example/?s=Stranger+Things",
            selection.entryUrl,
        )
        assertEquals(
            "tv:66732:s2:e3:via:ramoflix@ramo-mirror.example",
            selection.key,
        )
    }

    @Test
    fun customBcineDomainBuildsMirrorWatchUrl() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val preferences = PlaybackPreferences(
            generalProvider = PlaybackProviderId.BCINE,
            bcineBaseUrl = "https://bcine-mirror.example/",
        )
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            source = preferences.sourceFor(item),
        )

        assertEquals(
            "https://bcine-mirror.example/tv/66732/2/3",
            selection.entryUrl,
        )
        assertEquals(
            "tv:66732:s2:e3:via:bcine@bcine-mirror.example",
            selection.key,
        )
    }

    @Test
    fun savedBcineChoiceIsUsedForPlayback() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val preferences = PlaybackPreferences(
            generalProvider = PlaybackProviderId.BCINE,
        )

        assertEquals(
            PlaybackProviderId.BCINE,
            preferences.sourceFor(item).provider,
        )
    }

    @Test
    fun dorabyBuildsMovieAndTvWatchUrl() {
        val movie = Media(id = 1083381, type = MediaType.MOVIE, title = "Descendants: Wicked Wonderland")
        val movieSelection = PlaybackSelection(
            media = movie,
            source = PlaybackSource.doraby(),
        )

        val tv = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val tvSelection = PlaybackSelection(
            media = tv,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.doraby(),
        )

        assertEquals(
            "https://doraby.com/descendants-wicked-wonderland/",
            movieSelection.entryUrl,
        )
        assertEquals(
            "https://doraby.com/stranger-things/",
            tvSelection.entryUrl,
        )
    }
}
