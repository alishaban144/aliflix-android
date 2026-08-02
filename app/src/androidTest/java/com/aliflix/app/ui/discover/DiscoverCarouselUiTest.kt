package com.aliflix.app.ui.discover

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixTheme
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.recommendation.RecommendationPreferences
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.recommendation.SemanticModelState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class DiscoverCarouselUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tryOneAdvancesAutomatically() {
        val suggestions = discoverSuggestionLibrary.take(3)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AliflixTheme {
                TryOneCarousel(
                    suggestions = suggestions,
                    onSuggestion = {},
                )
            }
        }

        composeRule.onNodeWithTag("discover-try-one-carousel").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Suggestion 1 of 3: ${suggestions[0].prompt}",
            ),
        )

        composeRule.mainClock.advanceTimeBy(7_000)

        composeRule.onNodeWithTag("discover-try-one-carousel").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Suggestion 2 of 3: ${suggestions[1].prompt}",
            ),
        )
    }

    @Test
    fun tryOnePausesWhileTheCardIsPressedOrDragged() {
        val suggestions = discoverSuggestionLibrary.take(3)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AliflixTheme {
                TryOneCarousel(
                    suggestions = suggestions,
                    onSuggestion = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("discover-try-one-card")[0]
            .performTouchInput {
                down(center)
                moveBy(Offset(-40f, 0f))
                advanceEventTime(1_000)
                composeRule.mainClock.advanceTimeBy(7_000)
                up()
            }

        composeRule.onNodeWithTag("discover-try-one-carousel").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Suggestion 1 of 3: ${suggestions[0].prompt}",
            ),
        )

        composeRule.mainClock.advanceTimeBy(7_000)

        composeRule.onNodeWithTag("discover-try-one-carousel").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Suggestion 2 of 3: ${suggestions[1].prompt}",
            ),
        )
    }

    @Test
    fun tryOnePausesWhileAManualNavigationControlIsHeld() {
        val suggestions = discoverSuggestionLibrary.take(3)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AliflixTheme {
                TryOneCarousel(
                    suggestions = suggestions,
                    onSuggestion = {},
                )
            }
        }

        composeRule.onNodeWithTag("discover-try-one-next")
            .performTouchInput {
                down(center)
                composeRule.mainClock.advanceTimeBy(7_000)
                moveTo(Offset(-100f, -100f))
                advanceEventTime(100)
                up()
            }

        composeRule.onNodeWithTag("discover-try-one-carousel").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Suggestion 1 of 3: ${suggestions[0].prompt}",
            ),
        )
    }

    @Test
    fun tryOneProvidesPredictableAccessibleManualNavigation() {
        val suggestions = discoverSuggestionLibrary.take(3)
        composeRule.setContent {
            AliflixTheme {
                TryOneCarousel(
                    suggestions = suggestions,
                    onSuggestion = {},
                )
            }
        }

        composeRule.onNodeWithTag("discover-try-one-previous")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("discover-try-one-next")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag("discover-try-one-carousel").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Suggestion 2 of 3: ${suggestions[1].prompt}",
            ),
        )
        composeRule.onNodeWithContentDescription("Try one suggestions").assertIsDisplayed()
        composeRule.onAllNodesWithTag("discover-try-one-card")[0]
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Previous suggestion").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next suggestion").assertIsDisplayed()
    }

    @Test
    fun recommendationFeedbackActionsAreVisibleAccessibleTouchTargets() {
        val media = Media(
            id = 1,
            type = MediaType.TV,
            title = "A ranked result",
        )
        composeRule.setContent {
            AliflixTheme {
                FeedbackActions(
                    media = media,
                    onMoreLike = {},
                    onLessLike = {},
                    onSeen = {},
                )
            }
        }

        listOf("more", "less", "seen").forEach { action ->
            composeRule.onNodeWithTag("discover-feedback-$action-${media.key}")
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun failedLaterPageDoesNotAutomaticallyRetryWhileTailRemainsVisible() {
        val media = Media(id = 91, type = MediaType.MOVIE, title = "Loaded result")
        var state by mutableStateOf(
            RecommendationUiState.Results(
                preferences = RecommendationPreferences(),
                candidates = listOf(RecommendationCandidate(media = media)),
                hasMore = true,
            ),
        )
        val loadCalls = AtomicInteger(0)
        composeRule.setContent {
            AliflixTheme {
                RecommendationContent(
                    state = state,
                    suggestions = emptyList(),
                    semanticModelState = SemanticModelState.Unavailable,
                    shouldOfferSemanticModel = false,
                    onSuggestion = {},
                    onSurprise = {},
                    onAnswer = { _, _ -> },
                    onShowMatches = {},
                    onBack = {},
                    onRestart = {},
                    onRetry = {},
                    onOpen = {},
                    onLoadMore = {
                        loadCalls.incrementAndGet()
                        state = state.copy(loadingMore = true)
                    },
                    onRetryPage = {},
                    onRelax = {},
                    onDownloadSemanticModel = {},
                    onDismissSemanticModelOffer = {},
                    onMoreLike = {},
                    onLessLike = {},
                    onSeen = {},
                    onCorrectPreference = {},
                    listState = rememberLazyListState(),
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { loadCalls.get() == 1 }
        composeRule.runOnIdle {
            state = state.copy(
                loadingMore = false,
                pageError = "The next page failed",
            )
        }
        composeRule.waitForIdle()

        org.junit.Assert.assertEquals(1, loadCalls.get())
        composeRule.onNodeWithTag("discover-recommendation-page-error")
            .assertIsDisplayed()
    }
}
