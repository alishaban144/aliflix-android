package com.aliflix.app.player

import com.aliflix.app.model.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class RamoflixNativeCatalogTest {
    private val movie = PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club"), source = PlaybackSource.ramoflix())
    private val tv = movie.copy(media = Media(1396, MediaType.TV, "Breaking Bad"), seasonNumber = 2, episodeNumber = 3)
    private val movieHtml = """<script id="servers-js-extra">var Servers = {"id":"550","embedru":"https://soap2night.cc/embed/movie/tt0137523","vidlink":"https://player.videasy.to/movie/550"};</script>
        <section id="servers"><li class="server" onclick="loadServer(embedru)">Vidsrc</li><li class="server" onclick="loadServer(vidlink)">Videasy</li></section>"""
    private val tvHtml = """<script id="episodes-js-extra">var Episodes = {"tvid":"1396","post_id":"468"};</script>
        <section id="servers"><li class="server" data-load-embed-host="Vidfast" data-load-season="1" data-load-episode="1">Vidsrc</li></section>"""

    @Test fun moviesUseOnlyAdvertisedServersForTheExactTmdbIdentity() {
        val embeds = ramoflixPageEmbeds(Jsoup.parse(movieHtml), movie)
        assertEquals(listOf("Vidsrc", "Videasy"), embeds.map { it.first })
        assertEquals("https://soap2night.cc/embed/movie/tt0137523", embeds.first().second)
        assertTrue(ramoflixPageEmbeds(Jsoup.parse(movieHtml), movie.copy(media = movie.media.copy(id = 551))).isEmpty())
        assertTrue(ramoflixPageEmbeds(Jsoup.parse(movieHtml), tv.copy(media = tv.media.copy(id = 550))).isEmpty())
    }

    @Test fun exactEpisodeOverridesTheSitesDefaultAndPreservesTheConfiguredMirror() {
        val selection = tv.copy(source = PlaybackSource(PlaybackProviderId.RAMOFLIX, "https://mirror.example/"))
        assertEquals("https://mirror.example/?player_tv=468&s=2&e=3&sv=Vidfast&tv=true",
            ramoflixPageEmbeds(Jsoup.parse(tvHtml), selection).single().second)
        assertTrue(ramoflixPageEmbeds(Jsoup.parse(tvHtml), movie).isEmpty())
    }

    @Test fun searchIgnoresNavigationAdvertisingAndDuplicatePostLinks() {
        val page = Jsoup.parse("""<a href="/category/movies/">Fight Club</a><div class="filmlist"><div class="item">
            <a href="/fight-club/">Poster</a><a href="/fight-club/">Fight Club</a>
            <a href="https://ramoflix.net.attacker.test/wrong/">Fight Club</a></div></div>""", "https://ramoflix.net/")
        assertEquals(listOf("https://ramoflix.net/fight-club/"), ramoflixTitleLinks(page, movie))
    }

    @Test fun nativeHandoffPreservesRamoflixEpisodeAndResumeIdentity() {
        val restored = nativeSelection(tv.nativeJson())
        assertEquals(tv.key, restored.key)
        assertEquals(PlaybackProviderId.RAMOFLIX, restored.source.provider)
        assertEquals(2, restored.seasonNumber)
        assertEquals(3, restored.episodeNumber)
    }
}
