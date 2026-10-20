package com.aliflix.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aliflix.app.model.*
import com.aliflix.app.ui.discover.*
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No remote research, provider traffic or mutation of the viewer's library. */
class UnifiedAskUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun dailyLimitDoesNotInviteRepeatedRetries(){
  compose.setContent{AliflixMobileTheme{UnifiedAskAliflix(AskAliflixUiState.Error("query","Daily limit"),AskAliflixEditorState(),{},{},{},{},{},emptyList(),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={emptyList()})}}
  compose.onNodeWithText("Daily limit").assertIsDisplayed()
  compose.onNodeWithText("Retry",useUnmergedTree=true).assertDoesNotExist()
 }
 @Test fun quotaErrorKeepsBrowseSuggestionsVisible(){
  val movie=Media(27205,MediaType.MOVIE,"Inception",year="2010")
  compose.setContent{AliflixMobileTheme{UnifiedAskAliflix(AskAliflixUiState.Error("query","Daily limit"),AskAliflixEditorState(),{},{},{},{},{},emptyList(),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={listOf(movie)})}}
  compose.onNodeWithTag("ask-daily").assertIsDisplayed()
  compose.onNodeWithText("Daily limit").assertIsDisplayed()
  compose.onNodeWithText("Retry",useUnmergedTree=true).assertDoesNotExist()
 }
 @Test fun researchDoesNotReplacePopulatedSuggestionsWithAnEmptyLoader(){
  val movie=Media(27205,MediaType.MOVIE,"Inception",year="2010")
  compose.setContent{AliflixMobileTheme{UnifiedAskAliflix(AskAliflixUiState.Searching("identity"),AskAliflixEditorState(),{},{},{},{},{},emptyList(),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={listOf(movie)})}}
  compose.onNodeWithTag("ask-daily").assertIsDisplayed()
  compose.onNodeWithText("Inception").assertIsDisplayed()
 }
 @Test fun composerAnchorsFiltersAndMediaSelectionShareOneScreen(){
  var editor by mutableStateOf(AskAliflixEditorState())
  var submitted:AskAliflixRequest?=null
  val movie=Media(27205,MediaType.MOVIE,"Inception",year="2010")
  val series=Media(70523,MediaType.TV,"Dark",year="2017")
  compose.setContent {AliflixMobileTheme {
   UnifiedAskAliflix(AskAliflixUiState.Editing,editor,{editor=it},{submitted=it},{},{},{},listOf(movie,series),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={if(it==MediaType.MOVIE)listOf(movie)else listOf(series)})
  }}
  compose.onNodeWithTag("ask-unified").assertIsDisplayed()
  compose.onNodeWithText("Describe",useUnmergedTree=true).assertDoesNotExist()
  compose.onNodeWithTag("ask-composer").performTextInput("memory and identity")
  compose.onNodeWithTag("ask-send").performClick()
  compose.runOnIdle{assertEquals("memory and identity",(submitted as AskAliflixRequest.Describe).text)}
  compose.onNodeWithText("Similar",useUnmergedTree=true).performClick()
  compose.onNodeWithTag("ask-anchor-search").assertIsDisplayed()
  compose.onAllNodesWithText("Inception",useUnmergedTree=true).onFirst().performClick()
  compose.runOnIdle{assertEquals(1,editor.selectedAnchors.size)}
  compose.onAllNodesWithText("Filters",useUnmergedTree=true).onFirst().performClick()
  compose.onNodeWithTag("ask-filter-sheet").assertIsDisplayed()
  compose.onNodeWithContentDescription("Close filters").performClick()
  compose.onNodeWithTag("ask-filter-sheet").assertDoesNotExist()
  compose.onNodeWithText("Series",useUnmergedTree=true).performClick()
  compose.runOnIdle{assertEquals(MediaType.TV,editor.mediaType)}
  compose.onNodeWithTag("ask-daily").assertIsDisplayed()
  compose.onNodeWithTag("ask-series-status").assertIsDisplayed()
  compose.onNodeWithTag("ask-status-running").performClick()
  compose.runOnIdle{assertEquals("Returning Series",editor.spec.requiredStatus)}
  compose.onNodeWithTag("ask-send").performClick()
  compose.runOnIdle{assertEquals("returning",com.aliflix.app.ui.discover.AskAliflixRequestMapper.map(requireNotNull(submitted)).workerRequest.filters.seriesStatus)}
  compose.onNodeWithTag("ask-status-ended").performClick()
  compose.runOnIdle{assertEquals("Ended",editor.spec.requiredStatus)}
  compose.onNodeWithText("Surprise Me",useUnmergedTree=true).performClick()
  compose.runOnIdle{assertEquals("Ended",(submitted as AskAliflixRequest.Describe).filters?.requiredStatus)}
  compose.onNodeWithTag("ask-status-all").performClick()
  compose.runOnIdle{assertNull(editor.spec.requiredStatus)}
 }
}
