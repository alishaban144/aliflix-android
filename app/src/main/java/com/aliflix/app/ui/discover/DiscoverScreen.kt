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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.launch

@Composable
internal fun DiscoverScreen(
    state: SearchUiState,
    recommendationState: RecommendationUiState,
    aiEnabled: Boolean,
    homeContent: HomeContent?,
    recent: List<Media>,
    suggestionOrder: List<String>,
    semanticModelState: SemanticModelState,
    shouldOfferSemanticModel: Boolean,
    focusRequestId: Int?,
    onFocusRequestConsumed: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onOpen: (Media) -> Unit,
    onSelectRecommendationType: (RecommendationMediaKind) -> Unit,
    onSubmitRecommendation: (String) -> Unit,
    onSurpriseRecommendation: () -> Unit,
    onAnswerRecommendation: (RecommendationQuestion, List<String>) -> Unit,
    onShowRecommendationMatches: () -> Unit,
    onPreviousRecommendationStep: () -> Unit,
    onRestartRecommendations: () -> Unit,
    onRetryRecommendations: () -> Unit,
    onLoadMoreRecommendations: () -> Unit,
    onRetryRecommendationPage: () -> Unit,
    onRelaxRecommendation: (String) -> Unit,
    onDownloadSemanticModel: () -> Unit,
    onDismissSemanticModelOffer: () -> Unit,
    onMoreLikeRecommendation: (Media) -> Unit,
    onLessLikeRecommendation: (Media) -> Unit,
    onRecommendationSeen: (Media) -> Unit,
    onCorrectRecommendationPreference: (String) -> Unit,
    catalogGridState: LazyGridState,
    recommendationListState: LazyListState,
    mediaFilter: String,
    onMediaFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length),
            ),
        )
    }
    val preferences = recommendationState.preferencesOrNull()
    val recommendationKind = preferences?.selectedMediaKind()
    val suggestionKind = if (state.mode == SearchMode.AI) {
        recommendationKind
    } else {
        when (mediaFilter) {
            "Movies" -> RecommendationMediaKind.MOVIE
            "Series" -> RecommendationMediaKind.SERIES
            else -> null
        }
    }
    val suggestions = remember(suggestionOrder, suggestionKind) {
        suggestionsForSession(suggestionOrder, suggestionKind)
    }

    LaunchedEffect(state.query) {
        if (fieldValue.text != state.query) {
            fieldValue = TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length),
            )
        }
    }

    LaunchedEffect(focusRequestId) {
        val requestId = focusRequestId ?: return@LaunchedEffect
        withFrameNanos { }
        fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
        focusRequester.requestFocus()
        keyboard?.show()
        onFocusRequestConsumed(requestId)
    }

    fun submitRecommendation(prompt: String, kind: RecommendationMediaKind? = null) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        val requestedKind = kind ?: recommendationKind ?: return
        if (state.mode != SearchMode.AI) onModeChange(SearchMode.AI)
        if (recommendationKind != requestedKind) onSelectRecommendationType(requestedKind)
        onQueryChange(cleanPrompt)
        onSubmitRecommendation(cleanPrompt)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBackgroundBase),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            AliflixAccentPrimary.copy(alpha = 0.13f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Discover",
                        color = AliflixContentPrimary,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = if (state.mode == SearchMode.AI) {
                            "Tell Aliflix what should make the cut"
                        } else {
                            "Search directly or explore what is waiting"
                        },
                        color = AliflixContentSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            DiscoverModeSelector(
                selected = state.mode,
                aiEnabled = aiEnabled,
                onSelect = onModeChange,
            )

            OutlinedTextField(
                value = fieldValue,
                onValueChange = { updated ->
                    fieldValue = updated
                    onQueryChange(updated.text)
                },
                placeholder = {
                    Text(
                        text = if (state.mode == SearchMode.AI) {
                            "Mood, constraints, or something like a title..."
                        } else {
                            "Title, actor, year, or keyword"
                        },
                        color = AliflixContentTertiary,
                        fontSize = 14.sp,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.mode == SearchMode.AI) {
                            Icons.Rounded.AutoAwesome
                        } else {
                            Icons.Filled.Search
                        },
                        contentDescription = null,
                        tint = if (state.mode == SearchMode.AI) {
                            AliflixAccentSecondary
                        } else {
                            AliflixContentSecondary
                        },
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (fieldValue.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    fieldValue = TextFieldValue("")
                                    onQueryChange("")
                                },
                                modifier = Modifier.testTag("discover-clear-search"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                        if (state.mode == SearchMode.AI) {
                            IconButton(
                                enabled = fieldValue.text.isNotBlank() &&
                                    recommendationKind != null,
                                onClick = {
                                    submitRecommendation(fieldValue.text)
                                    keyboard?.hide()
                                },
                                modifier = Modifier.testTag("discover-recommend-submit"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = "Find recommendations",
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (state.mode == SearchMode.AI) {
                            submitRecommendation(fieldValue.text)
                        }
                        keyboard?.hide()
                    },
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = AliflixContentPrimary,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AliflixSurfaceElevated,
                    unfocusedContainerColor = AliflixSurfaceSecondary,
                    focusedBorderColor = AliflixAccentPrimary,
                    unfocusedBorderColor = AliflixBorderSubtle,
                    cursorColor = AliflixAccentSecondary,
                    focusedTextColor = AliflixContentPrimary,
                    unfocusedTextColor = AliflixContentPrimary,
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .focusRequester(focusRequester)
                    .testTag("discover-search-field"),
            )

            if (state.mode == SearchMode.AI) {
                RecommendationTypeSelector(
                    selected = recommendationKind,
                    onSelect = onSelectRecommendationType,
                )
            } else {
                CatalogueTypeSelector(
                    selected = mediaFilter,
                    onSelect = onMediaFilterChange,
                )
            }
        }

        AnimatedContent(
            targetState = state.mode,
            transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
            label = "discover-mode-content",
            modifier = Modifier.weight(1f),
        ) { mode ->
            if (mode == SearchMode.AI) {
                RecommendationContent(
                    state = recommendationState,
                    suggestions = suggestions,
                    semanticModelState = semanticModelState,
                    shouldOfferSemanticModel = shouldOfferSemanticModel,
                    onSuggestion = { suggestion ->
                        submitRecommendation(suggestion.prompt, suggestion.mediaKind)
                    },
                    onSurprise = onSurpriseRecommendation,
                    onAnswer = onAnswerRecommendation,
                    onShowMatches = onShowRecommendationMatches,
                    onBack = onPreviousRecommendationStep,
                    onRestart = {
                        onRestartRecommendations()
                        onQueryChange("")
                    },
                    onRetry = onRetryRecommendations,
                    onOpen = onOpen,
                    onLoadMore = onLoadMoreRecommendations,
                    onRetryPage = onRetryRecommendationPage,
                    onRelax = onRelaxRecommendation,
                    onDownloadSemanticModel = onDownloadSemanticModel,
                    onDismissSemanticModelOffer = onDismissSemanticModelOffer,
                    onMoreLike = onMoreLikeRecommendation,
                    onLessLike = onLessLikeRecommendation,
                    onSeen = onRecommendationSeen,
                    onCorrectPreference = onCorrectRecommendationPreference,
                    listState = recommendationListState,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CatalogueContent(
                    state = state,
                    mediaFilter = mediaFilter,
                    homeContent = homeContent,
                    recent = recent,
                    suggestions = suggestions,
                    aiEnabled = aiEnabled,
                    onSuggestion = { suggestion ->
                        submitRecommendation(suggestion.prompt, suggestion.mediaKind)
                    },
                    onOpen = onOpen,
                    gridState = catalogGridState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DiscoverModeSelector(
    selected: SearchMode,
    aiEnabled: Boolean,
    onSelect: (SearchMode) -> Unit,
) {
    Surface(
        color = AliflixSurfacePrimary.copy(alpha = 0.90f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                SearchMode.TITLE to "Catalogue",
                SearchMode.AI to "Recommend",
            ).forEach { (mode, label) ->
                val enabled = mode != SearchMode.AI || aiEnabled
                val active = selected == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (active) {
                                Brush.horizontalGradient(
                                    listOf(
                                        AliflixAccentPrimary.copy(alpha = 0.35f),
                                        AliflixAccentSecondary.copy(alpha = 0.15f),
                                    ),
                                )
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                        )
                        .selectable(
                            selected = active,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(mode) },
                        )
                        .testTag(
                            if (mode == SearchMode.TITLE) {
                                "discover-mode-catalogue"
                            } else {
                                "discover-mode-recommend"
                            },
                        )
                        .semantics {
                            stateDescription = if (active) "Selected" else "Not selected"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (mode == SearchMode.TITLE) {
                                Icons.Filled.Search
                            } else {
                                Icons.Rounded.AutoAwesome
                            },
                            contentDescription = null,
                            tint = if (active) AliflixContentPrimary else AliflixContentTertiary,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = label,
                            color = when {
                                !enabled -> AliflixContentTertiary.copy(alpha = 0.45f)
                                active -> AliflixContentPrimary
                                else -> AliflixContentSecondary
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogueTypeSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("All", "Movies", "Series").forEach { option ->
            val active = option == selected
            DiscoverFilterChip(
                label = option,
                selected = active,
                onClick = { onSelect(option) },
                modifier = Modifier.testTag("discover-filter-${option.lowercase()}")
            )
        }
    }
}

@Composable
private fun RecommendationTypeSelector(
    selected: RecommendationMediaKind?,
    onSelect: (RecommendationMediaKind) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Recommend",
            color = AliflixContentTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        RecommendationMediaKind.entries.forEach { kind ->
            DiscoverFilterChip(
                label = if (kind == RecommendationMediaKind.MOVIE) "Movies" else "Series",
                selected = selected == kind,
                onClick = { onSelect(kind) },
                modifier = Modifier.testTag(
                    if (kind == RecommendationMediaKind.MOVIE) {
                        "discover-type-movie"
                    } else {
                        "discover-type-series"
                    },
                ),
            )
        }
        if (selected == null) {
            Text(
                text = "Choose one",
                color = AliflixAccentSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
internal fun DiscoverFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected) AliflixAccentPrimary.copy(alpha = 0.22f)
                else AliflixSurfaceSecondary,
            )
            .border(
                1.dp,
                if (selected) AliflixAccentPrimary.copy(alpha = 0.72f)
                else AliflixBorderSubtle,
                RoundedCornerShape(15.dp),
            )
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CatalogueContent(
    state: SearchUiState,
    mediaFilter: String,
    homeContent: HomeContent?,
    recent: List<Media>,
    suggestions: List<DiscoverSuggestion>,
    aiEnabled: Boolean,
    onSuggestion: (DiscoverSuggestion) -> Unit,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val filteredResults = remember(state.results, mediaFilter) {
        state.results.filter { item ->
            when (mediaFilter) {
                "Movies" -> item.type == MediaType.MOVIE
                "Series" -> item.type == MediaType.TV
                else -> true
            }
        }
    }

    if (state.query.isBlank()) {
        DiscoverIdleContent(
            homeContent = homeContent,
            recent = recent,
            mediaFilter = mediaFilter,
            suggestions = suggestions,
            showTryOne = aiEnabled,
            onSuggestion = onSuggestion,
            onOpen = onOpen,
            modifier = modifier.testTag("discover-idle"),
        )
        return
    }

    Column(modifier = modifier) {
        when {
            state.phase == SearchPhase.LOADING && state.results.isEmpty() -> {
                CatalogueSkeletonGrid(modifier = Modifier.fillMaxSize())
                return@Column
            }
            state.phase == SearchPhase.EMPTY -> {
                DiscoverStateMessage(
                    eyebrow = "NO MATCHES",
                    title = "Nothing matched that search",
                    message = "Try a shorter title, a different spelling, or switch to Recommend for a more descriptive request.",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("discover-catalogue-empty"),
                )
                return@Column
            }
            state.phase == SearchPhase.ERROR && state.results.isEmpty() -> {
                DiscoverStateMessage(
                    eyebrow = "SEARCH UNAVAILABLE",
                    title = "The catalogue did not answer",
                    message = state.error.orEmpty(),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("discover-catalogue-error"),
                )
                return@Column
            }
        }

        if (state.phase == SearchPhase.LOADING || state.phase == SearchPhase.TYPING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("discover-catalogue-loading"),
                color = AliflixAccentSecondary,
                trackColor = Color.Transparent,
            )
        }
        if (state.phase == SearchPhase.ERROR && state.error != null) {
            InlineNotice(
                title = "Could not refresh these results",
                message = state.error,
                actionLabel = null,
                onAction = null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (state.phase) {
                    SearchPhase.TYPING -> "Keeping your last matches while you type"
                    SearchPhase.LOADING -> "Updating matches"
                    else -> "Best catalogue matches"
                },
                color = AliflixContentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${filteredResults.size}",
                color = AliflixContentTertiary,
                fontSize = 11.sp,
            )
        }
        if (filteredResults.isEmpty()) {
            DiscoverStateMessage(
                eyebrow = mediaFilter.uppercase(),
                title = "No $mediaFilter in these matches",
                message = "Choose another media filter to see the rest.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 2.dp,
                    bottom = 32.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("discover-catalogue-results"),
            ) {
                items(
                    items = filteredResults,
                    key = Media::key,
                    contentType = { "catalogue-poster" },
                ) { item ->
                    DiscoverPosterCard(item = item, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun DiscoverIdleContent(
    homeContent: HomeContent?,
    recent: List<Media>,
    mediaFilter: String,
    suggestions: List<DiscoverSuggestion>,
    showTryOne: Boolean,
    onSuggestion: (DiscoverSuggestion) -> Unit,
    onOpen: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accepts: (Media) -> Boolean = remember(mediaFilter) {
        { item ->
            when (mediaFilter) {
                "Movies" -> item.type == MediaType.MOVIE
                "Series" -> item.type == MediaType.TV
                else -> true
            }
        }
    }
    val recentItems = remember(recent, mediaFilter) { recent.filter(accepts).take(12) }
    val rails = remember(homeContent, mediaFilter) {
        homeContent?.rails.orEmpty()
            .map { rail -> rail.copy(items = rail.items.filter(accepts).distinctBy(Media::key)) }
            .filter { it.items.isNotEmpty() }
            .take(3)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (showTryOne && suggestions.isNotEmpty()) {
            item(key = "try-one", contentType = "suggestion-carousel") {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiscoverSectionHeader(
                        title = "Try one",
                        subtitle = "A fresh starting point — tap to run it now",
                    )
                    TryOneCarousel(
                        suggestions = suggestions,
                        onSuggestion = onSuggestion,
                    )
                }
            }
        }
        if (recentItems.isNotEmpty()) {
            item(key = "recent", contentType = "media-rail") {
                DiscoverMediaRail(
                    title = "Pick up where you left off",
                    subtitle = "Your recent activity",
                    items = recentItems,
                    onOpen = onOpen,
                )
            }
        }
        items(
            items = rails,
            key = { "rail:${it.title}" },
            contentType = { "media-rail" },
        ) { rail ->
            DiscoverMediaRail(
                title = rail.title,
                subtitle = null,
                items = rail.items.take(14),
                onOpen = onOpen,
            )
        }
        if (!showTryOne && recentItems.isEmpty() && rails.isEmpty()) {
            item(key = "catalogue-warming", contentType = "status") {
                InlineNotice(
                    title = "Discovery is warming up",
                    message = "Start with a title above. Catalogue collections will appear here as they become available.",
                    actionLabel = null,
                    onAction = null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun TryOneCarousel(
    suggestions: List<DiscoverSuggestion>,
    onSuggestion: (DiscoverSuggestion) -> Unit,
) {
    val signature = remember(suggestions) { suggestions.joinToString("|") { it.id } }
    key(signature) {
        val pagerState = rememberPagerState(pageCount = { suggestions.size })
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val isInteractionActive by rememberUpdatedState(
            pressed || pagerState.isScrollInProgress,
        )
        val carouselScope = rememberCoroutineScope()

        LaunchedEffect(signature) {
            if (suggestions.size < 2) return@LaunchedEffect
            while (true) {
                delay(6_000)
                if (!isInteractionActive) {
                    pagerState.animateScrollToPage((pagerState.currentPage + 1) % suggestions.size)
                }
            }
        }

        Column(
            modifier = Modifier
                .testTag("discover-try-one-carousel")
                .semantics {
                    contentDescription = "Try one suggestions"
                    stateDescription =
                        "Suggestion ${pagerState.currentPage + 1} of ${suggestions.size}: " +
                            suggestions[pagerState.currentPage].prompt
                },
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 10.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val suggestion = suggestions[page]
                val typeLabel = if (suggestion.mediaKind == RecommendationMediaKind.MOVIE) {
                    "MOVIE"
                } else {
                    "SERIES"
                }
                Surface(
                    color = AliflixSurfaceElevated,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        AliflixAccentPrimary.copy(alpha = 0.42f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = { onSuggestion(suggestion) },
                        )
                        .testTag("discover-try-one-card")
                        .semantics {
                            contentDescription =
                                "Try $typeLabel recommendation: ${suggestion.prompt}"
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            AliflixAccentPrimary.copy(alpha = 0.55f),
                                            AliflixAccentSecondary.copy(alpha = 0.30f),
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = AliflixContentPrimary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = typeLabel,
                                color = AliflixAccentSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                            )
                            Text(
                                text = suggestion.prompt,
                                color = AliflixContentPrimary,
                                fontSize = 16.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = AliflixContentSecondary,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = pagerState.currentPage > 0,
                    interactionSource = interactionSource,
                    onClick = {
                        carouselScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("discover-try-one-previous"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Previous suggestion",
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    suggestions.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (pagerState.currentPage == index) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pagerState.currentPage == index) {
                                        AliflixAccentSecondary
                                    } else {
                                        AliflixBorderStrong
                                    },
                                ),
                        )
                    }
                }
                IconButton(
                    enabled = pagerState.currentPage < suggestions.lastIndex,
                    interactionSource = interactionSource,
                    onClick = {
                        carouselScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("discover-try-one-next"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "Next suggestion",
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverMediaRail(
    title: String,
    subtitle: String?,
    items: List<Media>,
    onOpen: (Media) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DiscoverSectionHeader(title = title, subtitle = subtitle)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(
                items = items,
                key = Media::key,
                contentType = { "discover-rail-poster" },
            ) { item ->
                DiscoverPosterCard(
                    item = item,
                    onOpen = onOpen,
                    modifier = Modifier.width(108.dp),
                )
            }
        }
    }
}

@Composable
internal fun DiscoverSectionHeader(
    title: String,
    subtitle: String?,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            color = AliflixContentPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
        )
        subtitle?.let {
            Text(
                text = it,
                color = AliflixContentTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DiscoverPosterCard(
    item: Media,
    onOpen: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(role = Role.Button) { onOpen(item) }
            .semantics { contentDescription = "Open ${item.title}" },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(AliflixSurfaceSecondary),
        )
        Text(
            text = item.title,
            color = AliflixContentPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.year.isNotBlank()) {
            Text(
                text = item.year,
                color = AliflixContentTertiary,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CatalogueSkeletonGrid(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = AliflixAccentSecondary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Searching the catalogue",
                color = AliflixContentSecondary,
                fontSize = 12.sp,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(count = 9, key = { "catalogue-skeleton:$it" }, contentType = { "skeleton" }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(12.dp)
                            .clip(CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "discover-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "discover-skeleton-alpha",
    )
    Box(modifier = modifier.background(AliflixContentTertiary.copy(alpha = alpha)))
}

@Composable
internal fun InlineNotice(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AliflixSurfacePrimary,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AliflixContentPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        color = AliflixContentSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun DiscoverStateMessage(
    eyebrow: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = eyebrow,
            color = if (eyebrow.contains("ERROR") || eyebrow.contains("UNAVAILABLE")) {
                AliflixError
            } else {
                AliflixAccentSecondary
            },
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            color = AliflixContentPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = message,
                color = AliflixContentSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun RecommendationUiState.preferencesOrNull(): RecommendationPreferences? = when (this) {
    RecommendationUiState.Idle -> null
    is RecommendationUiState.SelectType -> preferences
    is RecommendationUiState.Discovering -> preferences
    is RecommendationUiState.Question -> preferences
    is RecommendationUiState.Results -> preferences
    is RecommendationUiState.Empty -> preferences
    is RecommendationUiState.SourceUnavailable -> preferences
    is RecommendationUiState.Relaxation -> preferences
    is RecommendationUiState.Error -> preferences
}

internal fun RecommendationPreferences.selectedMediaKind(): RecommendationMediaKind? =
    when (contentType?.value) {
        RecommendationContentType.MOVIE -> RecommendationMediaKind.MOVIE
        RecommendationContentType.TV -> RecommendationMediaKind.SERIES
        RecommendationContentType.EITHER,
        null,
        -> null
    }

internal fun RecommendationSourceHealth.isPartial(): Boolean =
    listOf(catalogue, imdb, web, reddit).any { status ->
        status == RecommendationSourceStatus.DEGRADED ||
            status == RecommendationSourceStatus.UNAVAILABLE
    }
