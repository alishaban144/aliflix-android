@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.Media
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationSort
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
fun AskAliflixResults(
    uiState: AskAliflixUiState,
    editorState: AskAliflixEditorState,
    onOpenMedia: (Media) -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    listState: LazyListState,
    onRefine: (String) -> Unit = {},
    onSortChanged: (RecommendationSort) -> Unit = {},
    hideMySpaceTitles: Boolean = false,
    onToggleHideMySpaceTitles: (Boolean) -> Unit = {},
    myListKeys: Set<String> = emptySet(),
    mySpaceKeys: Set<String> = emptySet(),
    onToggleMyList: (Media) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        val results = uiState as? AskAliflixUiState.Results
        val visibleItems = visibleAskAliflixItems(
            items = results?.items.orEmpty(),
            hideMySpaceTitles = hideMySpaceTitles,
            mySpaceKeys = mySpaceKeys,
        )
        val hiddenCount = (results?.items?.size ?: 0) - visibleItems.size

        when (uiState) {
            is AskAliflixUiState.Interpreting -> ResultContextBar(
                title = uiState.requestSummary,
                supportingText = null,
                editContentDescription = "Edit request",
                onEdit = onEdit,
            )
            is AskAliflixUiState.Searching -> ResultContextBar(
                title = uiState.requestSummary,
                supportingText = null,
                editContentDescription = "Edit request",
                onEdit = onEdit,
            )
            is AskAliflixUiState.Results -> ResultContextBar(
                title = if (editorState.mode == 2) "Filters" else uiState.requestSummary,
                supportingText = if (editorState.mode == 2) {
                    uiState.spec.askFilterSummary()
                } else {
                    resultCountLabel(uiState, visibleItems.size, hiddenCount)
                },
                editContentDescription = if (editorState.mode == 2) "Edit filters" else "Edit request",
                onEdit = onEdit,
            )
            is AskAliflixUiState.Empty -> ResultContextBar(
                title = uiState.requestSummary,
                supportingText = null,
                editContentDescription = "Edit request",
                onEdit = onEdit,
            )
            is AskAliflixUiState.SourceUnavailable -> ResultContextBar(
                title = uiState.requestSummary,
                supportingText = null,
                editContentDescription = "Edit request",
                onEdit = onEdit,
            )
            is AskAliflixUiState.Error -> ResultContextBar(
                title = uiState.requestSummary,
                supportingText = null,
                editContentDescription = "Edit request",
                onEdit = onEdit,
            )
            AskAliflixUiState.Editing -> Unit
        }

        if (results != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .toggleable(
                        value = hideMySpaceTitles,
                        role = Role.Switch,
                        onValueChange = onToggleHideMySpaceTitles,
                    )
                    .semantics {
                        stateDescription = if (hideMySpaceTitles) "My Space titles hidden" else "My Space titles shown"
                    }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Hide titles already in My Space",
                    color = AliflixContentSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = hideMySpaceTitles,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AliflixAccentPrimary,
                        checkedTrackColor = AliflixAccentSecondary.copy(alpha = 0.35f),
                        uncheckedThumbColor = AliflixContentTertiary,
                        uncheckedTrackColor = AliflixSurfaceElevated,
                    ),
                )
            }
        }

        AnimatedContent(
            targetState = uiState,
            transitionSpec = {
                fadeIn(AskAliflixMotion.smallContentSpec()) togetherWith fadeOut(AskAliflixMotion.smallContentSpec())
            },
            contentKey = { state -> state::class },
            label = "ask-results-state",
            modifier = Modifier.weight(1f),
        ) { state ->
            when (state) {
                is AskAliflixUiState.Interpreting -> AskLoadingState()

                is AskAliflixUiState.Searching -> AskLoadingState()

                is AskAliflixUiState.Results -> ResultsList(
                    state = state,
                    visibleItems = visibleItems,
                    listState = listState,
                    onOpenMedia = onOpenMedia,
                    onLoadMore = onLoadMore,
                    isFilterMode = editorState.mode == 2,
                    onSortChanged = onSortChanged,
                    myListKeys = myListKeys,
                    onToggleMyList = onToggleMyList,
                    hiddenCount = hiddenCount,
                    onShowHidden = { onToggleHideMySpaceTitles(false) },
                )

                is AskAliflixUiState.Empty -> AskStateMessage(
                    icon = Icons.Rounded.SearchOff,
                    title = "No matches",
                    message = state.message,
                    primaryLabel = "Edit request",
                    onPrimary = onEdit,
                    secondaryLabel = "New search",
                    onSecondary = onReset,
                )

                is AskAliflixUiState.SourceUnavailable -> AskStateMessage(
                    icon = Icons.Rounded.Warning,
                    title = "TMDB unavailable",
                    message = state.message,
                    primaryLabel = "Try again",
                    onPrimary = onRetry,
                    secondaryLabel = "Edit request",
                    onSecondary = onEdit,
                )

                is AskAliflixUiState.Error -> AskStateMessage(
                    icon = Icons.Rounded.Warning,
                    title = "Search failed",
                    message = state.message,
                    primaryLabel = "Try again",
                    onPrimary = onRetry,
                    secondaryLabel = "Edit request",
                    onSecondary = onEdit,
                )

                AskAliflixUiState.Editing -> Unit
            }
        }

        if (results != null && editorState.mode != 2) {
            RefineBottomBar(
                onRefine = onRefine,
                refining = results.refining,
                refineError = results.refineError,
                appliedRefinements = results.appliedRefinements,
            )
        }
    }
}

