package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSpec
import com.aliflix.app.ui.theme.AliflixBackgroundBase

@Composable
fun AskAliflixScreen(
    uiState: AskAliflixUiState,
    editorState: AskAliflixEditorState,
    onEditorStateChanged: (AskAliflixEditorState) -> Unit,
    onSubmitRequest: (AskAliflixRequest) -> Unit,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    onSearchTitles: suspend (String) -> List<Media>,
    suggestions: List<Media>,
    suggestionsLoading: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBackgroundBase)
    ) {
        // TOP APP BAR: ← Ask Aliflix  BETA  Reset
        AskAliflixHeader(
            onReset = onReset,
            onBack = onBack
        )

        // Primary Screen State Transition (Editing vs Results / Searching / Empty / Error)
        AnimatedContent(
            targetState = uiState is AskAliflixUiState.Editing,
            transitionSpec = {
                fadeIn(AskAliflixMotion.stateTransitionSpec()) togetherWith fadeOut(AskAliflixMotion.stateTransitionSpec())
            },
            label = "ask-screen-state"
        ) { isEditing ->
            if (isEditing) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // MODE & MEDIA TYPE CONTROL (Movies | Series) + (Describe | Similar | Filters)
                    AskAliflixModeSelector(
                        selectedMode = editorState.mode,
                        onModeSelected = { newMode ->
                            onEditorStateChanged(editorState.copy(mode = newMode))
                        },
                        selectedMediaType = editorState.mediaType,
                        onMediaTypeSelected = { newType ->
                            onEditorStateChanged(
                                editorState.copy(
                                    mediaType = newType,
                                    spec = editorState.spec.copy(mediaType = newType)
                                )
                            )
                        }
                    )

                    // MODE WORKSPACE CONTENT (Directional horizontal animation between modes)
                    AnimatedContent(
                        targetState = editorState.mode,
                        transitionSpec = {
                            AskAliflixMotion.horizontalModeTransition(targetState > initialState)
                        },
                        label = "ask-mode-workspace",
                        modifier = Modifier.weight(1f)
                    ) { mode ->
                        when (mode) {
                            0 -> {
                                // DESCRIBE WORKSPACE
                                AskAliflixDescribe(
                                    text = editorState.describeText,
                                    onTextChanged = { text ->
                                        onEditorStateChanged(editorState.copy(describeText = text))
                                    },
                                    mediaType = editorState.mediaType,
                                    onSubmit = {
                                        onSubmitRequest(
                                            AskAliflixRequest.Describe(
                                                mediaType = editorState.mediaType,
                                                text = editorState.describeText
                                            )
                                        )
                                    },
                                    loading = false
                                )
                            }

                            1 -> {
                                // SIMILAR WORKSPACE
                                AskAliflixSimilar(
                                    query = editorState.similarQuery,
                                    onQueryChanged = { q ->
                                        onEditorStateChanged(editorState.copy(similarQuery = q))
                                    },
                                    selectedAnchor = editorState.selectedAnchor,
                                    onAnchorSelected = { anchor ->
                                        onEditorStateChanged(
                                            editorState.copy(
                                                selectedAnchor = anchor,
                                                similarQuery = anchor?.title ?: editorState.similarQuery
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
                                                    anchor = anchor
                                                )
                                            )
                                        }
                                    },
                                    loading = false
                                )
                            }

                            2 -> {
                                // FILTERS WORKSPACE
                                AskAliflixFilters(
                                    spec = editorState.spec,
                                    onSpecChanged = { newSpec ->
                                        onEditorStateChanged(editorState.copy(spec = newSpec))
                                    },
                                    onSubmit = {
                                        onSubmitRequest(
                                            AskAliflixRequest.Filters(spec = editorState.spec)
                                        )
                                    },
                                    loading = false
                                )
                            }
                        }
                    }
                }
            } else {
                // RESULTS / SEARCHING / EMPTY / ERROR VIEW
                AskAliflixResults(
                    uiState = uiState,
                    onOpenMedia = onOpenMedia,
                    onEdit = onEdit,
                    onReset = onReset,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    listState = listState
                )
            }
        }
    }
}
