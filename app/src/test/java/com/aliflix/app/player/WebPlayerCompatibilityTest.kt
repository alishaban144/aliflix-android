package com.aliflix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPlayerCompatibilityTest {
    @Test
    fun removesWebViewAndAppMarkersFromUserAgent() {
        val userAgent =
            "Mozilla/5.0 (Linux; Android 11; TV Build/RP1A; wv) " +
                "AppleWebKit/537.36 Version/4.0 Chrome/120.0.0.0 Safari/537.36 " +
                "AliflixTV/2.4-tv"

        val compatible = browserCompatibleUserAgent(userAgent)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 11; TV Build/RP1A) " +
                "AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            compatible,
        )
        assertFalse(compatible.contains("wv"))
        assertFalse(compatible.contains("Aliflix"))
    }

    @Test
    fun moviepireShieldIsScopedToPlaybackDocumentOrigins() {
        val origins = mobileMoviepireShieldOriginRules("custom.moviepire.example")

        assertTrue(origins.contains("https://custom.moviepire.example"))
        assertTrue(origins.contains("https://*.custom.moviepire.example"))
        assertTrue(origins.contains("https://moviepire.ru"))
        assertTrue(origins.contains("https://vidrock.ru"))
        assertTrue(origins.contains("https://*.videasy.net"))
        assertFalse(origins.contains("*"))
        assertFalse(origins.any { it.startsWith("http://") })
    }

    @Test
    fun moviepireShieldRunsBeforeAdsAndKeepsPlayerApisAvailable() {
        val script = mobileMoviepireAdShieldScript()

        listOf(
            "#paldo-ad",
            "balkersestian.com",
            "crowdsynonym.com",
            "mploejuiashsatea.com",
            "acscdn.com",
            "hebamicmopeds.com",
            "adnetworks",
            "Node.prototype.appendChild",
            "EventTarget.prototype.addEventListener",
            "MutationObserver",
            "system warning detected",
        ).forEach { marker -> assertTrue(marker, script.contains(marker)) }

        assertFalse(script.contains("HTMLVideoElement.prototype"))
        assertFalse(script.contains("requestFullscreen ="))
        assertFalse(script.contains("chrome.cast ="))
    }
}
