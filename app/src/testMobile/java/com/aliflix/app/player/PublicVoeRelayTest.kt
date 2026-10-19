package com.aliflix.app.player

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class PublicVoeRelayTest {
    private fun page(target:String,from:String="https://voe.sx/e/file123?default_audio_language=en")=
        Jsoup.parse("<script>window.location.href = '$target';</script>",from)
    @Test fun canonicalHostMayDelegateOnlyTheSameFileAndAudioQuery() {
        val next="https://new-video-origin.com/e/file123?default_audio_language=en"
        assertEquals(next,publicVoeRelayTarget(page(next)))
        assertEquals(next,publicVoeRelayTarget(page(next,"https://www.voe.sx/e/file123?default_audio_language=en")))
        assertNull(publicVoeRelayTarget(page(next,"https://unrelated.example/e/file123?default_audio_language=en")))
        for(target in listOf("https://new-video-origin.com/e/different?default_audio_language=en",
            "https://new-video-origin.com/e/file123?default_audio_language=de",
            "https://new-video-origin.com:8443/e/file123?default_audio_language=en",
            "https://user@new-video-origin.com/e/file123?default_audio_language=en",
            "https://127.0.0.1/e/file123?default_audio_language=en",
            "https://private.local/e/file123?default_audio_language=en"))
            assertThrows(target,IllegalArgumentException::class.java){publicVoeRelayTarget(page(target))}
    }
    @Test fun tokenizedAccessRedirectRemainsConfinedToTheDelegatedOrigin() {
        val from=URI("https://new-video-origin.com/e/file123")
        assertTrue(sameProviderOrigin(from,URI("https://new-video-origin.com/access/opaque")))
        assertFalse(sameProviderOrigin(from,URI("https://ads.example/access/opaque")))
        assertFalse(sameProviderOrigin(from,URI("http://new-video-origin.com/access/opaque")))
        assertFalse(sameProviderOrigin(from,URI("https://user@new-video-origin.com/access/opaque")))
    }
}
