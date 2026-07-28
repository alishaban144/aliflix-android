package com.aliflix.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class SearchRecommendationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun thirdSearchPageOpensAndAcceptsFreeText() {
        composeRule.onNodeWithText("Search", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("search-mode-ai").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ai-recommendation-input")
            .assertIsDisplayed()
            .performTextInput("Funny movie with friends")
        composeRule.onNodeWithTag("ai-recommendation-submit").assertIsDisplayed()
    }

    @Test
    fun titleDescribeAndAiTabsAreAllPresentWhenBetaIsEnabled() {
        composeRule.onNodeWithText("Search", useUnmergedTree = true).performClick()

        composeRule.onNodeWithTag("search-mode-title").assertIsDisplayed()
        composeRule.onNodeWithTag("search-mode-plot").assertIsDisplayed()
        composeRule.onNodeWithTag("search-mode-ai").assertIsDisplayed()
        composeRule.onNodeWithTag("search-mode-pager").assertIsDisplayed()
    }
}
