package com.aliflix.app.recommendation.omdb

import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.omdb.OmdbLookupRequest
import com.aliflix.app.data.omdb.OmdbMetadataClient
import com.aliflix.app.data.omdb.OmdbTitleMetadata
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationAiClient

class OmdbRecommendationEngine(
    private val catalogClient: CatalogClient,
    private val omdbClient: OmdbMetadataClient,
    private val aiClient: RecommendationAiClient?,
) {
    companion object {
        const val MAX_UNCACHED_OMDB_UPSTREAM_CALLS = 60
        const val INITIAL_TARGET_VERIFIED_COUNT = 20
        const val PREFERRED_RESERVE_COUNT = 40
    }

    suspend fun executeRecommendation(
        spec: OmdbRecommendationSpec,
        ledger: AskAliflixLedger,
        targetVerifiedCount: Int = INITIAL_TARGET_VERIFIED_COUNT,
    ): List<VerifiedRecommendationItem> {
        val verifiedItems = mutableListOf<VerifiedRecommendationItem>()
        val seenInCurrentRun = mutableSetOf<String>()

        var wavesExecuted = 0
        val maxWaves = 10

        while (verifiedItems.size < targetVerifiedCount && wavesExecuted < maxWaves) {
            // Check uncached upstream call limit
            if (ledger.omdbUpstreamCalls >= MAX_UNCACHED_OMDB_UPSTREAM_CALLS) {
                break
            }

            wavesExecuted++

            // 1. Discover raw candidates from TMDB catalogue
            val rawCandidates = OmdbCandidateDiscovery.discoverNextBatch(
                spec = spec,
                ledger = ledger,
                client = catalogClient,
                maxPagesToSearch = 3,
                targetCandidateCount = 25
            )

            if (rawCandidates.isEmpty()) {
                break
            }

            // Filter out candidates already evaluated
            val candidatesToEvaluate = rawCandidates.filter { cand ->
                val id = cand.candidateId
                seenInCurrentRun.add(id) && ledger.evaluatedOmdbIds.add(id)
            }.take(20)

            if (candidatesToEvaluate.isEmpty()) {
                continue
            }

            // 2. Build OMDb lookup requests
            val batchRequests = candidatesToEvaluate.map { cand ->
                OmdbLookupRequest(
                    candidateId = cand.candidateId,
                    imdbId = cand.imdbId,
                    title = cand.title,
                    year = cand.year,
                    mediaType = if (cand.mediaType == MediaType.MOVIE) "movie" else "series"
                )
            }

            // 3. Perform OMDb progressive batch lookup
            val batchMap = try {
                omdbClient.lookupBatch(batchRequests)
            } catch (_: Throwable) {
                null
            }

            if (batchMap == null || batchMap.isEmpty()) {
                // Fallback: individual lookups if batch fails
                for (cand in candidatesToEvaluate) {
                    val meta = try {
                        omdbClient.lookup(
                            OmdbLookupRequest(
                                candidateId = cand.candidateId,
                                imdbId = cand.imdbId,
                                title = cand.title,
                                year = cand.year,
                                mediaType = if (cand.mediaType == MediaType.MOVIE) "movie" else "series"
                            )
                        )
                    } catch (_: Throwable) { null }

                    processMetadataEvaluation(cand.media, meta, spec, ledger, verifiedItems)
                }
            } else {
                val candidateMap = candidatesToEvaluate.associateBy { it.candidateId }
                for ((cid, meta) in batchMap) {
                    val candidate = candidateMap[cid] ?: continue
                    processMetadataEvaluation(candidate.media, meta, spec, ledger, verifiedItems)
                }
            }
        }

        // 4. Optional Plot Verification via Gemini (Describe mode only when plotRequirements present)
        val finalVerifiedItems = if (spec.plotRequirements.isNotEmpty() && aiClient != null && verifiedItems.isNotEmpty()) {
            verifyPlotsWithAi(verifiedItems, spec.plotRequirements)
        } else {
            verifiedItems
        }

        // 5. Rank and Sort
        return OmdbRecommendationRanker.rankAndSort(finalVerifiedItems, spec)
    }

    private fun processMetadataEvaluation(
        media: Media,
        meta: OmdbTitleMetadata?,
        spec: OmdbRecommendationSpec,
        ledger: AskAliflixLedger,
        output: MutableList<VerifiedRecommendationItem>,
    ) {
        ledger.omdbEvaluationsCount++
        if (meta?.source == "ANDROID_CACHE") ledger.omdbAndroidCacheHits++
        if (meta?.source == "WORKER_KV") ledger.omdbWorkerKvHits++
        if (meta?.source == "UPSTREAM_OMDB") ledger.omdbUpstreamCalls++

        val evalResult = OmdbConstraintEvaluator.evaluate(spec, meta)
        val candidateKey = media.imdbId ?: "tmdb:${media.id}"

        if (evalResult.accepted && meta != null) {
            ledger.acceptedCandidateIds.add(candidateKey)
            val explanation = OmdbRecommendationRanker.buildMatchExplanation(meta, evalResult)
            output.add(
                VerifiedRecommendationItem(
                    media = media,
                    omdbMetadata = meta,
                    evaluationResult = evalResult,
                    matchExplanation = explanation
                )
            )
        } else {
            ledger.rejectedCandidateIds.add(candidateKey)
        }
    }

    private suspend fun verifyPlotsWithAi(
        candidates: List<VerifiedRecommendationItem>,
        plotRequirements: List<String>,
    ): List<VerifiedRecommendationItem> {
        val client = aiClient ?: throw IllegalStateException("Semantic plot verification service unavailable")
        val aiVerifications = client.verifyPlots(plotRequirements, candidates)

        val matchCandidateIds = aiVerifications.results
            .filter { it.decision.equals("MATCH", ignoreCase = true) }
            .map { it.candidateId }
            .toSet()

        return candidates.filter { item ->
            val id = item.media.imdbId ?: "tmdb:${item.media.id}"
            matchCandidateIds.contains(id) || matchCandidateIds.contains(item.media.title)
        }
    }
}
