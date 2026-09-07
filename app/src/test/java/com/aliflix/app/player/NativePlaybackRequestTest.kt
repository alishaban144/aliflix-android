package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackRequestTest {
    @Test fun handoffPreservesStreamCredentialsPositionPauseAndSubtitles() {
        val input = NativePlaybackRequest("https://cdn.example/a.m3u8?token=x%2Fy", "application/x-mpegURL", "https://player.example/watch", "UA", "session=test", "Film", 42001, false, "WEBVTT\n\n", "selection", "de", "German")
        assertEquals(input, NativePlaybackRequest.fromJson(input.toJson()))
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
