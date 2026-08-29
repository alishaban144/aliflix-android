package com.aliflix.app.recommendation

internal fun buildAskAliflixShowMoreRequest(
    original: V3RecommendationRequest,
    nextCursor: String,
): V3RecommendationRequest {
    require(nextCursor.isNotBlank()) { "Show more requires a recommendation cursor" }
    return original.copy(cursor = nextCursor)
}
