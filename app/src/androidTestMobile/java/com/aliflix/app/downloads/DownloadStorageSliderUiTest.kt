package com.aliflix.app.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadStorageSliderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessibleAdjustmentCommitsQuotaAndRetainsExactEditor() {
        val limit = mutableIntStateOf(50)
        var edits = 0
        compose.setContent {
            Box(Modifier.width(280.dp)) {
                DownloadStorageSlider(limit.intValue, 25 * DOWNLOAD_GB,
                    onCommit = { limit.intValue = it }, onEdit = { edits++ })
            }
        }
        compose.onNodeWithTag("download-storage-slider").assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        compose.runOnIdle { assertEquals(75, limit.intValue) }
        compose.onNodeWithTag("download-storage-value").assertTextEquals("75 GB").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun externalPreferenceChangesRestoreValueAndUsageWarningInRtl() {
        val limit = mutableIntStateOf(50)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(280.dp)) {
                    DownloadStorageSlider(limit.intValue, 51 * DOWNLOAD_GB,
                        onCommit = { limit.intValue = it }, onEdit = {})
                }
            }
        }
        compose.onNodeWithText("Limit below usage").assertIsDisplayed()
        compose.runOnIdle { limit.intValue = 100 }
        compose.onNodeWithTag("download-storage-value").assertTextEquals("100 GB")
        compose.onNodeWithText("Limit below usage").assertDoesNotExist()
        compose.onNodeWithTag("download-storage-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        compose.runOnIdle { assertEquals(200, limit.intValue) }
    }
}
