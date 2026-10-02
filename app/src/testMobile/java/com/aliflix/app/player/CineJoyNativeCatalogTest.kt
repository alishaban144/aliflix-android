package com.aliflix.app.player

import com.aliflix.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CineJoyNativeCatalogTest {
    private fun darkS1E1() = PlaybackSelection(
        Media(70523, MediaType.TV, "Dark", year = "2017", imdbId = "tt5753856"),
        seasonNumber = 1,
        episodeNumber = 1,
        source = PlaybackSource(PlaybackProviderId.CINEJOY),
    )

    @Test fun darkEpisodeBuildsExactSiteCatalogueUrl() {
        val url = CineJoyNativeCatalog.innerUrl(darkS1E1(), "Lisbon")
        assertTrue(url, url.startsWith("https://api.wing.st/?"))
        assertTrue(url, url.contains("title=Dark"))
        assertTrue(url, url.contains("type=series"))
        assertTrue(url, url.contains("year=2017"))
        assertTrue(url, url.contains("imdb=tt5753856"))
        assertTrue(url, url.contains("tmdb=70523"))
        assertTrue(url, url.contains("server=Lisbon"))
        assertTrue(url, url.contains("season=1"))
        assertTrue(url, url.contains("episode=1"))
    }

    @Test fun moviesOmitSeasonEpisodeAndTolerateMissingIds() {
        val movie = PlaybackSelection(
            Media(550, MediaType.MOVIE, "Fight Club"),
            source = PlaybackSource(PlaybackProviderId.CINEJOY),
        )
        val url = CineJoyNativeCatalog.innerUrl(movie, "Lisbon")
        assertTrue(url, url.contains("type=movie"))
        assertTrue(url, url.contains("tmdb=550"))
        assertFalse(url, url.contains("season="))
        assertFalse(url, url.contains("imdb="))
        assertEquals("2017", CineJoyNativeCatalog.catalogueYear("2017-12-01"))
        assertNull(CineJoyNativeCatalog.catalogueYear(""))
        assertNull(CineJoyNativeCatalog.catalogueYear("unknown"))
    }

    @Test fun catalogueServersParseAndLabelForSelection() {
        val names = CineJoyNativeCatalog.parseServers(
            """{"servers":[{"name":"Lisbon","status":"ok"},{"name":"Nebula","status":"ok"}]}""",
        )
        assertEquals(listOf("Lisbon", "Nebula"), names)
        assertTrue(CineJoyNativeCatalog.parseServers("{}").isEmpty())
        assertEquals("CineJoy / Lisbon", CineJoyNativeCatalog.serverLabel("Lisbon"))
        assertEquals("Lisbon", CineJoyNativeCatalog.serverFromLabel("CineJoy / Lisbon"))
        assertNull(CineJoyNativeCatalog.serverFromLabel("CineJoy"))
    }

    @Test fun onlyHlsMastersAreNativePlayableWithAllAudio() {
        val decrypted = JSONObject(
            """{"status":200,"result":{"data":{"stream":[
                {"type":"hls","id":"primary","playlist":"https://cdn.example/playlist/master.m3u8","captions":[]},
                {"type":"hls","id":"embed","playlist":"https://cdn.example/content?v=token","captions":[]}
            ]}}}""",
        )
        assertEquals(listOf("https://cdn.example/playlist/master.m3u8"), CineJoyNativeCatalog.parsePlaylists(decrypted))
        assertTrue(CineJoyNativeCatalog.isHlsMasterUrl("https://cdn.example/a.m3u8?token=x"))
        assertFalse(CineJoyNativeCatalog.isHlsMasterUrl("https://cdn.example/content?v=token"))
        assertFalse(CineJoyNativeCatalog.isHlsMasterUrl("blob:https://cdn.example/id"))
    }

    @Test fun darkMasterDetectionMatchesLiveLisbonShape() {
        // Live Lisbon master for Dark S1E1 carries 4 audio renditions + 4 video variants.
        // The handoff must keep the master; a video-only variant has no audio at all.
        val master = JSONObject().put("url", "https://cdn.example/playlist/master.m3u8")
            .put("mimeType", "application/x-mpegURL").put("manifestKind", "master")
        val variant = JSONObject().put("url", "https://cdn.example/video/video_1080p.m3u8")
            .put("mimeType", "application/x-mpegURL").put("manifestKind", "variant")
        assertFalse(shouldAwaitHlsMaster(master, 0))
        assertTrue(shouldAwaitHlsMaster(variant, 7999))
        assertFalse(shouldAwaitHlsMaster(variant, 8000))
        assertTrue(shouldReplaceNativeStream(variant, master))
        assertFalse(shouldReplaceNativeStream(master, variant))
    }

    @Test fun darkAudioMenuComesFromMasterNotVideoOnlyChild() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 1",DEFAULT=YES,URI="audio_1.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 2",DEFAULT=NO,URI="audio_2.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 3",DEFAULT=NO,URI="audio_3.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 4",DEFAULT=NO,URI="audio_4.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,AUDIO="audio"
            video_1080p.m3u8
        """.trimIndent()
        assertEquals(listOf("Track 1", "Track 2", "Track 3", "Track 4"),
            CineJoyNativeCatalog.audioTracksInMaster(master))
        assertTrue(CineJoyNativeCatalog.audioTracksInMaster("#EXTM3U\n#EXTINF:6,\nsegment.ts").isEmpty())
    }

    @Test fun lisbonMasterKeepsAllAudioAndPrefersAvc1080OverHevc4k() {
        val url = "https://lit.example/playlist/master.m3u8"
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 1",DEFAULT=YES,URI="../audio/one.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 2",DEFAULT=NO,URI="../audio/two.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 3",DEFAULT=NO,URI="../audio/three.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Track 4",DEFAULT=NO,URI="../audio/four.m3u8"
            #EXT-X-STREAM-INF:RESOLUTION=3840x2160,CODECS="hvc1.1.2.L150.B0,mp4a.40.2",AUDIO="audio"
            ../video/4k.m3u8
            #EXT-X-STREAM-INF:DEFAULT=YES,RESOLUTION=1920x1080,CODECS="avc1.4D4032,mp4a.40.2",AUDIO="audio"
            ../video/1080.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=640x360,CODECS="avc1.4D401E,mp4a.40.2",AUDIO="audio"
            ../video/360.m3u8
        """.trimIndent()
        val parsed = CineJoyHlsProbe.master(url, master)
        assertEquals(listOf("Track 1", "Track 2", "Track 3", "Track 4"), parsed.audio.map { it.label })
        assertEquals("https://lit.example/audio/one.m3u8", parsed.audio.first().url)
        assertTrue(parsed.audio.first().default)
        assertEquals(1080, CineJoyHlsProbe.preferredVideos(parsed.video).first().height)
        assertEquals(360, CineJoyHlsProbe.preferredVideos(parsed.video, lowQuality = true).first().height)
    }

    @Test fun mediaProbeChecksTheChunkAtTheSavedPosition() {
        val url = "https://lit.example/video/1080.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.html"
            #EXTINF:6.000,
            #EXT-X-BITRATE:813
            zero.html
            #EXTINF:6.000,
            six.html
            #EXTINF:6.000,
            twelve.html
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals("https://lit.example/video/zero.html", CineJoyHlsProbe.segment(url, playlist, 0)?.media)
        assertEquals("https://lit.example/video/six.html", CineJoyHlsProbe.segment(url, playlist, 6_000)?.media)
        assertEquals("https://lit.example/video/init.html", CineJoyHlsProbe.segment(url, playlist, 6_000)?.init)
        assertNull(CineJoyHlsProbe.segment(url, playlist, 18_000))
    }
}
