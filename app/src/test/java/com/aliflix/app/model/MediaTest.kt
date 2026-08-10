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
    fun moviepireBuildsOfficialMovieAndTvWatchUrl() {
        val movie = Media(id = 1275779, type = MediaType.MOVIE, title = "Disclosure Day")
        val movieSelection = PlaybackSelection(
            media = movie,
            source = PlaybackSource.moviepire(),
        )

        val tv = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val tvSelection = PlaybackSelection(
            media = tv,
            seasonNumber = 2,
            episodeNumber = 3,
            source = PlaybackSource.moviepire(),
        )

        assertEquals(
            "https://moviepire.ru/watch/1275779",
            movieSelection.entryUrl,
        )
        assertEquals(
            "https://moviepire.ru/watch/66732?s=2&e=3",
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
        val moviepire = PlaybackSelection(
            media = item,
            source = PlaybackSource.moviepire(),
        )

        assertNotEquals(ramoflix.key, moviepire.key)
        assertEquals("movie:27205:via:ramoflix@ramoflix.net", ramoflix.key)
        assertEquals("movie:27205:via:moviepire@moviepire.ru", moviepire.key)
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
    fun customMoviepireDomainBuildsMirrorWatchUrl() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val preferences = PlaybackPreferences(
            generalProvider = PlaybackProviderId.MOVIEPIRE,
            moviepireBaseUrl = "https://moviepire-mirror.example/",
        )
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            source = preferences.sourceFor(item),
        )

        assertEquals(
            "https://moviepire-mirror.example/watch/66732?s=2&e=3",
            selection.entryUrl,
        )
        assertEquals(
            "tv:66732:s2:e3:via:moviepire@moviepire-mirror.example",
            selection.key,
        )
    }

    @Test
    fun savedMoviepireChoiceIsUsedForPlayback() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val preferences = PlaybackPreferences(
            generalProvider = PlaybackProviderId.MOVIEPIRE,
        )

        assertEquals(
            PlaybackProviderId.MOVIEPIRE,
            preferences.sourceFor(item).provider,
        )
    }

    @Test
    fun moviepireIsTheOnlyBetaPlaybackProvider() {
        assertEquals(
            listOf(PlaybackProviderId.MOVIEPIRE),
            PlaybackProviderId.entries.filter(PlaybackProviderId::isBeta),
        )
    }

    @Test
    fun legacyBcineProviderNamesMigrateToMoviepire() {
        listOf("BCINE", "Bcine", "bcine").forEach { storedValue ->
            assertEquals(
                storedValue,
                PlaybackProviderId.MOVIEPIRE,
                PlaybackProviderId.fromStoredValue(storedValue),
            )
        }
    }

    @Test
    fun tmdbDetailFieldsSurviveSavedStateRoundTrip() {
        val item = Media(
            id = 1396,
            type = MediaType.TV,
            title = "Breaking Bad",
            posterPath = "/poster.jpg",
            backdropPath = "/backdrop.jpg",
            year = "2008",
            rating = 8.9,
            tmdbVoteCount = 15_000,
            genres = listOf("Drama", "Crime"),
            cast = listOf("Bryan Cranston"),
            status = "Ended",
            originalLanguage = "en",
            creators = listOf(
                MediaCreator(
                    tmdbId = 66633,
                    name = "Vince Gilligan",
                    profilePath = "/vince.jpg",
                ),
            ),
        )

        val restored = Media.fromJson(item.toJson())

        assertEquals(item, restored)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", restored.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w185/vince.jpg", restored.creators.single().profileUrl)
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
