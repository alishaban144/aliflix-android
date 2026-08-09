package com.aliflix.app.ui.discover

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Rule
import org.junit.Test

class AskAliflixUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun similarToHasOneSearchAndMorphsToCanonicalAnchor() {
        showComposer()
        compose.onNodeWithTag("ask-mode-1").performClick()
        compose.onAllNodesWithTag("similar-title-search").assertCountEquals(1)
        compose.onNodeWithTag("similar-title-search").performTextInput("Breaking")
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("similar-suggestions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("similar-suggestions").assertIsDisplayed()
        compose.onNodeWithText("Breaking Bad").performClick()
        compose.onNodeWithTag("similar-selected-anchor").assertIsDisplayed()
        compose.onAllNodesWithTag("similar-title-search").assertCountEquals(0)
    }

    @Test fun workspaceAndFilterBrowserUseRemainingScreenHeight() {
        showComposer()
        compose.onNodeWithTag("ask-active-workspace").assertHeightIsAtLeast(300.dp)
        compose.onNodeWithTag("ask-mode-2").performClick()
        compose.onNodeWithTag("ask-filter-browser").assertIsDisplayed().assertHeightIsAtLeast(300.dp)
    }

    private fun showComposer() {
        compose.setContent {
            AliflixMobileTheme {
                RecommendationComposer(
                    state = RecommendationUiState.Idle,
                    selectedKind = RecommendationMediaKind.SERIES,
                    onSelectType = {},
                    onSearchTitles = {
                        listOf(Media(1396, MediaType.TV, "Breaking Bad", year = "2008"))
                    },
                    onSubmit = {}, onAnswer = { _, _ -> }, onRestart = {}, onRelax = {}, onRetry = {},
                    onShowMatches = {}, onBack = {},
                )
            }
        }
    }
}
