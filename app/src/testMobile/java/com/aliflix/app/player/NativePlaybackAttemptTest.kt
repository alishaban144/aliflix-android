package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackAttemptTest {
    @Test fun retryOfIdenticalUrlDoesNotConsumeThePreviousFailureOrReadyState() {
        val url = "https://cdn.example/dark/master.m3u8"
        assertFalse(nativeAttemptMatches("retry", "failed", url, url))
        assertFalse(nativeAttemptMatches("retry", null, url, url))
        assertFalse(nativeAttemptMatches("retry", "retry", url, "https://cdn.example/old.m3u8"))
        assertTrue(nativeAttemptMatches("retry", "retry", url, url))
    }
}
