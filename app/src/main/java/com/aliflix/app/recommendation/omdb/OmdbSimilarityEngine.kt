package com.aliflix.app.recommendation.omdb

import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.omdb.OmdbLookupRequest
import com.aliflix.app.data.omdb.OmdbMetadataClient
import com.aliflix.app.data.omdb.OmdbTitleMetadata
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationAiClient

class OmdbSimilarityEngine(
    private val catalogClient: CatalogClient,
    private val omdbClient: OmdbMetadataClient,
    private val aiClient: RecommendationAiClient?,
) {

    suspend fun resolveAnchor(
        query: String,
        mediaType: MediaType,
    ): OmdbRecommendationAnchor? {
        val mediaList = try {
            catalogClient.search(query)
        } catch (_: Throwable) {
            emptyList()
        }
        val media = mediaList.firstOrNull { it.type == mediaType } ?: mediaList.firstOrNull() ?: return null

        val omdb = omdbClient.lookup(
            OmdbLookupRequest(
                imdbId = media.imdbId,
                title = media.title,
                year = media.year?.toIntOrNull(),
                mediaType = if (media.type == MediaType.MOVIE) "movie" else "series"
            )
        )

        return OmdbRecommendationAnchor(
            title = omdb?.title ?: media.title,
            imdbId = omdb?.imdbId ?: media.imdbId,
            mediaType = media.type,
            overview = omdb?.plot ?: media.overview,
            genres = omdb?.genres ?: emptyList()
        )
    }

    suspend fun discoverSimilarCandidates(
        anchor: OmdbRecommendationAnchor,
        ledger: AskAliflixLedger,
    ): List<Media> {
        val candidates = mutableListOf<Media>()
        val seen = mutableSetOf<String>()

        // 1. TMDB related / recommendations search by title
        val searchResults = try {
            catalogClient.search(anchor.title)
        } catch (_: Throwable) {
            emptyList()
        }

        for (item in searchResults) {
            if (item.type != anchor.mediaType) continue
            if (item.title.equals(anchor.title, ignoreCase = true)) continue
            val key = item.imdbId ?: "tmdb:${item.id}"
            if (seen.add(key)) {
                candidates.add(item)
            }
        }

        // 2. Discover by shared genres
        if (anchor.genres.isNotEmpty()) {
            val spec = OmdbRecommendationSpec(
                mediaType = anchor.mediaType,
                includedGenres = anchor.genres.take(2).toSet()
            )
            val genreCandidates = OmdbCandidateDiscovery.discoverNextBatch(
                spec = spec,
                ledger = ledger,
                client = catalogClient,
                maxPagesToSearch = 3,
                targetCandidateCount = 40
            )
            for (cand in genreCandidates) {
                if (cand.title.equals(anchor.title, ignoreCase = true)) continue
                if (seen.add(cand.candidateId)) {
                    candidates.add(cand.media)
                }
            }
        }

        return candidates
    }
}
