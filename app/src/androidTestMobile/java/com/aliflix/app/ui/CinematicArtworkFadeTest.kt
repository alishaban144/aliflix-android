package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel regression for the real artwork modifier; does not depend on network-loaded posters. */
class CinematicArtworkFadeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun artworkReachesAmbientBeforeItsEdgeWithoutErasingTheBackground() {
        compose.setContent {
            Box(Modifier.size(200.dp, 240.dp).background(AliflixBackgroundBase).testTag("plane")) {
                Box(Modifier.fillMaxWidth().height(200.dp).cinematicArtworkFade().background(Color.White))
            }
        }
        val pixels = compose.onNodeWithTag("plane").captureToImage().toPixelMap()
        val x = (pixels.width * 0.8f).toInt()
        fun at(fraction: Float) = pixels[x, (pixels.height * fraction).toInt()]
        // The upper image stays clear; the lower image gradually exposes the background.
        assertTrue(at(0.2f).red > 0.98f)
        assertTrue(at(0.5f).red > at(0.65f).red)
        assertTrue(at(0.65f).red > at(0.76f).red)
        // Both sides of the image boundary must match, including alpha: DstIn is isolated.
        for (fraction in listOf(0.82f, 0.84f, 0.95f)) {
            val color = at(fraction)
            assertEquals(AliflixBackgroundBase.red, color.red, 0.01f)
            assertEquals(AliflixBackgroundBase.green, color.green, 0.01f)
            assertEquals(AliflixBackgroundBase.blue, color.blue, 0.01f)
            assertEquals(1f, color.alpha, 0.01f)
        }
    }
}
