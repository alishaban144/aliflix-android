
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.aliflix.app.ui.common.MobileTopSafeArea
import com.aliflix.app.ui.common.aliflixScreenBackground
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
import androidx.compose.material.icons.rounded.AccountCircle
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.SearchMode
import com.aliflix.app.SearchPhase
import com.aliflix.app.SearchUiState
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.ProductionCompanyFilter
import com.aliflix.app.ui.launch.AnimatedAliflixHeatmapLogo
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
    aiEnabled: Boolean,
    catalogueStore: DiscoverCatalogueStore? = null,
    onPerson: (com.aliflix.app.model.MediaCreator) -> Unit = {},
    onCategory: (String) -> Unit = {},
    onAccount: () -> Unit = {},
    homeContent: HomeContent?,
    recent: List<Media>,
    focusRequestId: Int?,
    onFocusRequestConsumed: (Int) -> Unit,
    askOpenRequestId: Int? = null,
    onAskOpenRequestConsumed: (Int) -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onSearchTitles: suspend (String) -> List<Media>,
    onSearchCompanies: suspend (String) -> List<ProductionCompanyFilter> = { emptyList() },
    onModeChange: (SearchMode) -> Unit,
    onOpen: (Media) -> Unit,
    catalogGridState: LazyGridState,
    recommendationListState: LazyListState,
    mediaFilter: String,
    onMediaFilterChange: (String) -> Unit,
    askUiState: AskAliflixUiState = AskAliflixUiState.Editing,
    askEditorState: AskAliflixEditorState = AskAliflixEditorState(),
    onSubmitAskAliflix: (AskAliflixRequest) -> Unit = {},
    onResetAskAliflix: () -> Unit = {},
    onEditAskAliflix: () -> Unit = {},
    onSetAskEditorState: (AskAliflixEditorState) -> Unit = {},
    onLoadMoreAskAliflix: () -> Unit = {},
    onRetryAskAliflix: () -> Unit = {},
    onRefineAskAliflix: (String) -> Unit = {},
    onToggleHideMySpaceAskAliflix: (Boolean) -> Unit = {},
    myListKeys: Set<String> = emptySet(),
    mySpaceKeys: Set<String> = emptySet(),
    onToggleMyList: (Media) -> Unit = {},
    onAskVisibilityChanged: (Boolean) -> Unit = {},
    catalogBottomPadding: Dp = 0.dp,
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
    var recommendModeActive by rememberSaveable { mutableStateOf(false) }
    var preservedCatalogBottomPadding by remember { mutableStateOf(catalogBottomPadding) }

    LaunchedEffect(catalogBottomPadding) {
        if (catalogBottomPadding > 0.dp) {
            preservedCatalogBottomPadding = catalogBottomPadding
        }
    }

    LaunchedEffect(recommendModeActive) {
        onAskVisibilityChanged(recommendModeActive)
    }

    LaunchedEffect(aiEnabled) {
        if (!aiEnabled && recommendModeActive) {
            recommendModeActive = false
            onModeChange(SearchMode.TITLE)
        }
    }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId != null) {
            recommendModeActive = false
            onModeChange(SearchMode.TITLE)
            withFrameNanos {}
            focusRequester.requestFocus()
            fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
            keyboard?.show()
            onFocusRequestConsumed(focusRequestId)
        }
    }

    LaunchedEffect(askOpenRequestId, aiEnabled) {
        if (askOpenRequestId != null) {
            if (aiEnabled) {
                recommendModeActive = true
                onModeChange(SearchMode.AI)
            }
            onAskOpenRequestConsumed(askOpenRequestId)
        }
    }

    androidx.activity.compose.BackHandler(enabled = recommendModeActive) {
        recommendModeActive = false
        onModeChange(SearchMode.TITLE)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .aliflixScreenBackground(),
    ) {
        AnimatedContent(
            targetState = recommendModeActive,
            transitionSpec = {
                if (targetState) {
                    (
                        androidx.compose.animation.slideInHorizontally(
                            initialOffsetX = { it / 5 },
                            animationSpec = DiscoverMotion.navigation()
                        ) + fadeIn(DiscoverMotion.navigation())
                    ) togetherWith (
                        androidx.compose.animation.slideOutHorizontally(
                            targetOffsetX = { -it / 6 },
                            animationSpec = DiscoverMotion.navigation()
                        ) + fadeOut(DiscoverMotion.navigation())
                    )
                } else {
                    (
                        androidx.compose.animation.slideInHorizontally(
                            initialOffsetX = { -it / 6 },
                            animationSpec = DiscoverMotion.navigation()
                        ) + fadeIn(DiscoverMotion.navigation())
                    ) togetherWith (
                        androidx.compose.animation.slideOutHorizontally(
                            targetOffsetX = { it / 5 },
                            animationSpec = DiscoverMotion.navigation()
                        ) + fadeOut(DiscoverMotion.navigation())
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("discover-mode-container"),
            contentAlignment = Alignment.TopStart,
            label = "discover-mode",
        ) { recommendMode ->
            if (recommendMode) {
                var similarSuggestions by remember { mutableStateOf<List<Media>>(emptyList()) }
                var similarSuggestionsLoading by remember { mutableStateOf(false) }
                var similarSuggestionsError by remember { mutableStateOf<String?>(null) }
                var similarSuggestionsRetry by remember { mutableStateOf(0) }

                LaunchedEffect(askEditorState.similarQuery, askEditorState.mode, askEditorState.selectedAnchors.size, similarSuggestionsRetry) {
                    val anchors = if (askEditorState.selectedAnchors.isNotEmpty()) askEditorState.selectedAnchors else listOfNotNull(askEditorState.selectedAnchor)
                    if (askEditorState.mode == 1 && askEditorState.similarQuery.trim().length >= 2 && anchors.size < 4) {
                        delay(280)
                        similarSuggestionsLoading = true
                        similarSuggestionsError = null
                        similarSuggestions = try {
                            onSearchTitles(askEditorState.similarQuery.trim())
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            similarSuggestionsError = "Title search is temporarily unavailable."
                            emptyList()
                        }
                        similarSuggestionsLoading = false
                    } else {
                        similarSuggestions = emptyList()
                        similarSuggestionsLoading = false
                        similarSuggestionsError = null
                    }
                }

                AskAliflixScreen(
                    uiState = askUiState,
                    editorState = askEditorState,
                    onEditorStateChanged = onSetAskEditorState,
                    onSubmitRequest = { req ->
                        keyboard?.hide()
                        onSubmitAskAliflix(req)
                    },
                    onReset = onResetAskAliflix,
                    onEdit = onEditAskAliflix,
                    onOpenMedia = onOpen,
                    suggestions = similarSuggestions,
                    suggestionsLoading = similarSuggestionsLoading,
                    suggestionsError = similarSuggestionsError,
                    onRetrySuggestions = { similarSuggestionsRetry += 1 },
                    onSearchCompanies = onSearchCompanies,
                    onLoadMore = onLoadMoreAskAliflix,
                    onRetry = onRetryAskAliflix,
                    onRefineRequest = onRefineAskAliflix,
                    onToggleHideMySpaceTitles = onToggleHideMySpaceAskAliflix,
                    myListKeys = myListKeys,
                    mySpaceKeys = mySpaceKeys,
                    onToggleMyList = onToggleMyList,
                    onBack = {
                        recommendModeActive = false
                        onModeChange(SearchMode.TITLE)
                    },
                    listState = recommendationListState,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = preservedCatalogBottomPadding),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        MobileTopSafeArea()

                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Discover", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = onAccount, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.AccountCircle, "Account", tint = AliflixAccentSecondary, modifier = Modifier.size(30.dp))
                            }
                        }

                        Row(

                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = fieldValue,
                                onValueChange = { updated ->
                                    fieldValue = updated

                                },
                                placeholder = {
                                    Text(
                                        text = "Search titles and people",
                                        color = AliflixContentTertiary,
                                        fontSize = 14.sp,
                                    )
                                },
                                leadingIcon = {
                                    IconButton(onClick = { keyboard?.hide(); fieldValue = fieldValue.copy(text = fieldValue.text.trim()) }) {
                                        Icon(Icons.Filled.Search, "Search catalogue", tint = AliflixContentSecondary)
                                    }
                                },
                                trailingIcon = {
                                    if (fieldValue.text.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                fieldValue = TextFieldValue("")

                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Clear search",
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { 
                                        keyboard?.hide()
                                        fieldValue = fieldValue.copy(text = fieldValue.text.trim())
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
                                    .weight(1f)
                                    .height(56.dp)
                                    .focusRequester(focusRequester)
                                    .testTag("discover-search-field"),
                            )
                        }

                        AnimatedVisibility(
                            visible = aiEnabled,
                            enter = fadeIn(DiscoverMotion.standard()),
                            exit = fadeOut(DiscoverMotion.standard()),
                        ) {
                            Surface(
                                onClick = {
                                    recommendModeActive = true
                                    onModeChange(SearchMode.AI)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .testTag("discover-ask-aliflix-card"),
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                contentColor = AliflixContentPrimary,
                                tonalElevation = 0.dp,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    AliflixAccentPrimary.copy(alpha = 0.32f),
                                                    AliflixSurfaceElevated,
                                                    AliflixSurfacePrimary,
                                                )
                                            )
                                        )
                                        .border(1.dp, AliflixAccentPrimary.copy(alpha = 0.34f), RoundedCornerShape(22.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AnimatedAliflixHeatmapLogo(
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Spacer(modifier = Modifier.width(13.dp))
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Ask Aliflix",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp,
                                            color = AliflixContentPrimary,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = "Open Ask Aliflix recommendations",
                                        tint = AliflixAccentSecondary,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                        }

                        CatalogueTypeSelector(
                            selected = mediaFilter,
                            onSelect = onMediaFilterChange,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                        )

                        if (catalogueStore != null) DiscoverCatalogueContent(
                            store = catalogueStore, query = fieldValue.text.trim(), filter = mediaFilter,
                            onOpen = onOpen, onPerson = onPerson, onCategory = onCategory,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("All", "Movies", "Series").forEach { option ->
            val active = option == selected
            val bgColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (active) AliflixAccentPrimary.copy(alpha = 0.22f) else AliflixSurfaceSecondary,
                animationSpec = DiscoverMotion.fast(),
                label = "catalogueBg"
            )
            val borderColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (active) AliflixAccentPrimary.copy(alpha = 0.72f) else AliflixBorderSubtle,
                animationSpec = DiscoverMotion.fast(),
                label = "catalogueBorder"
            )
            val textColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (active) AliflixContentPrimary else AliflixContentSecondary,
                animationSpec = DiscoverMotion.fast(),
                label = "catalogueText"
            )
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(15.dp))
                    .aliflixPressScale(interactionSource)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = { onSelect(option) }
                    )
                    .padding(horizontal = 15.dp)
                    .testTag("discover-filter-${option.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
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
internal fun DiscoverPosterCard(
    item: Media,
    onOpen: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .aliflixPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Button
            ) { onOpen(item) }
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
                text = listOf(if (item.type == MediaType.MOVIE) "Movie" else "Series", item.year).filter(String::isNotBlank).joinToString(" • "),
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
