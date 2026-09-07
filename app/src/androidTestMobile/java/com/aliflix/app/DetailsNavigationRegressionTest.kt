package com.aliflix.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import org.junit.Rule
import org.junit.Test

/** Live catalogue smoke test for the exact released SaveableStateProvider crash. */
class DetailsNavigationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun detailsScrollUpdatesDoNotCreateDuplicateNavigationStateOwners() {
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("More Info").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        repeat(2) {
            compose.onNodeWithText("More Info").performClick()
            compose.onNodeWithContentDescription("Back to previous screen").assertExists()
            repeat(4) {
                compose.onRoot().performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            repeat(4) { compose.onRoot().performTouchInput { swipeDown() }; compose.waitForIdle() }
            pressBack()
            compose.onNodeWithText("More Info").assertExists()
        }
    }
}
