package com.aliflix.app.recommendation.omdb

import com.aliflix.app.data.CatalogClient
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType

data class OmdbCandidate(
    val media: Media,
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val mediaType: MediaType,
) {
    val candidateId: String
        get() = imdbId ?: "tmdb:$tmdbId:${title.lowercase()}"
}

data class AskAliflixLedger(
    val searchedPages: MutableSet<Int> = mutableSetOf(),
    val seenCandidateIds: MutableSet<String> = mutableSetOf(),
    val evaluatedOmdbIds: MutableSet<String> = mutableSetOf(),
    val acceptedCandidateIds: MutableList<String> = mutableListOf(),
    val rejectedCandidateIds: MutableSet<String> = mutableSetOf(),
    var nextPageCursor: Int = 1,
    var displayedCount: Int = 0,
    var omdbEvaluationsCount: Int = 0,
    var omdbAndroidCacheHits: Int = 0,
    var omdbWorkerKvHits: Int = 0,
    var omdbUpstreamCalls: Int = 0,
)

object OmdbCandidateDiscovery {

    fun buildTmdbDiscoverPathParams(spec: OmdbRecommendationSpec): String {
        val params = mutableListOf<String>()

        // 1. Genres (AND logic via comma separated IDs)
        val genreIds = OmdbGenre.tmdbGenreIdsForSpec(spec.includedGenres, spec.mediaType)
        if (genreIds.isNotEmpty()) {
            params.add("with_genres=${genreIds.joinToString(",")}")
        }

        // 2. Years
        if (spec.mediaType == MediaType.MOVIE) {
            spec.minimumYear?.let { params.add("primary_release_date.gte=$it-01-01") }
            spec.maximumYear?.let { params.add("primary_release_date.lte=$it-12-31") }
        } else {
            spec.minimumYear?.let { params.add("first_air_date.gte=$it-01-01") }
            spec.maximumYear?.let { params.add("first_air_date.lte=$it-12-31") }
        }

        // 3. Default sort
        params.add("sort_by=popularity.desc")

        return params.joinToString("&")
    }

    suspend fun discoverNextBatch(
        spec: OmdbRecommendationSpec,
        ledger: AskAliflixLedger,
        client: CatalogClient,
        maxPagesToSearch: Int = 5,
        targetCandidateCount: Int = 50,
    ): List<OmdbCandidate> {
        val candidates = mutableListOf<OmdbCandidate>()
        var pagesSearchedInWave = 0

        while (pagesSearchedInWave < maxPagesToSearch && candidates.size < targetCandidateCount) {
            val pageToFetch = ledger.nextPageCursor
            if (!ledger.searchedPages.add(pageToFetch)) {
                ledger.nextPageCursor++
                continue
            }

            val pathParams = buildTmdbDiscoverPathParams(spec)
            val mediaItems = try {
                client.scrapeTmdbDiscoverPage(
                    pathParams = pathParams,
                    mediaType = spec.mediaType,
                    page = pageToFetch
                )
            } catch (_: Throwable) {
                emptyList()
            }

            ledger.nextPageCursor++
            pagesSearchedInWave++

            if (mediaItems.isEmpty()) {
                // If page returned empty, try broad query fallback on page 1 if initial search
                if (pageToFetch == 1 && spec.includedGenres.isNotEmpty()) {
                    val broadPath = "sort_by=popularity.desc"
                    val fallbackItems = try {
                        client.scrapeTmdbDiscoverPage(pathParams = broadPath, mediaType = spec.mediaType, page = 1)
                    } catch (_: Throwable) { emptyList() }
                    mediaItemsToCandidates(fallbackItems, spec, ledger, candidates)
                }
                break
            }

            mediaItemsToCandidates(mediaItems, spec, ledger, candidates)
        }

        return candidates
    }

    private fun mediaItemsToCandidates(
        mediaItems: List<Media>,
        spec: OmdbRecommendationSpec,
        ledger: AskAliflixLedger,
        output: MutableList<OmdbCandidate>,
    ) {
        for (item in mediaItems) {
            if (item.type != spec.mediaType) continue

            // Cheap year prefilter if year is explicitly available
            val itemYear = item.year?.toIntOrNull()
            if (itemYear != null) {
                if (spec.minimumYear != null && itemYear < spec.minimumYear) continue
                if (spec.maximumYear != null && itemYear > spec.maximumYear) continue
            }

            val candidateKey = item.imdbId ?: "tmdb:${item.id}:${item.title.lowercase()}"
            if (!ledger.seenCandidateIds.add(candidateKey)) continue

            output.add(
                OmdbCandidate(
                    media = item,
                    tmdbId = item.id,
                    imdbId = item.imdbId,
                    title = item.title,
                    year = itemYear,
                    mediaType = item.type,
                )
            )
        }
    }
}
