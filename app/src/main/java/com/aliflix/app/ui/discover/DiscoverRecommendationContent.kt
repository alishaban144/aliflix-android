@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.SearchMode
import com.aliflix.app.SearchPhase
import com.aliflix.app.SearchUiState
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.ConstraintRelaxation
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.recommendation.RecommendationContentType
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationPreferences
import com.aliflix.app.recommendation.RecommendationQuestion
import com.aliflix.app.recommendation.RecommendationQuestionType
import com.aliflix.app.recommendation.RecommendationSourceHealth
import com.aliflix.app.recommendation.RecommendationSourceStatus
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.recommendation.SemanticModelState
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixBorderStrong
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfacePrimary
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged


@Composable
internal fun RecommendationContent(
    state: RecommendationUiState,
    suggestions: List<DiscoverSuggestion>,
    semanticModelState: SemanticModelState,
    shouldOfferSemanticModel: Boolean,
    onSuggestion: (DiscoverSuggestion) -> Unit,
    onSurprise: () -> Unit,
    onAnswer: (RecommendationQuestion, List<String>) -> Unit,
    onShowMatches: () -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Media) -> Unit,
    onLoadMore: () -> Unit,
    onRetryPage: () -> Unit,
    onRelax: (String) -> Unit,
    onDownloadSemanticModel: () -> Unit,
    onDismissSemanticModelOffer: () -> Unit,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onSeen: (Media) -> Unit,
    onCorrectPreference: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = shouldOfferSemanticModel ||
                semanticModelState is SemanticModelState.Downloading,
        ) {
            SemanticModelNotice(
                state = semanticModelState,
                onDownload = onDownloadSemanticModel,
                onDismiss = onDismissSemanticModelOffer,
            )
        }

        when (state) {
            RecommendationUiState.Idle,
            is RecommendationUiState.SelectType,
            -> RecommendationIdle(
                selectedKind = state.preferencesOrNull()?.selectedMediaKind(),
                suggestions = suggestions,
                onSuggestion = onSuggestion,
                onSurprise = onSurprise,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-idle"),
            )
            is RecommendationUiState.Discovering -> RecommendationLoading(
                message = state.message,
                preferences = state.preferences,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-loading"),
            )
            is RecommendationUiState.Question -> RecommendationQuestionContent(
                state = state,
                onAnswer = onAnswer,
                onShowMatches = onShowMatches,
                onBack = onBack,
                onCorrectPreference = onCorrectPreference,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-question"),
            )
            is RecommendationUiState.Results -> RecommendationResults(
                state = state,
                onOpen = onOpen,
                onLoadMore = onLoadMore,
                onRetryPage = onRetryPage,
                onRestart = onRestart,
                onMoreLike = onMoreLike,
                onLessLike = onLessLike,
                onSeen = onSeen,
                onCorrectPreference = onCorrectPreference,
                listState = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-results"),
            )
            is RecommendationUiState.Empty -> RecommendationEmpty(
                message = state.message,
                options = state.options,
                onRelax = onRelax,
                onRestart = onRestart,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-low-confidence"),
            )
            is RecommendationUiState.SourceUnavailable -> RecommendationFailure(
                eyebrow = "SOURCES UNAVAILABLE",
                title = "Recommendations could not be verified",
                message = state.message,
                canRetry = state.canRetry,
                onRetry = onRetry,
                onRestart = onRestart,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-source-error"),
            )
            is RecommendationUiState.Relaxation -> RecommendationEmpty(
                message = state.message,
                options = state.options,
                onRelax = onRelax,
                onRestart = onRestart,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-relaxation"),
            )
            is RecommendationUiState.Error -> RecommendationFailure(
                eyebrow = "RECOMMENDATION ERROR",
                title = "This request did not finish",
                message = state.message,
                canRetry = state.canRetry,
                onRetry = onRetry,
                onRestart = onRestart,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover-recommendation-error"),
            )
        }
    }
}


