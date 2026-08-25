package com.aliflix.app.ui.discover

import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.AnimationFilter
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
            is AskAliflixRequest.Describe -> CatalogDiscoverySpec(
                if (outputType == MediaType.TV) RecommendationMediaKind.SERIES else RecommendationMediaKind.MOVIE,
                requiredStatus = request.requiredStatus,
            )
            is AskAliflixRequest.Similar -> CatalogDiscoverySpec(
                if (outputType == MediaType.TV) RecommendationMediaKind.SERIES else RecommendationMediaKind.MOVIE,
                requiredStatus = request.requiredStatus,
            )
        }
        val summary = when (request) {
            is AskAliflixRequest.Describe -> {
                if (request.refinementText != null) {
                    "${outputType.label()} — “${request.refinementText.trim()}”"
                } else {
                    "${outputType.label()} — “${request.text.trim()}”"
                }
            }
            is AskAliflixRequest.Similar -> {
                val titles = request.anchors.map { it.title }
                when (titles.size) {
                    0 -> "${outputType.label()} — similar titles"
                    1 -> "${outputType.label()} — similar to ${titles[0]}"
                    2 -> "${outputType.label()} — blending ${titles[0]} & ${titles[1]}"
                    else -> "${outputType.label()} — blending ${titles.size} titles"
                }
            }
            is AskAliflixRequest.Filters -> "${outputType.label()} — your selected filters"
        }
        val rawQuery = when (request) {
            is AskAliflixRequest.Describe -> request.text.trim()
            // Canonical anchors already carry the identity. Repeating their titles
            // in free text lets title words leak into semantic intent extraction.
            is AskAliflixRequest.Similar -> ""
            is AskAliflixRequest.Filters -> request.spec.discoveryText.trim()
        }
        val anchorList = (request as? AskAliflixRequest.Similar)?.anchors?.map {
            V3RecommendationAnchor(it.id, it.title, it.type.routeName)
        } ?: emptyList()

        return MappedAskAliflixRequest(summary, spec, V3RecommendationRequest(
            requestId = requestId,
            mode = when (request) { is AskAliflixRequest.Describe -> "describe"; is AskAliflixRequest.Similar -> "similar"; is AskAliflixRequest.Filters -> "filters" },
            query = rawQuery,
            mediaType = outputType.routeName,
            anchor = anchorList.firstOrNull(),
            anchors = anchorList,
            previousQuery = (request as? AskAliflixRequest.Describe)?.previousText,
            refinementQuery = (request as? AskAliflixRequest.Describe)?.refinementText,
            filters = spec.toWorkerFilters(),
            pageSize = 24,
        ))
    }

    private fun CatalogDiscoverySpec.toWorkerFilters(): V3RecommendationFilters {
        val animationSelected = animationFilter != null
        val resolvedIncludedGenres = if (animationSelected) {
            (includedGenres + "Animation").distinct()
        } else {
            includedGenres
        }
        val resolvedExcludedGenres = if (animationSelected) excludedGenres - "Animation" else excludedGenres
        val resolvedOriginalLanguage = if (animationSelected) null else originalLanguage
        val resolvedCountries = animationFilter?.let { listOf(it.tmdbOriginCountry) } ?: countries
        return V3RecommendationFilters(
            minimumYear = yearMinimum,
            maximumYear = yearMaximum,
            originalLanguage = resolvedOriginalLanguage,
            originCountries = resolvedCountries,
            minimumRuntimeMinutes = runtimeMinimumMinutes,
            maximumRuntimeMinutes = runtimeMaximumMinutes,
            includedGenres = resolvedIncludedGenres,
            excludedGenres = resolvedExcludedGenres,
            minimumTmdbRating = minimumTmdb,
            seriesStatus = when (requiredStatus?.trim()?.lowercase()) {
                "returning series" -> "returning"
                "ended" -> "ended"
                else -> null
            },
            sortBy = sortBy.workerValue,
        )
    }

    private fun MediaType.label() = if (this == MediaType.TV) "Series" else "Movies"
}
