package com.aliflix.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.SearchUiState
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.RecommendationAiClient
import com.aliflix.app.ui.discover.*
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DiscoverCatalogueUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogueAndPeopleRemainAvailableWithoutAskAliflix() {
        val store = DiscoverCatalogueStore(RecommendationAiClient("https://unused.test"))
        val movie = Media(27205, MediaType.MOVIE, "Inception", year = "2010", posterPath = "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg")
        val person = MediaCreator(31, "Tom Hanks")
        for (filter in listOf("All", "Movies", "Series")) {
            for (category in listOf("trending", "new", "top-rated")) store.session(category, "", filter).apply {
                items = listOf(movie); updatedAt = System.currentTimeMillis(); hasMore = false
            }
        }
        store.session(null, "Tom", "All").apply { people = listOf(person); updatedAt = System.currentTimeMillis(); hasMore = false }
        store.session(null, "Tom", "Movies").apply { items = listOf(movie); updatedAt = System.currentTimeMillis(); hasMore = false }
        var openedPerson: MediaCreator? = null
        var filter by mutableStateOf("All")
        compose.setContent { AliflixMobileTheme {
            DiscoverScreen(state = SearchUiState(), aiEnabled = false, catalogueStore = store,
                onPerson = { openedPerson = it }, onCategory = {}, homeContent = null, recent = emptyList(),
                focusRequestId = null, onFocusRequestConsumed = {}, onQueryChange = {}, onSubmitSearch = {},
                onSearchTitles = { emptyList() }, onModeChange = {}, onOpen = {}, catalogGridState = rememberLazyGridState(),
                recommendationListState = rememberLazyListState(), mediaFilter = filter, onMediaFilterChange = { filter = it })
        } }
        compose.onNodeWithText("Ask Aliflix").assertDoesNotExist()
        compose.onNodeWithText("Trending").assertIsDisplayed()
        compose.onNodeWithText("Top Rated").assertIsDisplayed()
        compose.onNodeWithText("Mind-bending").assertDoesNotExist()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "discover79.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithTag("discover-search-field").performTextInput("Tom")
        compose.onNodeWithText("Tom Hanks").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(person, openedPerson) }
        compose.onNodeWithText("Movies", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Tom Hanks").assertDoesNotExist()
        compose.onNodeWithText("Inception").assertIsDisplayed()
        compose.onNodeWithTag("discover-catalogue-submit").assertDoesNotExist()
    }
}
