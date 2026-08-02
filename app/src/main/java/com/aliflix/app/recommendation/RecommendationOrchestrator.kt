package com.aliflix.app.recommendation

import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.CatalogVerifiedMetadata
import com.aliflix.app.data.RecommendationDiscoveryItem
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface RecommendationCandidateRepository {
    suspend fun seedCandidates(
        spec: CatalogDiscoverySpec,
        requiredFields: RequiredMetadataFields,
    ): List<RecommendationCandidate> = emptyList()

    suspend fun discoverPage(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor,
        requiredFields: RequiredMetadataFields,
    ): RecommendationPage

    suspend fun resolveSimilarityAnchor(title: String): RecommendationCandidate?

    /**
     * Returns deliberate anchor choices rather than silently accepting the
     * first cross-media title match. Implementations should preserve provider
     * ordering because it is useful evidence when two canonical titles collide.
     */
    suspend fun resolveSimilarityAnchorCandidates(
        title: String,
        mediaKind: RecommendationMediaKind,
    ): List<RecommendationCandidate> =
        listOfNotNull(resolveSimilarityAnchor(title))
            .filter { it.media.type == mediaKind.mediaType }

    suspend fun enrichSimilarityAnchor(
        anchor: RecommendationCandidate,
    ): RecommendationCandidate = anchor

    suspend fun relatedCandidates(
        anchor: RecommendationCandidate,
        spec: CatalogDiscoverySpec,
        requiredFields: RequiredMetadataFields,
    ): List<RecommendationCandidate> = emptyList()
}

