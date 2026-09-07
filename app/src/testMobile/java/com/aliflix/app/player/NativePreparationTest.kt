package com.aliflix.app.player

import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class NativePreparationTest {
    @Test fun triesVidThenMistWithoutConfusingSimilarNames() {
        val names = listOf("Peach", "Mistify", "Azute", "Flix", "Vidmux", "Mist", "Videasy", "Vid")
        val servers = names.mapIndexed { index, name -> MoviepireServerOption("$index", name, index == 1) }
        assertEquals(listOf("Vid", "Mist", "Mistify", "Flix", "Peach", "Azute", "Vidmux", "Videasy"), orderedNativeServers(servers).map { it.label })
        assertEquals(0, nativeServerRank("Vid • HD"))
        assertEquals(1, nativeServerRank("Mist - fast"))
    }
    @Test fun preferredProvidersReceiveTheExactMovieOrEpisodeRoute() {
        val movie = PlaybackSelection(Media(550, MediaType.MOVIE, "Movie"), source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru"))
        assertEquals(listOf("Vid", "Mist", "Mistify", "Flix", "Peach"), preferredNativeEmbeds(movie).map { it.first })
        assertTrue(preferredNativeEmbeds(movie).all { it.second.endsWith("/movie/550") })
        val episode = movie.copy(media = Media(1396, MediaType.TV, "Series"), seasonNumber = 2, episodeNumber = 3)
        assertTrue(preferredNativeEmbeds(episode).all { it.second.endsWith("/tv/1396/2/3") })
    }
    @Test fun preservesEpisodeIdentityAndQueueThroughNativeHandoff() {
        val selection = PlaybackSelection(Media(1396, MediaType.TV, "Series"), 3, 4, "Episode four",
            listOf(Episode(3, 4, "Episode four"), Episode(3, 5, "Episode five")), PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru"))
        val restored = nativeSelection(selection.nativeJson())
        assertEquals(selection.key, restored.key)
        assertEquals(selection.availableEpisodes, restored.availableEpisodes)
        assertEquals("Episode four", restored.episodeTitle)
    }
    @Test fun permitsTheActualVidOriginButNotLookalikeDomains() {
        assertTrue(nativePlaybackOriginRules("moviepire.ru").contains("https://*.wplay.me"))
        assertFalse(nativePlaybackOriginRules("moviepire.ru").contains("https://wplay.me.attacker.test"))
    }
}
