package com.aliflix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home hero height doubles as the boundary between the poster and the darker content scrim, so
 * the two must stay in step: a taller hero has to move the scrim down with it.
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

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}
