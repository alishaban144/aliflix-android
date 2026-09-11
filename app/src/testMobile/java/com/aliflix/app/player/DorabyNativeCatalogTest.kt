package com.aliflix.app.player

import com.aliflix.app.model.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DorabyNativeCatalogTest {
    private val movie = PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club"), source = PlaybackSource.doraby())
    private val episode = movie.copy(media = Media(1396, MediaType.TV, "Breaking Bad"), seasonNumber = 2, episodeNumber = 3)

    @Test fun searchesTheSelectedCatalogueInsteadOfGuessingTitleSlugs() {
        val selection = movie.copy(media = movie.media.copy(title = "Amélie & Friends"),
            source = PlaybackSource.doraby("https://doraby-mirror.example/"))
        assertEquals("https://doraby-mirror.example/?s=Am%C3%A9lie+%26+Friends", fmovieSearchUrl(selection))
        val page = Jsoup.parse("""<div class="filmlist"><div class="item"><a href="/different-canonical-slug/">Amélie</a>
            <a href="https://ramoflix.net/wrong/">Other source</a><a href="https://doraby-mirror.example.attacker.test/wrong/">Ad</a></div></div>""",
            selection.source.baseUrl)
        assertEquals(listOf("https://doraby-mirror.example/different-canonical-slug/"), fmovieTitleLinks(page, selection))
    }

    @Test fun readsDorabyServerNamesAndUrlsWithoutSubstitutingAnotherProvider() {
        val page = Jsoup.parse("""<script id="servers-js-extra">var Servers = {"id":"550","embedru":"https://vidfast.vc/movie/550","vidsrc":"https://111movies.com/movie/tt0137523?autoPlay=true","movieclub":"https://soap2night.cc/embed/movie/tt0137523"};</script>
            <section id="servers"><li class="server" onclick="loadServer(embedru)">Vidfast</li>
            <li class="server" onclick="loadServer(vidsrc)">111movies</li><li class="server" onclick="loadServer(movieclub)">Vidsrc</li></section>""")
        val embeds = fmoviePageEmbeds(page, movie)
        assertEquals(listOf("Vidfast", "111movies", "Vidsrc"), embeds.map { it.first })
        assertEquals("https://vidfast.vc/movie/550", embeds.first().second)
        assertTrue(fmoviePageEmbeds(page, movie.copy(media = movie.media.copy(id = 551))).isEmpty())
        assertTrue(fmoviePageEmbeds(page, episode.copy(media = episode.media.copy(id = 550))).isEmpty())
    }

    @Test fun resolvesTheExactEpisodeOnTheConfiguredDorabyDomain() {
        val page = Jsoup.parse("""<script id="episodes-js-extra">var Episodes = {"tvid":"1396","post_id":"468"};</script>
            <section id="servers"><li class="server" data-load-embed-host="Vidfast" data-load-season="1" data-load-episode="1">Vidfast</li></section>""")
        val selected = episode.copy(source = PlaybackSource.doraby("https://doraby-mirror.example/"))
        assertEquals("https://doraby-mirror.example/?player_tv=468&s=2&e=3&sv=Vidfast&tv=true",
            fmoviePageEmbeds(page, selected).single().second)
        assertTrue(fmoviePageEmbeds(page, movie).isEmpty())
        assertTrue(fmoviePageEmbeds(page, episode.copy(media = episode.media.copy(id = 1399))).isEmpty())
    }

    @Test fun handoffPreservesDorabyIdentityAndEpisodeQueue() {
        val selected = episode.copy(availableEpisodes = listOf(Episode(2, 3, "Bit by a Dead Bee"), Episode(2, 4, "Down")))
        val restored = nativeSelection(selected.nativeJson())
        assertEquals(selected.key, restored.key)
        assertEquals(selected.availableEpisodes, restored.availableEpisodes)
        assertEquals(PlaybackProviderId.DORABY, restored.source.provider)
        assertEquals(2, restored.seasonNumber)
        assertEquals(3, restored.episodeNumber)
    }

    @Test fun permitsDorabyAdvertisedVidfastOriginWithoutTrustingLookalikes() {
        val origins = nativePlaybackOriginRules("doraby.com")
        assertTrue(origins.contains("https://vidfast.vc"))
        assertFalse(origins.contains("https://vidfast.vc.attacker.test"))
    }
}
