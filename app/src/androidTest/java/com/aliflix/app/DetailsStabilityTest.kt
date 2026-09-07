package com.aliflix.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aliflix.app.model.*
import com.aliflix.app.ui.DetailScreen
import com.aliflix.app.ui.TvDetailScreen
import com.aliflix.app.ui.theme.AliflixTheme
import org.junit.Rule
import org.junit.Test

class DetailsStabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seriesEpisodesMetadataUpdatesAndMovieReplacementStayStable() {
        var state by mutableStateOf(DetailUiState(item = Media(42, MediaType.TV, "Series fixture")))
        compose.setContent {
            AliflixTheme {
                if (BuildConfig.IS_TV) TvDetailScreen(state, false, false, PlaybackProviderId.MOVIEPIRE,
                    false, {}, { _, _ -> }, { _, _, _ -> }, {}, {}, {}, {})
                else DetailScreen(state, false, false, emptyMap(), null, {}, {}, { _, _ -> }, {}, {}, {}, {},
                    PlaybackProviderId.MOVIEPIRE, {})
            }
        }
        compose.onNodeWithText("Series fixture").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(seasons = listOf(Season(1, "Season 1", 2), Season(1, "Season 1", 2)),
                episodes = listOf(Episode(1, 1, "First episode"), Episode(1, 1, "First episode"), Episode(1, 2, "Second episode")))
        }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Episodes"))
        compose.onNodeWithText("Episodes").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(selectedSeason = 2, episodes = emptyList(), episodesLoading = true) }
        compose.waitForIdle()
        compose.runOnIdle { state = state.copy(episodesLoading = false, error = "Unavailable") }
        compose.waitForIdle()
        compose.runOnIdle { state = DetailUiState(item = Media(43, MediaType.MOVIE, "Movie fixture")) }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithText("Movie fixture").assertIsDisplayed()
    }
}
