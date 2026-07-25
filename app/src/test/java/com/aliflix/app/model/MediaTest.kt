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
    fun movies67BuildsDirectVidloveMovieUrl() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val selection = PlaybackSelection(
            media = item,
            source = PlaybackSource.movies67(),
        )

        assertEquals(
            "https://player.vidlove.cc/embed/movie/27205" +
                "?autoplay=true&poster=true&chromecast=true&servericon=true" +
                "&setting=true&pip=true&font=Roboto&fontcolor=ffffff&fontsize=20" +
                "&opacity=0.5&primarycolor=ffffff&secondarycolor=ffffff" +
                "&iconcolor=ffffff&server=Dark",
            selection.entryUrl,
        )
    }

    @Test
    fun movies67BuildsDirectVidloveTvSeasonEpisodeUrl() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.movies67(),
        )

        assertEquals(
            "https://player.vidlove.cc/embed/tv/66732/2/3" +
                "?autoplay=true&poster=true&chromecast=true&servericon=true" +
                "&setting=true&pip=true&font=Roboto&fontcolor=ffffff&fontsize=20" +
                "&opacity=0.5&primarycolor=ffffff&secondarycolor=ffffff" +
                "&iconcolor=ffffff&server=Dark",
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
}
