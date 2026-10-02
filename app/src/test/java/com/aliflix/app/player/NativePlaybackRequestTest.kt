package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class NativePlaybackRequestTest {
    @Test fun handoffPreservesStreamCredentialsPositionPauseAndSubtitles() {
        val input = NativePlaybackRequest("https://cdn.example/a.m3u8?token=x%2Fy", "application/x-mpegURL", "https://player.example/watch", "UA", "session=test", "Film", 42001, false, "WEBVTT\n\n", "selection", "de", "German")
        assertEquals(input, NativePlaybackRequest.fromJson(input.toJson()))
    }

    @Test fun cineJoyVideoRenditionSurvivesTheServiceHandoff() {
        val request = NativePlaybackRequest("https://cdn.example/master.m3u8", "application/x-mpegURL",
            "https://cinejoy.pk/", "UA", "", "Dark", 245_000, true,
            preferredVideoWidth = 1920, preferredVideoHeight = 1080)
        assertEquals(request, NativePlaybackRequest.fromJson(request.toJson()))
    }

    @Test fun hlsMasterBeatsVariantsAndCanExposeAllAudioTracks() {
        val variant = JSONObject().put("url", "https://cdn.example/de.m3u8").put("mimeType", "application/x-mpegURL").put("manifestKind", "variant")
        val master = JSONObject().put("url", "https://cdn.example/master.m3u8").put("mimeType", "application/x-mpegURL").put("manifestKind", "master")
        assertTrue(shouldAwaitHlsMaster(variant, 1000))
        assertTrue(shouldAwaitHlsMaster(variant, 3000))
        assertTrue(shouldAwaitHlsMaster(variant, 7999))
        assertFalse(shouldAwaitHlsMaster(variant, 8000))
        assertFalse(shouldAwaitHlsMaster(master, 0))
        assertTrue(shouldReplaceNativeStream(variant, master))
        assertFalse(shouldReplaceNativeStream(master, variant))
        assertTrue(nativeStreamDiscoveryScript().contains("TYPE=AUDIO"))
    }

    @Test fun audioTrackLabelsKeepEveryRenditionSelectable() {
        // CineJoy masters tag renditions Track 1..4 with no language; German stays pickable.
        assertEquals("Track 1", formatAudioTrackLabel(null, "Track 1"))
        assertEquals("Track 2", formatAudioTrackLabel("", "Track 2"))
        assertEquals("Original audio", formatAudioTrackLabel(null, null))
        assertEquals("Original audio", formatAudioTrackLabel("", "  "))
        // Language-tagged tracks show the language; both together show both.
        assertEquals("German", formatAudioTrackLabel("de", null))
        assertEquals("German • Track 2", formatAudioTrackLabel("de", "Track 2"))
        assertEquals("German", formatAudioTrackLabel("de", "German"))
        assertEquals("1080p", formatVideoTrackLabel(1080))
        assertEquals("Original", formatVideoTrackLabel(0))
    }

    @Test fun blobsAndNonNetworkSourcesCannotBeHandedToTheTv() {
        listOf("blob:https://example.com/id", "file:///private/video", "javascript:alert(1)", "https://user:pass@example.com/video", "https:///missing-host").forEach {
            assertFalse(it, isNativeStreamUrl(it))
        }
        assertTrue(isNativeStreamUrl("https://cdn.example/video.mp4?token=123"))
    }

    @Test fun subtitleDelayAndNegativeTimeClippingSurviveNativeHandoff() {
        val result = nativeSubtitlesVtt("[[0,1,\"expired\"],[1,3,\"visible\"],[3662,3663,\"later\"]]", -1.5)
        assertFalse(result.contains("expired"))
        assertTrue(result.contains("00:00:00.000 --> 00:00:01.500\nvisible"))
        assertTrue(result.contains("01:01:00.500 --> 01:01:01.500\nlater"))
    }
}
