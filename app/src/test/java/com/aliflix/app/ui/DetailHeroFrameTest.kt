package com.aliflix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The details hero absorbs the status bar inset into its own item, which is what lets the poster
 * plane live inside that item and still begin at the top of the screen. These cover the geometry
 * that keeps the artwork, and everything below the hero, exactly where they were.
 */
class DetailHeroFrameTest {
    @Test fun `the frame is the hero plus the status bar inset`() {
        assertEquals(468.dp(), detailHeroFrame(heroTopInset = 48.dp(), heroHeight = 420.dp()))
    }

    @Test fun `without an inset the frame is exactly the hero`() {
        assertEquals(420.dp(), detailHeroFrame(heroTopInset = 0.dp(), heroHeight = 420.dp()))
    }

    @Test fun `the scrolling plane crops the same artwork as the stationary plane did`() {
        val inset = 48.dp()
        val hero = 420.dp()
        val stationary = posterBand(planeHeight = 900.dp(), posterTopInset = inset, posterHeight = hero)
        val frame = detailHeroFrame(inset, hero)
        val scrolling = posterBand(planeHeight = frame, posterTopInset = 0.dp(), posterHeight = frame)
        assertEquals(stationary.bottom, scrolling.bottom)
        assertEquals(0.dp(), scrolling.top)
        assertEquals(frame, scrolling.height)
    }

    @Test fun `a frame taller than the screen is cut off at the plane and never inverts`() {
        val band = posterBand(
            planeHeight = 400.dp(),
            posterTopInset = 0.dp(),
            posterHeight = detailHeroFrame(heroTopInset = 48.dp(), heroHeight = 460.dp()),
        )
        assertEquals(400.dp(), band.bottom)
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}