class CatalogRecommendationCandidateRepository(
    private val client: CatalogClient,
) : RecommendationCandidateRepository {
    override suspend fun seedCandidates(
        spec: CatalogDiscoverySpec,
        requiredFields: RequiredMetadataFields,
    ): List<RecommendationCandidate> =
        client.knownRecommendationSeeds(spec, requiredFields)
            .take(120)
            .map { it.toCandidate() }

    override suspend fun discoverPage(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor,
        requiredFields: RequiredMetadataFields,
    ): RecommendationPage = supervisorScope {
        val page = client.recommendationPage(
            spec = spec,
            cursor = cursor,
            requiredFields = requiredFields,
        )
        val verificationGate = Semaphore(METADATA_CONCURRENCY)
        val candidates = page.items.map { seed ->
            async {
                verificationGate.withPermit {
                    verifySeed(seed, requiredFields)
                }
            }
        }.awaitAll()
        RecommendationPage(
            candidates = candidates,
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
            sourceHealth = page.sourceHealth,
            fromCache = page.fromCache,
        )
    }

    override suspend fun resolveSimilarityAnchor(title: String): RecommendationCandidate? {
        val media = client.resolveRecommendationAnchor(title) ?: return null
        val required = RequiredMetadataFields(
            runtime = media.type == MediaType.MOVIE,
            tvEpisodeRuntime = media.type == MediaType.TV,
        )
        val verified = client.verifyRecommendationItem(media, required)
        return RecommendationCandidate(
            media = verified.media,
            metadata = verified.metadata.toRecommendationMetadata(),
        )
    }

    override suspend fun resolveSimilarityAnchorCandidates(
        title: String,
        mediaKind: RecommendationMediaKind,
    ): List<RecommendationCandidate> = supervisorScope {
        val gate = Semaphore(ANCHOR_METADATA_CONCURRENCY)
        client.search(title)
            .asSequence()
            .filter { it.type == mediaKind.mediaType }
            .distinctBy { it.key }
            .take(ANCHOR_CANDIDATE_LIMIT)
            .map { media ->
                async {
                    val alternativeTitles = gate.withPermit {
                        client.recommendationAlternativeTitles(media)
                    }
                    RecommendationCandidate(
                        media = media,
                        evidence = "Catalogue title match for $title",
                        sources = setOf("ANCHOR_RESOLUTION"),
                        alternativeTitles = alternativeTitles,
                    )
                }
            }
            .toList()
            .awaitAll()
    }

    override suspend fun enrichSimilarityAnchor(
        anchor: RecommendationCandidate,
    ): RecommendationCandidate {
        val required = RequiredMetadataFields(
            runtime = anchor.media.type == MediaType.MOVIE,
            tvEpisodeRuntime = anchor.media.type == MediaType.TV,
        )
        val verified = client.verifyRecommendationItem(anchor.media, required)
        return anchor.copy(
            media = verified.media,
            metadata = verified.metadata.toRecommendationMetadata(),
        )
    }

    override suspend fun relatedCandidates(
        anchor: RecommendationCandidate,
        spec: CatalogDiscoverySpec,
        requiredFields: RequiredMetadataFields,
    ): List<RecommendationCandidate> = supervisorScope {
        val gate = Semaphore(METADATA_CONCURRENCY)
        client.relatedRecommendationItems(anchor.media)
            .filter { it.media.type == spec.mediaKind.mediaType }
            .filter { related ->
                related.directProviderRelation ||
                    RelatedContentEngine.signals(anchor.media, related.media)
                        .isDefensibleMetadataRelation
            }
            .map { related ->
                async {
                    gate.withPermit {
                        val source = if (related.directProviderRelation) {
                            "ANCHOR_RELATED"
                        } else {
                            "ANCHOR_METADATA"
                        }
                        val verified = verifySeed(
                            RecommendationDiscoveryItem(
                                media = related.media,
                                evidence = if (related.directProviderRelation) {
                                    "Catalogue related-title data for ${anchor.media.title}"
                                } else {
                                    "Metadata overlap with ${anchor.media.title}"
                                },
                                sources = setOf(source),
                                sourceCount = 1,
                                sourcePosition = related.sourceRank,
                            ),
                            requiredFields,
                        )
                        verified.copy(
                            relevanceEvidence = relationshipEvidence(
                                anchor = anchor.media,
                                candidate = verified.media,
                                directProviderRelation =
                                    related.directProviderRelation,
                                sourceRank = related.sourceRank,
                            ),
                        )
                    }
                }
            }
            .awaitAll()
    }

    private val RelatedContentSignals.isDefensibleMetadataRelation: Boolean
        get() = sharedCast.isNotEmpty() ||
            sharedGenres.size >= 2 ||
            sharedStoryTokens.size >= 3

    private fun relationshipEvidence(
        anchor: Media,
        candidate: Media,
        directProviderRelation: Boolean,
        sourceRank: Int,
    ): List<RecommendationEvidence> {
        val signals = RelatedContentEngine.signals(anchor, candidate)
        return buildList {
            if (directProviderRelation) {
                add(
                    RecommendationEvidence(
                        type = RecommendationEvidenceType.DIRECT_RELATED_TITLE,
                        strength = 1.0,
                        source = "TMDB_RELATED",
                        description =
                            "Catalogue related-title data connects it to ${anchor.title}",
                        sourceRank = sourceRank,
                    ),
                )
            }
            if (signals.sharedCast.isNotEmpty()) {
                add(
                    RecommendationEvidence(
                        type = RecommendationEvidenceType.SHARED_CAST,
                        strength = (signals.sharedCast.size / 3.0)
                            .coerceIn(0.45, 1.0),
                        source = "CATALOGUE_CAST",
                        description = "Shares ${signals.sharedCast.take(2).joinToString(" and ")} " +
                            "with ${anchor.title}",
                    ),
                )
            }
            if (signals.sharedGenres.isNotEmpty()) {
                add(
                    RecommendationEvidence(
                        type = RecommendationEvidenceType.SHARED_GENRE,
                        strength = (signals.sharedGenres.size / 3.0)
                            .coerceIn(0.25, 0.82),
                        source = "CATALOGUE_GENRES",
                        description = "Shares ${signals.sharedGenres.take(2).joinToString(" and ")} " +
                            "with ${anchor.title}",
                    ),
                )
            }
            if (signals.sharedStoryTokens.size >= 3) {
                add(
                    RecommendationEvidence(
                        type = RecommendationEvidenceType.THEME_MATCH,
                        strength = (signals.sharedStoryTokens.size / 8.0)
                            .coerceIn(0.30, 0.62),
                        source = "CATALOGUE_TEXT",
                        description = "Shares story themes with ${anchor.title}",
                    ),
                )
            }
        }
    }

    private suspend fun verifySeed(
        seed: RecommendationDiscoveryItem,
        required: RequiredMetadataFields,
    ): RecommendationCandidate {
        val seedCandidate = seed.toCandidate()
        if (seedCandidate.has(required)) return seedCandidate
        val verified = try {
            client.verifyRecommendationItem(seed.media, required)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        return if (verified == null) {
            seedCandidate
        } else {
            seedCandidate.copy(
                media = verified.media,
                metadata = seed.metadata.merge(verified.metadata)
                    .toRecommendationMetadata(),
            )
        }
    }

    private fun RecommendationDiscoveryItem.toCandidate() = RecommendationCandidate(
        media = media,
        metadata = metadata.toRecommendationMetadata(),
        evidence = evidence,
        sources = sources,
        sourceRanks = sources.associateWith { sourcePosition.coerceAtLeast(0) },
        sourceCount = sourceCount,
        sourcePosition = sourcePosition,
    )

    private fun CatalogVerifiedMetadata.merge(
        other: CatalogVerifiedMetadata,
    ): CatalogVerifiedMetadata = CatalogVerifiedMetadata(
        genresVerified = genresVerified || other.genresVerified,
        runtimeMinutes = runtimeMinutes ?: other.runtimeMinutes,
        originalLanguage = originalLanguage ?: other.originalLanguage,
        status = status ?: other.status,
        director = director ?: other.director,
        seasonCount = seasonCount ?: other.seasonCount,
        averageEpisodeRuntimeMinutes =
            averageEpisodeRuntimeMinutes ?: other.averageEpisodeRuntimeMinutes,
        verifiedAtMillis = maxOf(verifiedAtMillis, other.verifiedAtMillis),
    )

    private fun CatalogVerifiedMetadata.toRecommendationMetadata() =
        VerifiedMediaMetadata(
            genresVerified = genresVerified,
            runtimeMinutes = runtimeMinutes,
            originalLanguage = originalLanguage,
            status = status,
            director = director,
            seasonCount = seasonCount,
            averageEpisodeRuntimeMinutes = averageEpisodeRuntimeMinutes,
            verifiedAtMillis = verifiedAtMillis,
        )

    private fun RecommendationCandidate.has(required: RequiredMetadataFields): Boolean {
        if (required.genres && !metadata.genresVerified) return false
        if (required.runtime && media.type == MediaType.MOVIE &&
            metadata.runtimeMinutes == null
        ) {
            return false
        }
        if (required.tvEpisodeRuntime && media.type == MediaType.TV &&
            metadata.averageEpisodeRuntimeMinutes == null
        ) {
            return false
        }
        if (required.originalLanguage && metadata.originalLanguage.isNullOrBlank()) {
            return false
        }
        if (required.status && metadata.status.isNullOrBlank()) return false
        if (required.imdbRating && media.imdbRating == null) return false
        if (required.rottenTomatoesRating &&
            media.rottenTomatoesRating == null
        ) {
            return false
        }
        if (required.tmdbRating && media.rating <= 0.0) return false
        return true
    }

    private companion object {
        const val METADATA_CONCURRENCY = 4
        const val ANCHOR_CANDIDATE_LIMIT = 8
        const val ANCHOR_METADATA_CONCURRENCY = 3
    }
}

object RecommendationQueryBuilder {
    fun build(preferences: RecommendationPreferences): String {
        val parts = buildList {
            add(
                when (preferences.contentType?.value) {
                    RecommendationContentType.MOVIE -> "movie"
                    RecommendationContentType.TV -> "television series"
                    else -> ""
                },
            )
            addAll(preferences.includedGenres.map { it.value })
            addAll(preferences.moods.map { it.value.label })
            addAll(preferences.semanticFacets.map { it.value.label })
            addAll(preferences.excludedFacets.map { "avoid ${it.value.label}" })
            preferences.viewingContext?.let {
                add("for ${it.value.label.lowercase()}")
            }
            preferences.runtimeMaximumMinutes?.let { add("under ${it.value} minutes") }
            preferences.runtimeMinimumMinutes?.let { add("at least ${it.value} minutes") }
            preferences.preferredRuntimeMinutes?.let { add("around ${it.value} minutes") }
            preferences.yearMinimum?.let { add("released after ${it.value - 1}") }
            preferences.yearMaximum?.let { add("released before ${it.value + 1}") }
            preferences.minimumImdb?.let { add("IMDb ${it.value} or higher") }
            preferences.minimumRottenTomatoes?.let {
                add("RT critic ${it.value}% or higher")
            }
            preferences.originalLanguage?.let {
                add("${it.value} original language")
            }
            preferences.requiredStatus?.let {
                add("${it.value} series")
            }
            preferences.similarityTitle?.let { anchor ->
                add(
                    when (preferences.relativeRuntime?.value) {
                        RelativeRuntimePreference.SHORTER_THAN_ANCHOR ->
                            "shorter than and similar to ${anchor.value}"
                        RelativeRuntimePreference.LONGER_THAN_ANCHOR ->
                            "longer than and similar to ${anchor.value}"
                        null -> "similar to ${anchor.value}"
                    },
                )
            }
            preferences.familiarity?.let { add(it.value.label) }
            addAll(preferences.creatorNames.map { "directed by ${it.value}" })
            addAll(preferences.castNames.map { "starring ${it.value}" })
            addAll(preferences.countryPreferences.map { "from ${it.value}" })
            addAll(preferences.unmatchedPreferences.map { signal ->
                if (signal.negated) "avoid ${signal.text}" else signal.text
            })
            addAll(preferences.unverifiedTerms)
            if (preferences.surpriseMe) add("surprising highly rated")
        }
        return parts
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .joinToString(" ")
            .take(MAX_QUERY_LENGTH)
    }

    private const val MAX_QUERY_LENGTH = 360
}

internal fun mergeRecommendationCandidates(
    existing: RecommendationCandidate?,
    incoming: RecommendationCandidate,
): RecommendationCandidate {
    if (existing == null) return incoming
    val sources = existing.sources + incoming.sources
    val mergedMedia = existing.media.copy(
        title = incoming.media.title.takeIf(String::isNotBlank) ?: existing.media.title,
        overview = incoming.media.overview.takeIf { it.length > existing.media.overview.length }
            ?: existing.media.overview,
        posterPath = incoming.media.posterPath ?: existing.media.posterPath,
        backdropPath = incoming.media.backdropPath ?: existing.media.backdropPath,
        year = incoming.media.year.takeIf(String::isNotBlank) ?: existing.media.year,
        rating = maxOf(existing.media.rating, incoming.media.rating),
        imdbId = incoming.media.imdbId ?: existing.media.imdbId,
        imdbRating = incoming.media.imdbRating ?: existing.media.imdbRating,
        imdbVoteCount = maxOf(
            existing.media.imdbVoteCount ?: 0,
            incoming.media.imdbVoteCount ?: 0,
        ).takeIf { it > 0 },
        imdbRatingState =
            incoming.media.imdbRatingState ?: existing.media.imdbRatingState,
        rottenTomatoesRating =
            incoming.media.rottenTomatoesRating ?: existing.media.rottenTomatoesRating,
        genres = (existing.media.genres + incoming.media.genres)
            .distinctBy(String::lowercase),
        cast = (existing.media.cast + incoming.media.cast)
            .distinctBy(String::lowercase)
            .take(12),
    )
    val existingMetadata = existing.metadata
    val incomingMetadata = incoming.metadata
    val evidence = (existing.evidence.lineSequence() + incoming.evidence.lineSequence())
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(6)
        .joinToString(". ")
    return existing.copy(
        media = mergedMedia,
        metadata = VerifiedMediaMetadata(
            genresVerified =
                existingMetadata.genresVerified || incomingMetadata.genresVerified,
            runtimeMinutes =
                incomingMetadata.runtimeMinutes ?: existingMetadata.runtimeMinutes,
            originalLanguage =
                incomingMetadata.originalLanguage ?: existingMetadata.originalLanguage,
            status = incomingMetadata.status ?: existingMetadata.status,
            director = incomingMetadata.director ?: existingMetadata.director,
            seasonCount = incomingMetadata.seasonCount ?: existingMetadata.seasonCount,
            averageEpisodeRuntimeMinutes =
                incomingMetadata.averageEpisodeRuntimeMinutes
                    ?: existingMetadata.averageEpisodeRuntimeMinutes,
            verifiedAtMillis = maxOf(
                existingMetadata.verifiedAtMillis,
                incomingMetadata.verifiedAtMillis,
            ),
        ),
        evidence = evidence,
        sources = sources,
        sourceRanks = (existing.sourceRanks.keys + incoming.sourceRanks.keys + sources)
            .associateWith { source ->
                minOf(
                    existing.sourceRanks[source] ?: existing.sourcePosition,
                    incoming.sourceRanks[source] ?: incoming.sourcePosition,
                ).coerceAtLeast(0)
            },
        sourceCount = maxOf(existing.sourceCount, incoming.sourceCount, sources.size),
        sourcePosition = minOf(existing.sourcePosition, incoming.sourcePosition),
        relevanceEvidence = (
            existing.relevanceEvidence + incoming.relevanceEvidence
            ).distinctBy { evidenceItem ->
            listOf(
                evidenceItem.type.name,
                evidenceItem.source,
                evidenceItem.description,
            ).joinToString("|")
        },
        precomputedSemanticScore = incoming.precomputedSemanticScore
            ?: existing.precomputedSemanticScore,
        matchReasons = (existing.matchReasons + incoming.matchReasons)
            .distinctBy { reason ->
                listOf(
                    reason.evidenceType.name,
                    reason.source,
                    reason.text,
                ).joinToString("|")
            },
        alternativeTitles = existing.alternativeTitles + incoming.alternativeTitles,
    )
}
