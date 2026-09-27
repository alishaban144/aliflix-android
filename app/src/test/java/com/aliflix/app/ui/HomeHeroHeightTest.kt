package com.aliflix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artwork frame follows the hero height, including accessibility font scaling and insets.
 */
class HomeHeroHeightTest {
    @Test fun `the base height is used at the default font scale`() {
        assertEquals(556.dp(), homeHeroHeight(1f))
    }

    @Test fun `a larger font scale grows the hero`() {
        assertTrue(homeHeroHeight(1.5f) > homeHeroHeight(1f))
    }

    @Test fun `the growth is bounded so the hero cannot swallow the page`() {
        assertEquals(556.dp() + 320.dp(), homeHeroHeight(4f))
    }

    @Test fun `a smaller font scale never shrinks the hero below the base height`() {
        assertEquals(556.dp(), homeHeroHeight(0.8f))
    }

    @Test fun `the band is as tall as the hero and starts at the top of the plane`() {
        val band = posterBand(planeHeight = 900.dp(), posterTopInset = 0.dp(), posterHeight = 556.dp())
        assertEquals(0.dp(), band.top)
        assertEquals(556.dp(), band.bottom)
        assertEquals(556.dp(), band.height)
    }

    @Test fun `a status bar inset contributes to the artwork frame bottom`() {
        val band = posterBand(planeHeight = 900.dp(), posterTopInset = 48.dp(), posterHeight = 420.dp())
        assertEquals(48.dp(), band.top)
        assertEquals(468.dp(), band.bottom)
        assertEquals(900.dp() - 468.dp(), 900.dp() - band.bottom)
    }

    @Test fun `a hero taller than the plane is cut off at the plane and never inverts`() {
        val band = posterBand(planeHeight = 500.dp(), posterTopInset = 0.dp(), posterHeight = 556.dp())
        assertEquals(0.dp(), band.top)
        assertEquals(500.dp(), band.bottom)
        assertTrue(band.bottom >= band.top)
    }

    @Test fun `an inset past the plane collapses to an empty band rather than drawing outside it`() {
        val band = posterBand(planeHeight = 300.dp(), posterTopInset = 400.dp(), posterHeight = 200.dp())
        assertEquals(300.dp(), band.top)
        assertEquals(300.dp(), band.bottom)
        assertEquals(0.dp(), band.height)
    }

    @Test fun `an unbounded or empty plane yields an empty band`() {
        assertEquals(0.dp(), posterBand(0.dp(), 0.dp(), 556.dp()).height)
        assertEquals(0.dp(), posterBand(androidx.compose.ui.unit.Dp.Infinity, 0.dp(), 556.dp()).height)
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}
