package com.aliflix.app.player

import com.aliflix.app.model.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SerienstreamNativeCatalogTest {
    @Test fun capturesTheVerifiedVoeMirrorWithoutAcceptingAdvertisingOrInsecureFrames() {
        assertEquals("https://voe.sx/e/abc123", canonicalVoeEmbed("https://megankiddifference.com/e/abc123?view=1"))
        assertNull(canonicalVoeEmbed("https://advert.example/e/abc123"))
        assertNull(canonicalVoeEmbed("http://voe.sx/e/abc123"))
        assertNull(canonicalVoeEmbed("https://user@voe.sx/e/abc123"))
    }
    @Test fun parsesOnlyEpisodeVideoHostsAndKeepsLanguagesSeparate() {
        val page = Jsoup.parse("""
            <div id="episode-links">
              <button data-provider-name="VOE" data-language-id="1" data-play-url="/r?t=german"></button>
              <button data-provider-name="VOE" data-language-id="2" data-play-url="/r?t=english"></button>
              <button data-provider-name="Doodstream" data-language-id="2" data-play-url="/r?t=other"></button>
              <button data-provider-name="VOE" data-language-id="1" data-play-url="https://advert.example/r?t=bad"></button>
            </div><iframe src="https://advert.example/"></iframe>
        """, "https://serienstream.to/serie/pantheon/staffel-1/episode-1")
        val links = serienstreamLinks(page)
        assertEquals(listOf("de", "en"), links.map { it.language })
        assertEquals(listOf("German · VOE", "English · VOE"), links.map { it.label })
        assertTrue(links.all { it.url.startsWith("https://serienstream.to/r?") })
    }

    @Test fun titleIdentityRejectsRemakesAndImdbMismatches() {
        val media = Media(70523, MediaType.TV, "Dark", year = "2017", imdbId = "tt5753856", originalLanguage = "de")
        val title = Jsoup.parse("<h1>Dark</h1><a href='/jahr/2017'>2017</a><a href='https://www.imdb.com/title/tt5753856/'>IMDb</a>")
        assertTrue(serienstreamTitleMatches(title, media))
        assertFalse(serienstreamTitleMatches(title, media.copy(imdbId = "tt9999999")))
        assertFalse(serienstreamTitleMatches(Jsoup.parse("<h1>Dark</h1><a href='/jahr/2026'>2026</a>"), media))
        assertFalse(serienstreamTitleMatches(Jsoup.parse("<h1>Dark</h1>"), media))
    }

    @Test fun decodesPublicConfigurationWithoutExecutingHostScripts() {
        val config = JSONObject().put("source", "https://cdn.example/master.m3u8").put("captions", org.json.JSONArray())
        val original = Base64.getEncoder().encodeToString(config.toString().toByteArray())
        val shifted = original.reversed().map { (it.code + 3).toChar() }.joinToString("")
        val encoded = Base64.getEncoder().encodeToString(shifted.toByteArray(Charsets.ISO_8859_1)).map { c -> when(c) {
            in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
            in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
            else -> c
        } }.joinToString("@$", prefix = "!!", postfix = "#&")
        val page = Jsoup.parse("<script type='application/json'>[\"$encoded\"]</script><script>throw Error('never execute');</script>")
        assertEquals(config.getString("source"), decodeVoeConfig(page).getString("source"))
    }

    @Test fun routesGermanOriginalSeriesWithoutChangingExplicitSourcesOrMovies() {
        val dark = Media(70523, MediaType.TV, "Dark", originalLanguage = "de")
        val prefs = PlaybackPreferences(generalProvider = MobilePlaybackProvider.FLIXER)
        assertEquals(MobilePlaybackProvider.SERIENSTREAM, prefs.sourceFor(dark).identity)
        assertEquals(MobilePlaybackProvider.SERIENSTREAM, prefs.copy(generalProvider = PlaybackProviderId.CINEJOY).sourceFor(dark).identity)
        assertEquals(PlaybackProviderId.CINEJOY, prefs.sourceFor(dark, PlaybackProviderId.CINEJOY).identity)
        assertEquals(MobilePlaybackProvider.FLIXER, prefs.sourceFor(dark.copy(type = MediaType.MOVIE)).identity)
        assertFalse(MobilePlaybackProvider.SERIENSTREAM.isAvailableFor(dark.copy(type = MediaType.MOVIE)))
        assertEquals(1, mobileGeneralPlaybackProviders().count { it == MobilePlaybackProvider.SERIENSTREAM })
        val selected = PlaybackSelection(dark,1,1,source = prefs.sourceFor(dark))
        assertEquals(listOf(selected),initialPlaybackRace(playbackSourceFallbacks(selected,prefs)))
        assertFalse(playbackSourceFallbacks(selected,prefs).drop(1).isEmpty())
    }

    @Test fun requestRoundTripKeepsAudioEditionAndDoesNotReuseItsStreamKeysAsRenditions() {
        val request = NativePlaybackRequest("https://cdn.example/master.m3u8", "application/x-mpegURL", "https://voe.sx/e/id", "ua", "", "Pantheon", 90000, false,
            sourceAudioLanguage = "en", sourceAudioOptions = """[{"language":"de","label":"German"},{"language":"en","label":"English"}]""")
        assertEquals(request, NativePlaybackRequest.fromJson(request.toJson()))
        assertEquals(listOf("de", "en"), request.sourceAudioChoices().map { it.language })
        assertEquals(listOf("en"), request.copy(offlineDownloadId = "saved-episode").sourceAudioChoices().map { it.language })
        val quality = com.aliflix.app.downloads.DownloadQuality("720p",720,100,true,listOf(androidx.media3.common.StreamKey(0,0)),"serienstream-source")
        val tracks = listOf(com.aliflix.app.downloads.DownloadAudioTrack("English","en","serienstream-source",0))
        assertEquals(quality.keys, com.aliflix.app.downloads.downloadStreamKeys(quality, tracks, 0))
    }
}
