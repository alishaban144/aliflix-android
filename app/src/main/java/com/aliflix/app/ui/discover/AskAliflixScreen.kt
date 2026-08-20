package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aliflix.app.model.Media
import com.aliflix.app.ui.common.aliflixScreenBackground

@Composable
fun AskAliflixScreen(
    uiState: AskAliflixUiState,
    editorState: AskAliflixEditorState,
    onEditorStateChanged: (AskAliflixEditorState) -> Unit,
    onSubmitRequest: (AskAliflixRequest) -> Unit,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    suggestions: List<Media>,
    suggestionsLoading: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    onRefineRequest: (String) -> Unit = {},
    onToggleHideWatched: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .aliflixScreenBackground()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AskAliflixHeader(
                onReset = onReset,
                onBack = onBack,
                showNewSearch = uiState !is AskAliflixUiState.Editing,
            )

            AnimatedContent(
                targetState = uiState is AskAliflixUiState.Editing,
                transitionSpec = { AskAliflixMotion.editorResultTransition(targetState) },
                label = "ask-screen-state",
                modifier = Modifier.weight(1f),
            ) { isEditing ->
                if (isEditing) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AskAliflixModeSelector(
                            selectedMode = editorState.mode,
                            onModeSelected = { onEditorStateChanged(editorState.copy(mode = it)) },
                            selectedMediaType = editorState.mediaType,
                            onMediaTypeSelected = { newType ->
                                onEditorStateChanged(
                                    editorState.copy(
                                        mediaType = newType,
                                        spec = editorState.spec.copy(
                                            mediaKind = if (newType == com.aliflix.app.model.MediaType.TV) {
                                                com.aliflix.app.recommendation.RecommendationMediaKind.SERIES
                                            } else {
                                                com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE
                                            },
                                        ),
                                    )
                                )
                            },
                        )

                        AnimatedContent(
                            targetState = editorState.mode,
                            transitionSpec = { AskAliflixMotion.horizontalModeTransition(targetState > initialState) },
                            label = "ask-mode-workspace",
                            modifier = Modifier.weight(1f),
                        ) { mode ->
                            when (mode) {
                                0 -> AskAliflixDescribe(
                                    text = editorState.describeText,
                                    onTextChanged = { onEditorStateChanged(editorState.copy(describeText = it)) },
                                    mediaType = editorState.mediaType,
                                    onSubmit = {
                                        onSubmitRequest(
                                            AskAliflixRequest.Describe(
                                                mediaType = editorState.mediaType,
                                                text = editorState.describeText,
                                            )
                                        )
                                    },
                                    loading = false,
                                )

                                1 -> {
                                    val anchors = if (editorState.selectedAnchors.isNotEmpty()) {
                                        editorState.selectedAnchors
                                    } else {
                                        listOfNotNull(editorState.selectedAnchor)
                                    }
                                    AskAliflixSimilar(
                                        query = editorState.similarQuery,
                                        onQueryChanged = { onEditorStateChanged(editorState.copy(similarQuery = it)) },
                                        selectedAnchors = anchors,
                                        onAddAnchor = { item ->
                                            if (anchors.none { it.key == item.key } && anchors.size < 4) {
                                                val updated = anchors + item
                                                onEditorStateChanged(
                                                    editorState.copy(
                                                        selectedAnchors = updated,
                                                        selectedAnchor = updated.firstOrNull(),
                                                        similarQuery = "",
                                                    )
                                                )
                                            }
                                        },
                                        onRemoveAnchor = { item ->
                                            val updated = anchors.filterNot { it.key == item.key }
                                            onEditorStateChanged(
                                                editorState.copy(
                                                    selectedAnchors = updated,
                                                    selectedAnchor = updated.firstOrNull(),
                                                )
                                            )
                                        },
                                        suggestions = suggestions,
                                        suggestionsLoading = suggestionsLoading,
                                        outputMediaType = editorState.mediaType,
                                        onSubmit = {
                                            if (anchors.isNotEmpty()) {
                                                onSubmitRequest(
                                                    AskAliflixRequest.Similar(
                                                        outputMediaType = editorState.mediaType,
                                                        anchors = anchors,
                                                    )
                                                )
                                            }
                                        },
                                        loading = false,
                                    )
                                }

                                else -> AskAliflixFilters(
                                    spec = editorState.spec,
                                    onSpecChanged = { onEditorStateChanged(editorState.copy(spec = it)) },
                                    onSubmit = { onSubmitRequest(AskAliflixRequest.Filters(editorState.spec)) },
                                    loading = false,
                                )
                            }
                        }
                    }
                } else {
                    AskAliflixResults(
                        uiState = uiState,
                        editorState = editorState,
                        onOpenMedia = onOpenMedia,
                        onEdit = onEdit,
                        onReset = onReset,
                        onLoadMore = onLoadMore,
                        onRetry = onRetry,
                        onRefine = onRefineRequest,
                        hideWatched = editorState.hideWatched,
                        onToggleHideWatched = onToggleHideWatched,
                        listState = listState,
                    )
                }
            }
        }
    }
}
