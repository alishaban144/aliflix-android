package com.aliflix.app.player

import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PlaybackSourceFallbackTest {
    @Test fun everySourceFallsBackToBothOthersWithoutChangingEpisodeIdentity() {
        val generalProviders = listOf(PlaybackProviderId.CINEJOY, PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY, PlaybackProviderId.MOVIEPIRE, MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY)
        for (provider in PlaybackProviderId.entries + MobilePlaybackProvider.entries) {
            val preferences = PlaybackPreferences(dorabyBaseUrl = "https://doraby.example", moviepireBaseUrl = "https://moviepire.example")
            val episode = PlaybackSelection(Media(1396, MediaType.TV, "Breaking Bad"), 2, 3, "Bit by a Dead Bee",
                source = PlaybackSource(provider, "https://selected.example"))
            val choices = playbackSourceFallbacks(episode, preferences)
            assertEquals(episode, choices.first())
            // The selected source stays first, and every other general mirror is offered exactly once.
            assertEquals(
                (listOf(provider) + generalProviders.filter { it != provider }).toSet(),
                choices.map { it.source.identity }.toSet(),
            )
            choices.forEach {
                assertEquals(2, it.seasonNumber); assertEquals(3, it.episodeNumber)
                assertEquals(episode.media, it.media)
                assertEquals(playbackProgressKey(episode), playbackProgressKey(it))
                assertEquals("tv:1396:s2:e3", playbackProgressKey(it))
            }
            choices.drop(1).filter { it.source.identity == PlaybackProviderId.DORABY }.forEach { assertEquals("https://doraby.example", it.source.baseUrl) }
        }
    }
    @Test fun moviesShareProgressAcrossSourcesAndMirrors() {
        val movie = PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club"), source = PlaybackSource.doraby())
        assertEquals(setOf("movie:550"), playbackSourceFallbacks(movie, PlaybackPreferences()).map(::playbackProgressKey).toSet())
    }
    @Test fun cinejoyGetsALongerStartupBudgetThanOtherSources() {
        // CineJoy must boot its own bundle and answer its own API before it exposes a manifest.
        assertTrue(resolveBudgetMillis(PlaybackProviderId.CINEJOY) > resolveBudgetMillis(PlaybackProviderId.RAMOFLIX))
        assertTrue(resolveBudgetMillis(PlaybackProviderId.CINEJOY) > resolveBudgetMillis(PlaybackProviderId.MOVIEPIRE))
        // Every other provider keeps the established window.
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.RAMOFLIX))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.DORABY))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.MOVIEPIRE))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.MIRURO))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.ANIKURO))
    }
    @Test fun japaneseAnimeRacesBothAnimeNativeSourcesBeforeGeneralMirrors() {
        val anime = Media(21, MediaType.TV, "One Piece", genres = listOf("Animation"), originalLanguage = "ja")
        assertTrue(PlaybackProviderId.MIRURO.isAvailableFor(anime))
        assertTrue(PlaybackProviderId.ANIKURO.isAvailableFor(anime))
        assertFalse(PlaybackProviderId.ANIKURO.isAvailableFor(Media(550, MediaType.MOVIE, "Fight Club", originalLanguage = "en")))
        val selection = PlaybackSelection(anime, 1, 243, "Episode 243", source = PlaybackSource(PlaybackProviderId.ANIKURO))
        val choices = playbackSourceFallbacks(selection, PlaybackPreferences())
        assertEquals(
            listOf(
                PlaybackProviderId.ANIKURO,
                PlaybackProviderId.CINEJOY,
                PlaybackProviderId.MIRURO,
                PlaybackProviderId.RAMOFLIX,
                PlaybackProviderId.DORABY,
                PlaybackProviderId.MOVIEPIRE,
                MobilePlaybackProvider.SEVEN_MOVIES,
                MobilePlaybackProvider.MOVY,
            ),
            choices.map { it.source.identity },
        )
        assertEquals("https://anikuro.to/", choices.first().source.buildEntryUrl(anime))
        choices.forEach {
            assertEquals(anime, it.media)
            assertEquals("tv:21:s1:e243", playbackProgressKey(it))
        }
    }

    @Test fun freshCinejoyPlaybackIncludesConcurrentFallbacks() {
        val selection = PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club"), source = PlaybackSource(PlaybackProviderId.CINEJOY))
        val candidates = playbackSourceFallbacks(selection, PlaybackPreferences())
        assertEquals(listOf(candidates.first()), initialPlaybackRace(candidates))
    }

    @Test fun newSourcesPreserveExactEpisodeRoutes() {
        val series = Media(615, MediaType.TV, "Futurama")
        assertEquals("https://www.movy.sx/tv/615/2/3?play=true", PlaybackSource(MobilePlaybackProvider.MOVY).buildEntryUrl(series, 2, 3))
        assertEquals("https://7movies.ac/tv/615/watch?season=2&episode=3", PlaybackSource(MobilePlaybackProvider.SEVEN_MOVIES).buildEntryUrl(series, 2, 3))
        val episode = PlaybackSelection(series, 2, 3, source = PlaybackSource(MobilePlaybackProvider.SEVEN_MOVIES))
        val embeds = preferredNativeEmbeds(episode)
        assertEquals(5, embeds.size)
        assertEquals(episode.entryUrl, embeds.first().second)
        assertTrue(embeds.drop(1).all { it.second.contains("/tv/615/2/3") })
        assertTrue(embeds.map { it.second }.distinct().size == embeds.size)
        val movie = Media(550, MediaType.MOVIE, "Fight Club")
        assertEquals("https://www.movy.sx/movie/550?play=true", PlaybackSource(MobilePlaybackProvider.MOVY).buildEntryUrl(movie))
        assertEquals("https://7movies.ac/movie/550/watch", PlaybackSource(MobilePlaybackProvider.SEVEN_MOVIES).buildEntryUrl(movie))
    }

    @Test fun mobileSourcesSurviveNativeHandoffAndPreferenceRestore() {
        for (provider in MobilePlaybackProvider.entries) {
            val media = Media(615, MediaType.TV, "Futurama")
            val preferences = PlaybackPreferences(generalProvider = PlaybackProvider.valueOf(provider.name))
            val selection = PlaybackSelection(media, 2, 3, "Episode three", source = preferences.sourceFor(media))
            val restored = nativeSelection(selection.nativeJson())
            assertEquals(provider, preferences.effectiveGeneralProvider)
            assertEquals(provider, restored.source.identity)
            assertNull(restored.source.provider)
            assertEquals(selection.key, restored.key)
            assertEquals(selection.entryUrl, restored.entryUrl)
            assertEquals(playbackProgressKey(selection), playbackProgressKey(restored))
        }
    }

    @Test fun providerRaceStartsTwoAttemptsAndCleansUpTheLoser() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var loserClosed = false
        val winner = withTimeout(2_000) {
            firstSuccessful(listOf(
                suspend {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally { loserClosed = true }
                },
                suspend { started.await(); "playable" },
            ), parallelism = 2)
        }
        assertEquals("playable", winner)
        assertTrue(loserClosed)
    }
}