@Composable
private fun ResultsList(
    state: AskAliflixUiState.Results,
    visibleItems: List<RecommendationCandidate>,
    listState: LazyListState,
    onOpenMedia: (Media) -> Unit,
    onLoadMore: () -> Unit,
    isFilterMode: Boolean = false,
    onSortChanged: (RecommendationSort) -> Unit = {},
    myListKeys: Set<String>,
    onToggleMyList: (Media) -> Unit,
    hiddenCount: Int,
    onShowHidden: () -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Matches",
                    color = AliflixContentPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.25).sp,
                )
                Spacer(Modifier.width(8.dp))
                ResultCountPill(resultCountLabel(state, visibleItems.size, hiddenCount))
                Spacer(Modifier.weight(1f))
                if (isFilterMode) {
                    AskAliflixSortDropdown(
                        selectedSort = state.spec.sortBy,
                        mediaKind = state.spec.mediaKind,
                        onSortSelected = onSortChanged,
                    )
                }
            }
        }

        if (visibleItems.isEmpty() && hiddenCount > 0) {
            item {
                Surface(
                    color = AliflixSurfaceElevated.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, AliflixBorderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OutlinedButton(onClick = onShowHidden, shape = RoundedCornerShape(14.dp)) {
                            Text("Show hidden titles")
                        }
                    }
                }
            }
        }

        itemsIndexed(visibleItems, key = { _, item -> item.media.key }) { _, item ->
            ResultCard(
                item = item,
                onClick = { onOpenMedia(item.media) },
                inMyList = item.media.key in myListKeys,
                onToggleMyList = { onToggleMyList(item.media) },
                modifier = Modifier.animateItem(),
            )
        }

        state.loadMoreError?.let { message ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(AliflixError.copy(alpha = 0.11f))
                        .padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = AliflixError, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, color = AliflixError, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }

        if (state.hasMore || state.loadingMore) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.loadingMore) {
                        LoadingResultCard(alpha = 0.72f)
                        Spacer(Modifier.height(9.dp))
                        LoadingResultCard(alpha = 0.55f)
                    } else {
                        OutlinedButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            Text(
                                if (state.loadMoreError == null) "Find more matches" else "Try finding more again",
                                color = AliflixContentPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "End of results",
                    color = AliflixContentTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    item: RecommendationCandidate,
    onClick: () -> Unit,
    inMyList: Boolean,
    onToggleMyList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.982f else 1f,
        AskAliflixMotion.pressSpec(),
        label = "ask-result-press",
    )
    val border by animateColorAsState(
        if (pressed) AliflixAccentPrimary.copy(alpha = 0.72f) else AliflixBorderSubtle,
        AskAliflixMotion.pressSpec(),
        label = "ask-result-border",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        color = AliflixSurfaceElevated.copy(alpha = 0.84f),
        shape = RoundedCornerShape(19.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AskAliflixPoster(
                media = item.media,
                modifier = Modifier.size(width = 82.dp, height = 122.dp),
                cornerRadius = 12.dp,
            )

            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = item.media.title,
                        color = AliflixContentPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onToggleMyList,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (inMyList) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = if (inMyList) "Remove ${item.media.title} from My List" else "Add ${item.media.title} to My List",
                            tint = if (inMyList) AliflixAccentSecondary else AliflixContentTertiary,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                val metadata = listOfNotNull(
                    item.media.year.takeIf(String::isNotBlank),
                    item.media.runtime.takeIf(String::isNotBlank),
                    if (item.media.type == com.aliflix.app.model.MediaType.MOVIE) "Movie" else "Series",
                )
                Text(
                    metadata.joinToString(" · "),
                    color = AliflixContentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.media.genres.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        item.media.genres.take(2).forEach { genre ->
                            Text(
                                text = genre,
                                color = AliflixContentSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AliflixSurfaceSecondary)
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                if (item.media.rating > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFFFFC857).copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFFFC857), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("TMDB ${"%.1f".format(item.media.rating)}", color = AliflixContentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultContextBar(
    title: String,
    supportingText: String?,
    editContentDescription: String,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = AliflixSurfaceElevated.copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            AliflixAccentPrimary.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (supportingText == null) 52.dp else 64.dp)
                .clickable(role = Role.Button, onClick = onEdit)
                .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AliflixContentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                supportingText?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = it,
                        color = AliflixContentSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = editContentDescription,
                tint = AliflixAccentSecondary,
                modifier = Modifier.size(23.dp),
            )
        }
    }
}

@Composable
private fun ResultCountPill(label: String) {
    Surface(
        color = AliflixAccentPrimary.copy(alpha = 0.18f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = label,
            color = AliflixAccentSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        )
    }
}

internal fun resultCountLabel(
    state: AskAliflixUiState.Results,
    visibleCount: Int = state.items.size,
    hiddenCount: Int = 0,
): String =
    if (hiddenCount > 0) {
        "$visibleCount shown · $hiddenCount hidden"
    } else if (state.hasMore) {
        "$visibleCount+ found"
    } else if (state.totalAvailable > state.items.size) {
        "$visibleCount of ${state.totalAvailable}"
    } else {
        "$visibleCount found"
    }

internal fun visibleAskAliflixItems(
    items: List<RecommendationCandidate>,
    hideMySpaceTitles: Boolean,
    mySpaceKeys: Set<String>,
): List<RecommendationCandidate> = if (hideMySpaceTitles) {
    items.filterNot { it.media.key in mySpaceKeys }
} else {
    items
}


@Composable
private fun AskLoadingState() {
    val infinite = rememberInfiniteTransition(label = "ask-loading")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "ask-loading-pulse",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        ComposingThinkingOrb(modifier = Modifier.size(148.dp))
        Spacer(Modifier.height(13.dp))
        Text("Finding matches", color = AliflixContentPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(20.dp))
        repeat(2) { index ->
            LoadingResultCard(alpha = (pulse - index * 0.08f).coerceIn(0.28f, 0.8f))
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LoadingResultCard(alpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(142.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AliflixSurfaceElevated.copy(alpha = 0.72f))
            .border(1.dp, AliflixBorderSubtle.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .padding(10.dp),
    ) {
        Box(Modifier.size(width = 62.dp, height = 92.dp).clip(RoundedCornerShape(10.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha)))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.72f).height(15.dp).clip(RoundedCornerShape(5.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha)))
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth(0.44f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha * 0.8f)))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(0.58f).height(22.dp).clip(RoundedCornerShape(8.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha * 0.7f)))
        }
    }
}

