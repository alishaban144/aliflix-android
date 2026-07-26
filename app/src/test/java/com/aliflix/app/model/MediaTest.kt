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
    fun movies67BuildsOfficialMovieWatchUrl() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val selection = PlaybackSelection(
            media = item,
            source = PlaybackSource.movies67(),
        )

        assertEquals(
            "https://67movies.nl/watch/movie/27205",
            selection.entryUrl,
        )
    }

    @Test
    fun movies67BuildsOfficialTvSeasonEpisodeWatchUrl() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.movies67(),
        )

        assertEquals(
            "https://67movies.nl/watch/tv/66732/2/3",
            selection.entryUrl,
        )
    }

    @Test
    fun playbackSelectionKeysAreDistinctAcrossProviders() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val ramoflix = PlaybackSelection(
            media = item,
            source = PlaybackSource.ramoflix(),
        )
        val movies67 = PlaybackSelection(
            media = item,
            source = PlaybackSource.movies67(),
        )

        assertNotEquals(ramoflix.key, movies67.key)
        assertEquals("movie:27205:via:ramoflix@ramoflix.net", ramoflix.key)
        assertEquals("movie:27205:via:movies_67@67movies.nl", movies67.key)
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
    fun customMovies67DomainBuildsMirrorWatchUrl() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val preferences = PlaybackPreferences(
            generalProvider = PlaybackProviderId.MOVIES_67,
            movies67BaseUrl = "https://67-mirror.example/",
        )
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            source = preferences.sourceFor(item),
        )

        assertEquals(
            "https://67-mirror.example/watch/tv/66732/2/3",
            selection.entryUrl,
        )
        assertEquals(
            "tv:66732:s2:e3:via:movies_67@67-mirror.example",
            selection.key,
        )
    }

    @Test
    fun savedMovies67ChoiceIsUsedForPlayback() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val preferences = PlaybackPreferences(
            generalProvider = PlaybackProviderId.MOVIES_67,
        )

        assertEquals(
            PlaybackProviderId.MOVIES_67,
            preferences.sourceFor(item).provider,
        )
    }

    @Test
    fun rivestreamBuildsDetailUrlForMovieAndTv() {
        val movie = Media(id = 1273221, type = MediaType.MOVIE, title = "Example Movie")
        val tv = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")

        val movieSelection = PlaybackSelection(
            media = movie,
            source = PlaybackSource.rivestream(),
        )
        val tvSelection = PlaybackSelection(
            media = tv,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.rivestream(),
        )

        assertEquals(
            "https://www.rivestream.app/detail?type=movie&id=1273221",
            movieSelection.entryUrl,
        )
        assertEquals(
            "https://www.rivestream.app/detail?type=tv&id=66732&season=2&episode=3",
            tvSelection.entryUrl,
        )
    }
}
