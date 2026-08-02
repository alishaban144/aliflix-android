package com.aliflix.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class SearchRecommendationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun openDiscover() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("bottom-tab-discover")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("bottom-tab-discover").performClick()
    }

    @Test
    fun explicitlySelectingDiscoverFocusesThePrimaryField() {
        openDiscover()

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("discover-search-field")
            .assertIsDisplayed()
            .assertIsFocused()
    }

    @Test
    fun ordinaryRecompositionAndRecreationDoNotReplayDiscoverFocusRequest() {
        openDiscover()
        composeRule.onNodeWithTag("discover-search-field").assertIsFocused()

        composeRule.onNodeWithTag("discover-mode-recommend")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performClick()
        composeRule.onNodeWithTag("discover-search-field").assertIsNotFocused()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("discover-search-field").assertIsNotFocused()
    }

    @Test
    fun discoverPrimaryControlsMeetMinimumTouchTargetHeight() {
        openDiscover()

        composeRule.onNodeWithTag("discover-mode-catalogue")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("discover-mode-recommend")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("discover-type-movie")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("discover-type-series")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun discoverUsesOnlyCatalogueAndRecommendationModes() {
        openDiscover()

        composeRule.onNodeWithTag("discover-mode-catalogue").assertIsDisplayed()
        composeRule.onNodeWithTag("discover-mode-recommend").assertIsDisplayed()
        composeRule.onAllNodesWithTag("search-mode-plot").assertCountEquals(0)
        composeRule.onAllNodesWithTag("search-mode-pager").assertCountEquals(0)
    }

    @Test
    fun tappingTryOneStartsTheRecommendationFlowImmediately() {
        openDiscover()
        composeRule.onNodeWithTag("discover-mode-recommend").performClick()
        composeRule.onNodeWithTag("discover-type-movie").performClick()
        composeRule.onAllNodesWithTag("discover-try-one-card")[0].performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            listOf(
                "discover-recommendation-loading",
                "discover-recommendation-question",
                "discover-recommendation-results",
                "discover-recommendation-low-confidence",
                "discover-recommendation-error",
                "discover-recommendation-source-error",
            ).any { tag ->
                composeRule.onAllNodesWithTag(tag)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun recommendationDraftAndTypeSurviveActivityRecreation() {
        openDiscover()
        composeRule.onNodeWithTag("discover-mode-recommend").performClick()
        composeRule.onNodeWithTag("discover-type-series").performClick()
        composeRule.onNodeWithTag("discover-search-field")
            .performTextInput("A quiet mystery with a complete ending")

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("discover-mode-recommend").assertIsSelected()
        composeRule.onNodeWithTag("discover-type-series").assertIsSelected()
        composeRule.onNodeWithTag("discover-search-field")
            .assertTextContains("A quiet mystery with a complete ending")
    }
}
