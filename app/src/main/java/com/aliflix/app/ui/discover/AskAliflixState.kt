package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationCandidate

sealed interface AskAliflixRequest {
    data class Describe(
        val mediaType: MediaType,
        val text: String,
        val previousText: String? = null,
        val refinementText: String? = null,
    ) : AskAliflixRequest

    data class Similar(
        val outputMediaType: MediaType,
        val anchors: List<Media> = emptyList(),
        val anchor: Media? = anchors.firstOrNull(),
    ) : AskAliflixRequest {
        constructor(outputMediaType: MediaType, anchor: Media) : this(
            outputMediaType = outputMediaType,
            anchors = listOf(anchor),
            anchor = anchor,
        )
    }

    data class Filters(
        val spec: CatalogDiscoverySpec
    ) : AskAliflixRequest
}

sealed interface AskAliflixUiState {
    data object Editing : AskAliflixUiState

    data class Interpreting(
        val requestSummary: String
    ) : AskAliflixUiState

    data class Searching(
        val requestSummary: String,
        val verifiedCount: Int = 0,
        val evaluatedCount: Int = 0
    ) : AskAliflixUiState

    data class Results(
        val requestSummary: String,
        val spec: CatalogDiscoverySpec,
        val items: List<RecommendationCandidate>,
        val totalAvailable: Int = items.size,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val nextCursor: String? = null,
        val loadMoreError: String? = null,
        val refining: Boolean = false,
        val activeRequest: AskAliflixRequest? = null,
    ) : AskAliflixUiState

    data class Empty(
        val requestSummary: String,
        val message: String = "No verified titles matched every filter."
    ) : AskAliflixUiState

    data class SourceUnavailable(
        val requestSummary: String,
        val message: String
    ) : AskAliflixUiState

    data class Error(
        val requestSummary: String,
        val message: String
    ) : AskAliflixUiState
}

data class AskAliflixEditorState(
    val mode: Int = 0, // 0: Describe, 1: Similar, 2: Filters
    val mediaType: MediaType = MediaType.MOVIE,
    val describeText: String = "",
    val similarQuery: String = "",
    val selectedAnchor: Media? = null,
    val selectedAnchors: List<Media> = emptyList(),
    val hideWatched: Boolean = false,
    val spec: CatalogDiscoverySpec = CatalogDiscoverySpec(mediaKind = com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE)
)

internal fun AskAliflixEditorState.resultsHeading(): String =
    if (mode == 1) {
        val anchors = if (selectedAnchors.isNotEmpty()) selectedAnchors else listOfNotNull(selectedAnchor)
        when (anchors.size) {
            0 -> "Matches"
            1 -> "Similar to \"${anchors[0].title}\""
            2 -> "Blending \"${anchors[0].title}\" & \"${anchors[1].title}\""
            else -> "Blending ${anchors.size} titles"
        }
    } else {
        "Matches"
    }
