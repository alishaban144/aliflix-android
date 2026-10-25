package com.aliflix.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.aliflix.app.model.*
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.*
import org.junit.Assert.*

class LibraryMetadataDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun actualLibraryPosterPillsStaySingleLineAndUnclippedAtLargeFont() {
        compose.setContent {AliflixMobileTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.7f)) {
                MediaPoster(Media(181,MediaType.MOVIE,"A library film",rating=8.7,runtime="130 min"),132.dp,showLibraryMetadata=true,onClick={})
            }
        }}
        for(text in listOf("MOVIE","★ 8.7","2h10m")) {
            val node=compose.onNodeWithText(text,useUnmergedTree=true).assertIsDisplayed()
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertEquals(1,layouts.single().lineCount)
            val layout=layouts.single()
            assertFalse("Pill cannot discard its content: $text size=${layout.size} paragraphWidth=${layout.multiParagraph.width} line=${layout.getLineLeft(0)}..${layout.getLineRight(0)} constraints=${layout.layoutInput.constraints} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}",layout.hasVisualOverflow)
            assertEquals(androidx.compose.ui.text.style.TextAlign.Center,layouts.single().layoutInput.style.textAlign)
        }
    }
}
