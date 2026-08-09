package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.aliflix.app.model.Media
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        AliflixSurfaceElevated.copy(alpha = 0.54f),
                        AliflixBackgroundBase,
                        AliflixBackgroundBase,
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AskAliflixHeader(onReset = onReset, onBack = onBack)

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

                                1 -> AskAliflixSimilar(
                                    query = editorState.similarQuery,
                                    onQueryChanged = { onEditorStateChanged(editorState.copy(similarQuery = it)) },
                                    selectedAnchor = editorState.selectedAnchor,
                                    onAnchorSelected = { anchor ->
                                        onEditorStateChanged(
                                            editorState.copy(
                                                selectedAnchor = anchor,
                                                similarQuery = anchor?.title ?: editorState.similarQuery,
                                            )
                                        )
                                    },
                                    suggestions = suggestions,
                                    suggestionsLoading = suggestionsLoading,
                                    outputMediaType = editorState.mediaType,
                                    onSubmit = {
                                        editorState.selectedAnchor?.let { anchor ->
                                            onSubmitRequest(
                                                AskAliflixRequest.Similar(
                                                    outputMediaType = editorState.mediaType,
                                                    anchor = anchor,
                                                )
                                            )
                                        }
                                    },
                                    loading = false,
                                )

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
                        onOpenMedia = onOpenMedia,
                        onEdit = onEdit,
                        onReset = onReset,
                        onLoadMore = onLoadMore,
                        onRetry = onRetry,
                        listState = listState,
                    )
                }
            }
        }
    }
}
