package com.aliflix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
