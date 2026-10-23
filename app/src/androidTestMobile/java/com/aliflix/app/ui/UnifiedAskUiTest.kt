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
 @Test fun switchingFromDescribeResultsSubmitsTheSelectedSimilarAnchor(){
  val anchor=Media(1396,MediaType.TV,"Breaking Bad",year="2008")
  val spec=com.aliflix.app.recommendation.CatalogDiscoverySpec(mediaKind=com.aliflix.app.recommendation.RecommendationMediaKind.SERIES)
  val prior=AskAliflixRequest.Describe(MediaType.TV,"crime dramas",filters=spec)
  val state=AskAliflixUiState.Results("crime dramas",spec,listOf(com.aliflix.app.recommendation.RecommendationCandidate(anchor)),activeRequest=prior)
  var editor by mutableStateOf(AskAliflixEditorState(mode=1,mediaType=MediaType.TV,selectedAnchors=listOf(anchor),selectedAnchor=anchor,spec=spec))
  var submitted:AskAliflixRequest?=null
  compose.setContent{AliflixMobileTheme{UnifiedAskAliflix(state,editor,{editor=it},{submitted=it},{},{},{},emptyList(),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={emptyList()},onRefine={error("The previous Describe request must not be refined")})}}
  compose.onNodeWithTag("ask-send").performScrollTo().performClick()
  compose.runOnIdle{assertEquals(listOf(anchor),(submitted as AskAliflixRequest.Similar).anchors)}
 }
 @Test fun newResultsAndReorderedBatchesStartAtTheTopUntilTheUserScrolls(){
  val spec=com.aliflix.app.recommendation.CatalogDiscoverySpec(mediaKind=com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE)
  val rows=(1..16).map{com.aliflix.app.recommendation.RecommendationCandidate(Media(90000+it,MediaType.MOVIE,"Result $it",year="2020"))}
  var state:AskAliflixUiState by mutableStateOf(AskAliflixUiState.Results("old",spec,rows))
  lateinit var list:androidx.compose.foundation.lazy.LazyListState
  compose.setContent{AliflixMobileTheme{list=rememberLazyListState();UnifiedAskAliflix(state,AskAliflixEditorState(),{},{},{},{},{},emptyList(),false,{}, {emptyList()},list,emptySet(),{},Modifier,dailyProvider={emptyList()})}}
  compose.onNodeWithTag("ask-content").performScrollToIndex(10)
  compose.runOnIdle{state=AskAliflixUiState.Searching("new")}
  compose.waitForIdle()
  compose.runOnIdle{state=AskAliflixUiState.Results("new",spec,rows.takeLast(4),researching=true)}
  compose.waitForIdle()
  compose.runOnIdle{state=AskAliflixUiState.Results("new",spec,rows,researching=true)}
  compose.waitForIdle()
  compose.runOnIdle{assertEquals(0,list.firstVisibleItemIndex);assertEquals(0,list.firstVisibleItemScrollOffset)}
  compose.onNodeWithTag("ask-content").performTouchInput{swipeUp()}
  compose.waitForIdle()
  var key:Any?=null
  compose.runOnIdle{key=list.layoutInfo.visibleItemsInfo.first().key;state=AskAliflixUiState.Results("new",spec,rows.reversed(),researching=false)}
  compose.waitForIdle()
  compose.runOnIdle{assertEquals("Streaming must preserve intentional reading",key,list.layoutInfo.visibleItemsInfo.first().key)}
 }
 @Test fun dislikeLivesInsideTheCompactCardAndRespondsInTheSameFrame(){
  val movie=Media(27205,MediaType.MOVIE,"Inception",year="2010")
  var visible by mutableStateOf(true)
  compose.setContent{AliflixMobileTheme{if(visible)MobileAskResultCard(com.aliflix.app.recommendation.RecommendationCandidate(movie),{},false,{},false,onDislike={visible=false})}}
  val card=compose.onNodeWithTag("ask-result-${movie.key}").fetchSemanticsNode().boundsInRoot
  val dislike=compose.onNodeWithTag("ask-result-dislike-${movie.key}").fetchSemanticsNode().boundsInRoot
  assertTrue("Dislike must be inside the result card",card.contains(dislike.center))
  compose.onNodeWithTag("ask-result-dislike-${movie.key}").performClick()
  compose.onNodeWithTag("ask-result-${movie.key}").assertDoesNotExist()
 }
 @Test fun surprisePresentsThreeChoicesWithoutOpeningATitle(){
  var state:AskAliflixUiState by mutableStateOf(AskAliflixUiState.Editing)
  var opened:Media?=null
  val movies=listOf(Media(1,MediaType.MOVIE,"First discovery"),Media(2,MediaType.MOVIE,"Second discovery"),Media(3,MediaType.MOVIE,"Third discovery"))
  compose.setContent{AliflixMobileTheme{UnifiedAskAliflix(state,AskAliflixEditorState(),{}, {request->
   state=AskAliflixUiState.Results("surprise",com.aliflix.app.recommendation.CatalogDiscoverySpec(mediaKind=com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE),movies.map{com.aliflix.app.recommendation.RecommendationCandidate(it)},activeRequest=request)
  },{},{},{opened=it},emptyList(),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={emptyList()})}}
  compose.onNodeWithText("Surprise Me",useUnmergedTree=true).performClick()
  compose.onNodeWithTag("ask-surprise-three").assertIsDisplayed()
  compose.onNodeWithText("Your next three").assertIsDisplayed()
  compose.runOnIdle{assertNull(opened)}
  compose.onNodeWithContentDescription("Close discoveries").performClick()
  compose.onNodeWithTag("ask-surprise-three").assertDoesNotExist()
 }
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
 @Test fun verifiedPostersAreInteractiveWhileFurtherBatchesContinue(){
  val first=Media(27205,MediaType.MOVIE,"Inception",year="2010")
  val next=Media(157336,MediaType.MOVIE,"Interstellar",year="2014")
  var state by mutableStateOf(AskAliflixUiState.Results("space",com.aliflix.app.recommendation.CatalogDiscoverySpec(mediaKind=com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE),
   listOf(com.aliflix.app.recommendation.RecommendationCandidate(first)),researching=true,hasMore=false))
  var opened:Media?=null
  compose.setContent{AliflixMobileTheme{UnifiedAskAliflix(state,AskAliflixEditorState(),{},{},{},{},{opened=it},emptyList(),false,{}, {emptyList()},rememberLazyListState(),emptySet(),{},Modifier,dailyProvider={emptyList()})}}
  compose.onNodeWithText("Inception").performScrollTo().performClick()
  compose.runOnIdle{assertEquals(first,opened);state=state.copy(items=state.items+com.aliflix.app.recommendation.RecommendationCandidate(next),researching=false)}
  compose.onNodeWithText("Interstellar").performScrollTo().assertIsDisplayed()
  compose.onNodeWithTag("ask-research-continuing").assertDoesNotExist()
 }
}
