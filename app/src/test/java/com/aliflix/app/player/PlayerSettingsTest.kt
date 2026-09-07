package com.aliflix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsTest {
    @Test
    fun defaultSettingsHaveExpectedValues() {
        val settings = PlayerSettings()
        assertEquals(16f, settings.subtitleFontSizeSp, 0.01f)
        assertEquals(0.5f, settings.subtitleBackgroundOpacity, 0.01f)
        assertEquals(0, settings.subtitleDelayTenths)
        assertEquals(0.0, settings.subtitleDelaySeconds, 0.001)
        assertEquals(0, settings.subtitleVerticalOffsetDp)
        assertEquals(1.0f, settings.playbackSpeed, 0.01f)
        assertFalse(settings.resizeModeZoom)
    }

    @Test
    fun subtitleVerticalOffsetStoresExactValue() {
        assertEquals(20, PlayerSettings(subtitleVerticalOffsetDp = 20).subtitleVerticalOffsetDp)
        assertEquals(-15, PlayerSettings(subtitleVerticalOffsetDp = -15).subtitleVerticalOffsetDp)
    }

    @Test
    fun subtitleDelayComputesExactTenthsOfSecond() {
        assertEquals(0.1, PlayerSettings(subtitleDelayTenths = 1).subtitleDelaySeconds, 0.001)
        assertEquals(-0.1, PlayerSettings(subtitleDelayTenths = -1).subtitleDelaySeconds, 0.001)
        assertEquals(1.5, PlayerSettings(subtitleDelayTenths = 15).subtitleDelaySeconds, 0.001)
        assertEquals(-2.4, PlayerSettings(subtitleDelayTenths = -24).subtitleDelaySeconds, 0.001)
        assertEquals(10.0, PlayerSettings(subtitleDelayTenths = 100).subtitleDelaySeconds, 0.001)
    }

    @Test
    fun resizeModeZoomToggle() {
        val fit = PlayerSettings(resizeModeZoom = false)
        val fill = fit.copy(resizeModeZoom = true)
        assertTrue(fill.resizeModeZoom)
        assertFalse(fit.resizeModeZoom)
    }
}
