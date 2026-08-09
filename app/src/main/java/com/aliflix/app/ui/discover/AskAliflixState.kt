package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationCandidate

sealed interface AskAliflixRequest {
    data class Describe(
        val mediaType: MediaType,
        val text: String
    ) : AskAliflixRequest

    data class Similar(
        val outputMediaType: MediaType,
        val anchor: Media
    ) : AskAliflixRequest

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
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true
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
    val spec: CatalogDiscoverySpec = CatalogDiscoverySpec(mediaKind = com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE)
)
