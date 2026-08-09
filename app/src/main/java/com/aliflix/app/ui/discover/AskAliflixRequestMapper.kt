package com.aliflix.app.ui.discover

import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.V3RecommendationAnchor
import com.aliflix.app.recommendation.V3RecommendationFilters
import com.aliflix.app.recommendation.V3RecommendationRequest
import java.util.UUID

data class MappedAskAliflixRequest(
    val summary: String,
    val spec: CatalogDiscoverySpec,
    val workerRequest: V3RecommendationRequest,
)

object AskAliflixRequestMapper {
    fun map(request: AskAliflixRequest, requestId: String = UUID.randomUUID().toString()): MappedAskAliflixRequest {
        val outputType = when (request) {
            is AskAliflixRequest.Describe -> request.mediaType
            is AskAliflixRequest.Similar -> request.outputMediaType
            is AskAliflixRequest.Filters -> request.spec.mediaKind.mediaType
        }
        val spec = when (request) {
            is AskAliflixRequest.Filters -> request.spec
            else -> CatalogDiscoverySpec(if (outputType == MediaType.TV) RecommendationMediaKind.SERIES else RecommendationMediaKind.MOVIE)
        }
        val summary = when (request) {
            is AskAliflixRequest.Describe -> "${outputType.label()} — “${request.text.trim()}”"
            is AskAliflixRequest.Similar -> "${outputType.label()} — similar to ${request.anchor.title}"
            is AskAliflixRequest.Filters -> "${outputType.label()} — your selected filters"
        }
        val rawQuery = when (request) {
            is AskAliflixRequest.Describe -> request.text.trim()
            is AskAliflixRequest.Similar -> "${outputType.label().lowercase()} similar to ${request.anchor.title}"
            is AskAliflixRequest.Filters -> request.spec.discoveryText.trim()
        }
        return MappedAskAliflixRequest(summary, spec, V3RecommendationRequest(
            requestId = requestId,
            mode = when (request) { is AskAliflixRequest.Describe -> "describe"; is AskAliflixRequest.Similar -> "similar"; is AskAliflixRequest.Filters -> "filters" },
            query = rawQuery,
            mediaType = outputType.routeName,
            anchor = (request as? AskAliflixRequest.Similar)?.anchor?.let { V3RecommendationAnchor(it.id, it.title, it.type.routeName) },
            filters = spec.toWorkerFilters(),
        ))
    }

    private fun CatalogDiscoverySpec.toWorkerFilters() = V3RecommendationFilters(
        minimumYear = yearMinimum, maximumYear = yearMaximum, originalLanguage = originalLanguage,
        originCountries = countries, minimumRuntimeMinutes = runtimeMinimumMinutes, maximumRuntimeMinutes = runtimeMaximumMinutes,
        includedGenres = includedGenres, excludedGenres = excludedGenres, minimumTmdbRating = minimumTmdb,
    )

    private fun MediaType.label() = if (this == MediaType.TV) "Series" else "Movies"
}