@Composable
private fun RecommendationIdle(
    selectedKind: RecommendationMediaKind?,
    suggestions: List<DiscoverSuggestion>,
    onSuggestion: (DiscoverSuggestion) -> Unit,
    onSurprise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "recommend-intro", contentType = "intro") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (selectedKind == null) {
                        "Choose Movies or Series, then tell us the fit."
                    } else {
                        "Use a mood, a limit, an exclusion, or a title you already love."
                    },
                    color = AliflixContentSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                if (selectedKind != null) {
                    OutlinedButton(
                        onClick = onSurprise,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("discover-surprise-me"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("Surprise me")
                    }
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            item(key = "recommend-try-one", contentType = "suggestion-carousel") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DiscoverSectionHeader(
                        title = "Try one",
                        subtitle = "Suggestions change with each app session",
                    )
                    TryOneCarousel(
                        suggestions = suggestions,
                        onSuggestion = onSuggestion,
                    )
                }
            }
        }
        item(key = "request-tips", contentType = "tips") {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = AliflixSurfacePrimary,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = "Requests that work well",
                        color = AliflixContentPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    listOf(
                        "“Like a named title, but shorter”",
                        "“A hidden gem without graphic violence”",
                        "“Warm, funny, and under two hours”",
                    ).forEach { tip ->
                        Text(
                            text = tip,
                            color = AliflixContentSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun RecommendationLoading(
    message: String,
    preferences: RecommendationPreferences,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "loading-status", contentType = "status") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = AliflixAccentSecondary,
                )
                Column {
                    Text(
                        text = "Building a ranked shortlist",
                        color = AliflixContentPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = message,
                        color = AliflixContentSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        item(key = "loading-preferences", contentType = "preferences") {
            PreferenceSummary(preferences = preferences)
        }
        item(key = "hero-skeleton", contentType = "skeleton") {
            RecommendationHeroSkeleton()
        }
        items(count = 3, key = { "result-skeleton:$it" }, contentType = { "skeleton" }) {
            RecommendationRowSkeleton()
        }
    }
}


@Composable
private fun RecommendationQuestionContent(
    state: RecommendationUiState.Question,
    onAnswer: (RecommendationQuestion, List<String>) -> Unit,
    onShowMatches: () -> Unit,
    onBack: () -> Unit,
    onCorrectPreference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedQuestionId by rememberSaveable { mutableStateOf("") }
    var selectedValues by rememberSaveable { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.question.id) {
        if (selectedQuestionId != state.question.id) {
            selectedQuestionId = state.question.id
            selectedValues = emptySet()
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "question-progress", contentType = "status") {
            Text(
                text = state.progressMessage,
                color = AliflixAccentSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        item(key = "question-preferences", contentType = "preferences") {
            PreferenceSummary(
                preferences = state.preferences,
                onRemove = onCorrectPreference,
            )
        }
        item(key = "question", contentType = "question") {
            Surface(
                color = AliflixSurfaceElevated,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = state.question.text,
                        color = AliflixContentPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    state.question.supportingText?.let { supporting ->
                        Text(
                            text = supporting,
                            color = AliflixContentSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.question.options.forEachIndexed { index, option ->
                            val selected = option.value in selectedValues
                            DiscoverFilterChip(
                                label = option.label,
                                selected = selected,
                                onClick = {
                                    selectedValues = if (
                                        state.question.type == RecommendationQuestionType.SINGLE_SELECT
                                    ) {
                                        setOf(option.value)
                                    } else if (selected) {
                                        selectedValues - option.value
                                    } else {
                                        selectedValues + option.value
                                    }
                                },
                                modifier = Modifier.testTag(
                                    if (index == 0) {
                                        "discover-preference-action"
                                    } else {
                                        "discover-question-${option.id}"
                                    },
                                ),
                            )
                        }
                    }
                    Button(
                        enabled = selectedValues.isNotEmpty(),
                        onClick = {
                            onAnswer(state.question, selectedValues.toList())
                            selectedValues = emptySet()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("discover-question-continue"),
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
        item(key = "question-actions", contentType = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.canGoBack) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Back")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onShowMatches,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("discover-show-matches"),
                ) {
                    Text("Show best matches now")
                }
            }
        }
    }
}


@Composable
private fun RecommendationResults(
    state: RecommendationUiState.Results,
    onOpen: (Media) -> Unit,
    onLoadMore: () -> Unit,
    onRetryPage: () -> Unit,
    onRestart: () -> Unit,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onSeen: (Media) -> Unit,
    onCorrectPreference: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val tailSignature = remember(state.preferences, state.candidates) {
        state.candidates.lastOrNull()?.media?.key
            ?.let { key -> "${state.preferences.hashCode()}:$key:${state.candidates.size}" }
    }
    var consumedTailSignature by rememberSaveable { mutableStateOf<String?>(null) }
    val isNearEnd by remember(state.candidates.size) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?.let { it >= state.candidates.lastIndex - 2 } == true
        }
    }
    LaunchedEffect(
        listState,
        tailSignature,
        state.hasMore,
        state.loadingMore,
        state.refreshing,
        state.pageError,
    ) {
        snapshotFlow { isNearEnd }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (!nearEnd) {
                    consumedTailSignature = null
                } else if (
                    tailSignature != null &&
                    consumedTailSignature != tailSignature &&
                    state.hasMore &&
                    !state.loadingMore &&
                    !state.refreshing &&
                    state.pageError == null
                ) {
                    consumedTailSignature = tailSignature
                    onLoadMore()
                }
            }
    }

    val top = state.candidates.firstOrNull()
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("discover-results-list"),
        contentPadding = PaddingValues(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "result-heading", contentType = "heading") {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ranked for this request",
                            color = AliflixContentPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "${state.candidates.size} verified matches so far",
                            color = AliflixContentSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(
                        onClick = onRestart,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("New request")
                    }
                }
                PreferenceSummary(
                    preferences = state.preferences,
                    onRemove = onCorrectPreference,
                )
            }
        }
        if (state.sourceHealth.isPartial()) {
            item(key = "partial-sources", contentType = "notice") {
                InlineNotice(
                    title = "Some sources are limited",
                    message = "The ranking uses the evidence currently available. You can keep browsing these matches.",
                    actionLabel = null,
                    onAction = null,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("discover-recommendation-partial"),
                )
            }
        }
        if (top != null) {
            item(key = "top:${top.media.key}", contentType = "recommendation-hero") {
                RecommendationHero(
                    candidate = top,
                    onOpen = onOpen,
                    onMoreLike = onMoreLike,
                    onLessLike = onLessLike,
                    onSeen = onSeen,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        itemsIndexed(
            items = state.candidates.drop(1),
            key = { _, item -> "ranked:${item.media.key}" },
            contentType = { _, _ -> "recommendation-row" },
        ) { index, candidate ->
            val rank = index + 2
            RecommendationResultRow(
                rank = rank,
                candidate = candidate,
                onOpen = onOpen,
                onMoreLike = onMoreLike,
                onLessLike = onLessLike,
                onSeen = onSeen,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (state.loadingMore || state.refreshing) {
            item(key = "append-loading", contentType = "append-status") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .testTag("discover-load-more"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AliflixAccentSecondary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (state.loadingMore) "Ranking the next matches" else "Refreshing evidence",
                        color = AliflixContentSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        state.pageError?.let { message ->
            item(key = "page-error", contentType = "append-status") {
                InlineNotice(
                    title = "The next page did not load",
                    message = message,
                    actionLabel = "Retry page",
                    onAction = onRetryPage,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("discover-recommendation-page-error"),
                )
            }
        }
    }
}


@Composable
private fun RecommendationHero(
    candidate: RecommendationCandidate,
    onOpen: (Media) -> Unit,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onSeen: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = candidate.media
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button) { onOpen(item) }
            .testTag("discover-top-match-${item.key}")
            .semantics { contentDescription = "Open top match ${item.title}" },
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            AliflixAccentPrimary.copy(alpha = 0.55f),
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 8.5f),
            ) {
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, AliflixSurfaceElevated),
                            ),
                        ),
                )
                Text(
                    text = "TOP MATCH",
                    color = AliflixContentPrimary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                        .clip(CircleShape)
                        .background(AliflixAccentPrimary.copy(alpha = 0.86f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Column(
                modifier = Modifier.padding(start = 17.dp, end = 17.dp, bottom = 17.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = item.title,
                    color = AliflixContentPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ReliableMetadata(item = item, candidate = candidate)
                candidateReason(candidate)?.let { reason ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "WHY THIS MATCHES",
                            color = AliflixAccentSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = reason,
                            color = AliflixContentSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FeedbackActions(
                    media = item,
                    onMoreLike = onMoreLike,
                    onLessLike = onLessLike,
                    onSeen = onSeen,
                )
            }
        }
    }
}


@Composable
private fun RecommendationResultRow(
    rank: Int,
    candidate: RecommendationCandidate,
    onOpen: (Media) -> Unit,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onSeen: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = candidate.media
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AliflixSurfacePrimary,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(item) }
                    .semantics { contentDescription = "Open rank $rank ${item.title}" },
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Box {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(82.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(13.dp))
                            .background(AliflixSurfaceSecondary),
                    )
                    Text(
                        text = "#$rank",
                        color = AliflixContentPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(AliflixAccentPrimary.copy(alpha = 0.88f))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = item.title,
                        color = AliflixContentPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ReliableMetadata(item = item, candidate = candidate)
                    candidateReason(candidate)?.let { reason ->
                        Text(
                            text = reason,
                            color = AliflixContentSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            FeedbackActions(
                media = item,
                onMoreLike = onMoreLike,
                onLessLike = onLessLike,
                onSeen = onSeen,
            )
        }
    }
}


@Composable
internal fun FeedbackActions(
    media: Media,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onSeen: (Media) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            Triple("More like this", "more", onMoreLike),
            Triple("Less like this", "less", onLessLike),
            Triple("Already seen", "seen", onSeen),
        ).forEach { (label, tag, action) ->
            TextButton(
                onClick = { action(media) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("discover-feedback-$tag-${media.key}"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}


@Composable
private fun ReliableMetadata(
    item: Media,
    candidate: RecommendationCandidate,
) {
    val metadata = remember(item, candidate.metadata) {
        buildList {
            item.year.takeIf(String::isNotBlank)?.let(::add)
            val runtime = candidate.metadata.runtimeMinutes?.let { "$it min" }
                ?: item.runtime.takeIf(String::isNotBlank)
            runtime?.let(::add)
            item.genres.take(2).joinToString(" · ")
                .takeIf(String::isNotBlank)
                ?.let(::add)
            item.imdbRating?.takeIf { it > 0.0 }?.let { add("IMDb ${"%.1f".format(it)}") }
        }
    }
    if (metadata.isNotEmpty()) {
        Text(
            text = metadata.joinToString("  •  "),
            color = AliflixContentTertiary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


private fun candidateReason(candidate: RecommendationCandidate): String? =
    candidate.matchReasons
        .sortedByDescending { it.contribution }
        .map { it.text.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .take(2)
        .joinToString(" · ")
        .takeIf(String::isNotBlank)
        ?: candidate.explanation.trim().takeIf(String::isNotBlank)
        ?: candidate.evidence.trim().takeIf(String::isNotBlank)


@Composable
private fun RecommendationEmpty(
    message: String,
    options: List<ConstraintRelaxation>,
    onRelax: (String) -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "empty-copy", contentType = "status") {
            DiscoverStateMessage(
                eyebrow = "LOW CONFIDENCE",
                title = "A shorter honest list is better",
                message = message,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (options.isNotEmpty()) {
            item(key = "relax-heading", contentType = "heading") {
                Text(
                    text = "Useful ways to broaden it",
                    color = AliflixContentPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(options, key = ConstraintRelaxation::id, contentType = { "relaxation" }) { option ->
                OutlinedButton(
                    onClick = { onRelax(option.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                ) {
                    Text(
                        text = option.label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                    Text("+${option.recoveredCandidates}")
                }
            }
        }
        item(key = "empty-restart", contentType = "actions") {
            TextButton(onClick = onRestart, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Start a different request")
            }
        }
    }
}


@Composable
private fun RecommendationFailure(
    eyebrow: String,
    title: String,
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DiscoverStateMessage(
            eyebrow = eyebrow,
            title = title,
            message = message,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .heightIn(min = 50.dp)
                        .testTag("discover-retry"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
            OutlinedButton(onClick = onRestart, modifier = Modifier.heightIn(min = 50.dp)) {
                Text("New request")
            }
        }
    }
}


@Composable
private fun SemanticModelNotice(
    state: SemanticModelState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloading = state as? SemanticModelState.Downloading
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        color = AliflixAccentSecondary.copy(alpha = 0.09f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            AliflixAccentSecondary.copy(alpha = 0.26f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (downloading != null) "Adding deeper language matching" else "Improve mood matching",
                    color = AliflixContentPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (downloading != null) {
                        "${downloading.progressPercent}% downloaded"
                    } else {
                        "Optional on-device semantic model"
                    },
                    color = AliflixContentSecondary,
                    fontSize = 10.sp,
                )
            }
            if (downloading == null) {
                TextButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Download")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss model offer")
                }
            } else {
                CircularProgressIndicator(
                    progress = { downloading.progressPercent / 100f },
                    modifier = Modifier.size(25.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}


@Composable
private fun PreferenceSummary(
    preferences: RecommendationPreferences,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null,
) {
    val chips = remember(preferences) { preferenceChips(preferences) }
    if (chips.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(chips, key = { "${it.key}:${it.label}" }, contentType = { "preference-chip" }) { chip ->
            AssistChip(
                onClick = {
                    if (onRemove != null && chip.key != null) onRemove(chip.key)
                },
                enabled = onRemove != null && chip.key != null,
                label = {
                    Text(
                        text = chip.label,
                        maxLines = 1,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                trailingIcon = if (onRemove != null && chip.key != null) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove ${chip.label}",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else {
                    null
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = AliflixAccentPrimary.copy(alpha = 0.14f),
                    labelColor = AliflixContentSecondary,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = AliflixAccentPrimary.copy(alpha = 0.28f),
                ),
            )
        }
    }
}

private data class PreferenceChip(val key: String?, val label: String)


private fun preferenceChips(preferences: RecommendationPreferences): List<PreferenceChip> =
    buildList {
        preferences.contentType?.value?.let { type ->
            add(
                PreferenceChip(
                    key = null,
                    label = when (type) {
                        RecommendationContentType.MOVIE -> "Movies"
                        RecommendationContentType.TV -> "Series"
                        RecommendationContentType.EITHER -> "Movies or series"
                    },
                ),
            )
        }
        addAll(preferences.moods.map { PreferenceChip("mood:${it.value.name}", it.value.label) })
        addAll(preferences.includedGenres.map { PreferenceChip("genre:${it.value}", it.value) })
        addAll(
            preferences.excludedGenres.map {
                PreferenceChip("excluded_genre:${it.value}", "No ${it.value.lowercase()}")
            },
        )
        addAll(preferences.semanticFacets.map { PreferenceChip("facet:${it.value.id}", it.value.label) })
        preferences.viewingContext?.let { add(PreferenceChip("context", it.value.label)) }
        preferences.runtimeMaximumMinutes?.let {
            add(PreferenceChip("runtime_max", "Up to ${it.value} min"))
        }
        preferences.runtimeMinimumMinutes?.let {
            add(PreferenceChip("runtime_min", "At least ${it.value} min"))
        }
        preferences.yearMinimum?.let { add(PreferenceChip("year_min", "${it.value}+")) }
        preferences.yearMaximum?.let { add(PreferenceChip("year_max", "Through ${it.value}")) }
        preferences.similarityTitle?.let { add(PreferenceChip("similarity", "Like ${it.value}")) }
    }.distinctBy { it.key to it.label }.take(14)


@Composable
private fun RecommendationHeroSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 8.5f)
                .clip(RoundedCornerShape(22.dp)),
        )
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(20.dp)
                .clip(CircleShape),
        )
    }
}


@Composable
private fun RecommendationRowSkeleton() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ShimmerBox(
            modifier = Modifier
                .width(82.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(13.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(16.dp)
                    .clip(CircleShape),
            )
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape),
            )
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(12.dp)
                    .clip(CircleShape),
            )
        }
    }
}
