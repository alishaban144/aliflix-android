package com.aliflix.app.recommendation

import java.util.UUID

internal fun buildAskAliflixShowMoreRequest(
    original: V3RecommendationRequest,
    displayed: List<RecommendationCandidate>,
    selectedModel: RecommendationAiModel,
    requestId: String = UUID.randomUUID().toString(),
): V3RecommendationRequest {
    require(original.mode == "describe" || original.mode == "similar") {
        "Only generated recommendation modes support a fresh Show more request"
    }
    val displayedIds = displayed.map { it.media.id }
    val displayedTitles = displayed.map { it.media.title }.filter(String::isNotBlank)
    val filters = original.filters.copy(
        excludedTmdbIds = (original.filters.excludedTmdbIds + displayedIds)
            .distinct()
            .takeLast(1000),
        excludedTitles = (original.filters.excludedTitles + displayedTitles)
            .distinctBy { it.trim().lowercase() }
            .takeLast(100),
    )
    return original.copy(
        requestId = requestId,
        aiModel = selectedModel.workerValue,
        filters = filters,
        cursor = null,
    )
}
