package com.aliflix.app.player

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.MobilePlaybackProvider
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackSelection
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class IndianPlaybackCatalogueTest {
    @Test fun matchesExactTitleTypeAndYear() {
        val search = Jsoup.parse("""
            <div class="item-film"><div class="film-name"><a href="/movie/the-family-man-1/">The Family Man</a></div><div class="film-info"><span class="item">2019</span></div></div>
            <div class="item-film"><div class="film-name"><a href="/series/the-family-man-2/">The Family Man</a></div><div class="film-info"><span class="item">2019</span></div></div>
            <div class="item-film"><div class="film-name"><a href="/series/the-family-man-3/">The Family Man</a></div><div class="film-info"><span class="item">2025</span></div></div>
        """.trimIndent())
        val title = Media(1, MediaType.TV, "The Family Man", year = "2019")
        assertEquals("https://cinego.ws/series/the-family-man-2/", IndianPlaybackCatalogue.findTitle(search, "https://cinego.ws", title, false))
        assertNull(IndianPlaybackCatalogue.findTitle(search, "https://cinego.ws", title.copy(year = "2000"), false))
    }

    @Test fun prioritizesMegacloudAndSelectsExactEpisodeNumber() {
        val servers = Jsoup.parse("""
            <a class="link-item" data-id="first">UpCloud</a>
            <a class="link-item" data-id="backup">AKCloud</a>
            <a class="link-item" data-id="best">MegaCloud</a>
            <a class="eps-item" data-id="episode10" title="Eps 10: Finale"></a>
            <a class="eps-item" data-id="episode1" title="Eps 1: Pilot"></a>
        """.trimIndent())
        val links = IndianPlaybackCatalogue.parseHiMoviesServers(servers, "https://himovies.bz", "https://himovies.bz/movie/test")
        assertEquals(listOf("MegaCloud", "UpCloud", "AKCloud"), links.map { it.server })
        assertEquals("episode1", IndianPlaybackCatalogue.hiMoviesNumberedId(servers, ".eps-item", "Eps", 1))
        assertNull(IndianPlaybackCatalogue.hiMoviesNumberedId(servers, ".eps-item", "Eps", 2))
    }

    @Test fun defaultsIndianMoviesAndEnglishLanguageIndianSeriesToSpecialistSources() {
        val preferences = PlaybackPreferences()
        val titles = listOf(Media(1, MediaType.MOVIE, "Rana Vikrama", originalLanguage = "kn"),
            Media(2, MediaType.TV, "An Indian series", originalLanguage = "en", originCountries = listOf("IN")))
        for (media in titles) {
            val selection = PlaybackSelection(media, source = preferences.sourceFor(media))
            assertEquals(listOf(MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO),
                initialPlaybackRace(playbackSourceFallbacks(selection, preferences)).take(2).map { it.source.identity })
        }
    }

    @Test fun checksIndianFilmRuntimeAgainstPreviewsAndMismatchedMovies() {
        val movie = Media(691633, MediaType.MOVIE, "Rana Vikrama", originalLanguage = "kn", runtime = "147 min")
        val selection = PlaybackSelection(movie, source = PlaybackSource(MobilePlaybackProvider.HIMOVIES))
        val expected = indianNativeDurationRange(selection)
        assertThrows(IllegalStateException::class.java) { verifyNativeDuration(5_042L, expected) }
        assertThrows(IllegalStateException::class.java) { verifyNativeDuration(10_261_875L, expected) }
        verifyNativeDuration(141 * 60_000L, expected)
    }

    @Test fun stalledCatalogueDoesNotDelayNativeAlternatives() = runTest {
        val preferences = PlaybackPreferences()
        val media = Media(691633, MediaType.MOVIE, "Rana Vikrama", originalLanguage = "kn")
        val selection = PlaybackSelection(media, source = preferences.sourceFor(media))
        val candidates = initialPlaybackRace(playbackSourceFallbacks(selection, preferences))
        assertEquals(listOf(MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO, MobilePlaybackProvider.FLIXER),
            candidates.take(3).map { it.source.identity })
        val winner = withTimeout(500) {
            racePlaybackSources(candidates) { candidate ->
                if (candidate.source.identity == MobilePlaybackProvider.FLIXER) candidate else awaitCancellation()
            }
        }
        assertEquals(media, winner.media)
        assertEquals(MobilePlaybackProvider.FLIXER, winner.source.identity)
    }
}