@Composable
private fun AskStateMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AliflixSurfaceElevated)
                .border(1.dp, AliflixBorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AliflixAccentSecondary, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.height(17.dp))
        Text(title, color = AliflixContentPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(7.dp))
        Text(message, color = AliflixContentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPrimary,
            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(primaryLabel, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = if (secondaryLabel == "New search") {
                    Icons.Rounded.AddCircle
                } else {
                    Icons.Rounded.Edit
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(secondaryLabel, color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RefineBottomBar(
    onRefine: (String) -> Unit,
    refining: Boolean,
    refineError: String?,
    appliedRefinements: List<String>,
    modifier: Modifier = Modifier,
) {
    var refineText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = refineText.isNotBlank() && !refining

    LaunchedEffect(appliedRefinements.size) {
        if (appliedRefinements.lastOrNull() == refineText.trim()) {
            refineText = ""
        }
    }

    fun submit() {
        if (canSubmit) {
            keyboard?.hide()
            onRefine(refineText.trim())
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        refineError?.let { message ->
            Text(
                text = message,
                color = AliflixError,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = AliflixSurfaceElevated.copy(alpha = 0.92f),
            border = BorderStroke(
                width = 1.dp,
                color = if (canSubmit) AliflixAccentSecondary.copy(alpha = 0.45f) else AliflixBorderSubtle,
            ),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = if (canSubmit) AliflixAccentSecondary else AliflixContentTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = refineText,
                    onValueChange = { refineText = it.take(200) },
                    placeholder = {
                        Text(
                            "Refine results…",
                            color = AliflixContentTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    enabled = !refining,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { submit() }
                    ),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = AliflixContentPrimary,
                        unfocusedTextColor = AliflixContentPrimary,
                        cursorColor = AliflixAccentSecondary,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSubmit) AliflixAccentPrimary
                            else AliflixSurfaceSecondary.copy(alpha = 0.5f)
                        )
                        .clickable(enabled = canSubmit, role = Role.Button) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (refining) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AliflixAccentSecondary,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "Submit refinement",
                            tint = if (canSubmit) Color.White else AliflixContentTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AskAliflixSortDropdown(
    selectedSort: RecommendationSort,
    mediaKind: RecommendationMediaKind,
    onSortSelected: (RecommendationSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val availableSorts = remember { RecommendationSort.entries }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AliflixSurfaceElevated,
            border = BorderStroke(1.dp, AliflixBorderSubtle),
            modifier = Modifier.clip(RoundedCornerShape(20.dp)),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = AliflixAccentPrimary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = selectedSort.label,
                    color = AliflixContentPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Select sort order",
                    tint = AliflixContentSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AliflixSurfaceElevated)
                .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
        ) {
            availableSorts.forEach { sort ->
                val isSelected = sort == selectedSort
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = sort.label,
                                color = if (isSelected) AliflixAccentPrimary else AliflixContentPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                            )
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = AliflixAccentPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (sort != selectedSort) {
                            onSortSelected(sort)
                        }
                    },
                )
            }
        }
    }
}

