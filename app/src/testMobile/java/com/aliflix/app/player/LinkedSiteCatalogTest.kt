package com.aliflix.app.player

import com.aliflix.app.model.*
import com.aliflix.app.downloads.downloadProviderOrder
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class LinkedSiteCatalogTest {
    @Test fun canonicalAnimeIdentitySurvivesTheSitesEarlierCatalogueYear() {
        val page=Jsoup.parse("""<h1><span>Black Clover</span></h1><a href="/animes/jahr/2016">2016</a><a data-imdb="tt7441658"></a>""")
        val media=Media(73223,MediaType.TV,"Black Clover",year="2017",imdbId="tt7441658")
        assertTrue(aniworldTitleMatches(page,media))
        assertFalse(aniworldTitleMatches(page,media.copy(imdbId="tt9999999")))
        assertFalse(aniworldTitleMatches(page,media.copy(imdbId=null)))
    }
    @Test fun changedSearchSchemasCannotCertifyThatAWorkingSiteLacksTheTitle() {
        assertEquals(0,filmoSearchMovies("""{"movies":[]}""").length())
        assertThrows(IllegalArgumentException::class.java) { filmoSearchMovies("""{"results":[]}""") }
        assertTrue(serienstreamSearchProvesAbsence(Jsoup.parse("""<div class="search-results"><div class="results-group" data-group="shows">Keine Serien gefunden</div></div>""")))
        assertFalse(serienstreamSearchProvesAbsence(Jsoup.parse("""<h1>Checking browser</h1>""")))
    }
    @Test fun externalPlayerDiscoveryIsGrantedOnlyToTheTemporaryExactHttpsOriginAndNeverTv() {
        assertEquals("https://player.example",trustedNativeEmbedOrigin("https://player.example:443/e/movie",true,false))
        assertNull(trustedNativeEmbedOrigin("https://player.example/e/movie",false,false))
        assertNull(trustedNativeEmbedOrigin("https://player.example/e/movie",true,true))
        for(url in listOf("http://player.example/e/movie","https://user@player.example/e/movie","https://player.example:8443/e/movie"))
            assertNull(trustedNativeEmbedOrigin(url,true,false))
    }
    @Test fun ownerBlockedMirrorsAreNotAdvertisedAsPlayable() {
        val page=Jsoup.parse("""<div class="provider-row"><span class="provider-row__lang">English</span>
            <div data-provider-chip data-p="good"><span class="provider-chip__name">VOE</span></div>
            <div data-provider-chip data-p="blocked"><span class="provider-chip__name">Byse</span></div></div>""","https://filmo.to/movies/interstellar")
        assertEquals(listOf("VOE"),filmoPlaybackLinks(page).map { it.server })
    }
    @Test fun filmoVoePublicFileRedirectIsAcceptedWithoutAdmittingOtherRootDocuments() {
        for(url in listOf("https://voe.sx/abc123?default_audio_language=en","https://voe.sx/e/abc123","https://megankiddifference.com/e/abc123","https://megankiddifference.com/engine/opaque-public-player-token"))
            assertTrue(url,isProviderSiteRedirect(java.net.URI(url)))
        for(url in listOf("https://ads.example/abc123","https://ads.example/e/abc123","http://voe.sx/abc123","https://voe.sx:8080/abc123","https://user@voe.sx/abc123"))
            assertFalse(url,isProviderSiteRedirect(java.net.URI(url)))
    }
    @Test fun filmoRequiresExactTitleAndReleaseYearAndKeepsOpaqueActionsOnItsOrigin() {
        val page=Jsoup.parse("""<h1>Interstellar</h1><span class="ft-meta-label">2014</span>
            <div class="provider-row"><span class="provider-row__lang">English</span>
            <div data-provider-chip data-p="opaque" class="provider-chip"><span class="provider-chip__name">VOE</span></div></div>
            <div class="provider-row"><span class="provider-row__lang">Deutsch</span>
            <div data-provider-chip data-p="opaque-de"><span class="provider-chip__name">VOE</span></div></div>""","https://filmo.to/movies/interstellar")
        val movie=Media(157336,MediaType.MOVIE,"Interstellar",year="2014")
        assertTrue(filmoTitleMatches(page,movie));assertFalse(filmoTitleMatches(page,movie.copy(year="2015")))
        assertFalse(filmoTitleMatches(page,movie.copy(title="Interstellar 2")))
        val links=filmoPlaybackLinks(page)
        assertEquals(listOf("en","de"),links.map { it.language });assertEquals("opaque-de",links[1].token)
        assertTrue(links.all { it.url==page.location() })
    }
    @Test fun aniworldDoesNotConfuseJapaneseHardsubVariantsWithGermanDubOrSupportLinks() {
        val page=Jsoup.parse("""<a data-imdb="tt7441658"></a><div class="hosterSiteVideo"><ul>
            <li data-lang-key="1" data-link-target="/redirect/11"><h4>VOE</h4></li>
            <li data-lang-key="2" data-link-target="/redirect/12"><h4>VOE</h4></li>
            <li data-lang-key="3" data-link-target="/redirect/13"><h4>Filemoon</h4></li>
            <li data-lang-key="1" data-link-target="https://other.example/redirect/14"><h4>VOE</h4></li>
            </ul></div>""","https://aniworld.to/anime/stream/black-clover/staffel-1/episode-1")
        val media=Media(73223,MediaType.TV,"Black Clover",imdbId="tt7441658")
        assertTrue(aniworldTitleMatches(page,media));assertFalse(aniworldTitleMatches(page,media.copy(imdbId="tt1111111")))
        val links=aniworldPlaybackLinks(page)
        assertEquals(listOf("de","ja-en","ja-de"),links.map { it.language })
        assertEquals("Japanese · German subtitles",links.last().audioLabel)
        assertEquals("ja",resolvedDialogueLanguage("und",links.last().language))
    }
    @Test fun initialSourcesAreContentSpecificWithoutBlockingFallback() {
        val preferences=PlaybackPreferences(generalProvider=PlaybackProviderId.CINEJOY)
        val cases=listOf(Media(1,MediaType.MOVIE,"Movie") to MobilePlaybackProvider.FILMO,
            Media(2,MediaType.TV,"Series") to MobilePlaybackProvider.SERIENSTREAM,
            Media(3,MediaType.TV,"Anime",originalLanguage="ja",genres=listOf("Animation")) to MobilePlaybackProvider.ANIWORLD)
        for((media,provider) in cases) {
            assertEquals(provider,preferences.sourceFor(media).identity)
            val selection=PlaybackSelection(media,source=preferences.sourceFor(media))
            assertEquals(provider,initialPlaybackRace(playbackSourceFallbacks(selection,preferences)).first().source.identity)
            assertTrue(initialPlaybackRace(playbackSourceFallbacks(selection,preferences)).size>1)
            assertEquals(provider,downloadProviderOrder(selection).first())
            assertEquals(PlaybackProviderId.CINEJOY,preferences.sourceFor(media,PlaybackProviderId.CINEJOY).identity)
        }
    }
    @Test fun nativeEpisodeHandoffKeepsVerifiedImdbMetadata() {
        val episode=Episode(3,7,"An episode",imdbId="tt1234567",imdbRating=8.7,imdbVoteCount=1234,imdbRatingState=RatingSourceState.VERIFIED)
        val selection=PlaybackSelection(Media(2,MediaType.TV,"Series"),3,7,availableEpisodes=listOf(episode))
        assertEquals(episode,nativeSelection(selection.nativeJson()).availableEpisodes.single())
    }
}
