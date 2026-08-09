package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.text.Normalizer
import kotlin.math.max

/** A catalogue identity that cannot collide across movies and series. */
data class CanonicalMediaIdentity(
    val mediaType: MediaType,
    val catalogueId: Int,
) {
    val key: String = "${mediaType.routeName}:$catalogueId"

    companion object {
        fun from(media: Media): CanonicalMediaIdentity = CanonicalMediaIdentity(
            mediaType = media.type,
            catalogueId = media.id,
        )
    }
}

/** Canonical title metadata used before a title is allowed to become an anchor. */
data class CanonicalTitleAnchor(
    val identity: CanonicalMediaIdentity,
    val canonicalTitle: String,
    val alternativeTitles: Set<String> = emptySet(),
    val year: Int? = null,
) {
    val mediaType: MediaType get() = identity.mediaType

    companion object {
        fun from(
            media: Media,
            alternativeTitles: Set<String> = emptySet(),
        ): CanonicalTitleAnchor = CanonicalTitleAnchor(
            identity = CanonicalMediaIdentity.from(media),
            canonicalTitle = media.title,
            alternativeTitles = alternativeTitles,
            year = media.year.take(4).toIntOrNull(),
        )
    }
}

data class CanonicalTitleMatch(
    val anchor: CanonicalTitleAnchor,
    val matchedTitle: String,
    val confidence: Double,
)

sealed interface TitleAnchorResolution {
    val query: String

    data class Resolved(
        override val query: String,
        val anchor: CanonicalTitleAnchor,
        val matchedTitle: String,
        val confidence: Double,
    ) : TitleAnchorResolution

    data class Ambiguous(
        override val query: String,
        val candidates: List<CanonicalTitleMatch>,
    ) : TitleAnchorResolution

    data class NotFound(
        override val query: String,
    ) : TitleAnchorResolution
}

/**
 * Resolves title text deliberately. Exact canonical and alias matches win;
 * near-equal identities are surfaced as ambiguous instead of choosing one.
 */
object CanonicalTitleResolver {
    private const val MIN_RESOLUTION_CONFIDENCE = 0.74
    private const val AMBIGUITY_MARGIN = 0.055

    fun resolve(
        query: String,
        requiredType: RecommendationContentType?,
        candidates: List<CanonicalTitleAnchor>,
    ): TitleAnchorResolution {
        val normalizedQuery = normalizeTitle(query)
        if (normalizedQuery.isBlank()) return TitleAnchorResolution.NotFound(query)

        val scored = candidates
            .asSequence()
            .filter { requiredType == null || requiredType.accepts(it.mediaType) }
            .distinctBy { it.identity }
            .mapNotNull { anchor ->
                val titles = (setOf(anchor.canonicalTitle) + anchor.alternativeTitles)
                    .filter(String::isNotBlank)
                val best = titles
                    .map { title -> title to titleSimilarity(normalizedQuery, normalizeTitle(title)) }
                    .maxByOrNull { it.second }
                    ?: return@mapNotNull null
                CanonicalTitleMatch(anchor, best.first, best.second)
            }
            .filter { it.confidence >= MIN_RESOLUTION_CONFIDENCE }
            .sortedWith(
                compareByDescending<CanonicalTitleMatch>(CanonicalTitleMatch::confidence)
                    .thenBy { it.anchor.year ?: Int.MAX_VALUE }
                    .thenBy { it.anchor.identity.key },
            )
            .toList()

        val first = scored.firstOrNull() ?: return TitleAnchorResolution.NotFound(query)
        val contenders = scored.takeWhile {
            first.confidence - it.confidence <= AMBIGUITY_MARGIN
        }
        if (contenders.map { it.anchor.identity }.distinct().size > 1) {
            return TitleAnchorResolution.Ambiguous(query, contenders.take(5))
        }
        return TitleAnchorResolution.Resolved(
            query = query,
            anchor = first.anchor,
            matchedTitle = first.matchedTitle,
            confidence = first.confidence,
        )
    }

    private fun titleSimilarity(query: String, candidate: String): Double {
        if (query == candidate) return 1.0
        val queryTokens = query.split(' ').filter(String::isNotBlank).toSet()
        val candidateTokens = candidate.split(' ').filter(String::isNotBlank).toSet()
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        val intersection = (queryTokens intersect candidateTokens).size.toDouble()
        val union = (queryTokens union candidateTokens).size.toDouble().coerceAtLeast(1.0)
        val jaccard = intersection / union
        val containment = intersection / queryTokens.size.coerceAtLeast(1)
        val prefix = if (candidate.startsWith(query) || query.startsWith(candidate)) 0.08 else 0.0
        return (jaccard * 0.62 + containment * 0.30 + prefix).coerceIn(0.0, 0.98)
    }

    private fun normalizeTitle(value: String): String = Normalizer
        .normalize(value.lowercase(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

enum class RecommendationEvidenceType {
    DIRECT_RELATED_TITLE,
    SAME_FRANCHISE,
    SHARED_CREATOR,
    SHARED_WRITER,
    SHARED_CAST,
    SHARED_COMPANY,
    SHARED_NETWORK,
    SHARED_KEYWORD,
    SHARED_GENRE,
    CATALOGUE_FILTER,
    SEMANTIC_MATCH,
    MOOD_MATCH,
    THEME_MATCH,
    LEARNED_PREFERENCE,
    SOURCE_AGREEMENT,
    QUALITY,
    POPULARITY,
    RUNTIME_MATCH,
    YEAR_MATCH,
    LANGUAGE_MATCH,
}

data class RecommendationEvidence(
    val type: RecommendationEvidenceType,
    val strength: Double,
    val source: String,
    val description: String,
    val sourceRank: Int? = null,
) {
    val normalizedStrength: Double get() = strength.coerceIn(0.0, 1.0)

    val isAnchorGraphEvidence: Boolean
        get() = type in ANCHOR_GRAPH_EVIDENCE

    companion object {
        val ANCHOR_GRAPH_EVIDENCE = setOf(
            RecommendationEvidenceType.DIRECT_RELATED_TITLE,
            RecommendationEvidenceType.SAME_FRANCHISE,
            RecommendationEvidenceType.SHARED_CREATOR,
            RecommendationEvidenceType.SHARED_WRITER,
            RecommendationEvidenceType.SHARED_CAST,
            RecommendationEvidenceType.SHARED_COMPANY,
            RecommendationEvidenceType.SHARED_NETWORK,
            RecommendationEvidenceType.SHARED_KEYWORD,
            RecommendationEvidenceType.SHARED_GENRE,
        )
    }
}

data class RecommendationMatchReason(
    val evidenceType: RecommendationEvidenceType,
    val text: String,
    val contribution: Double,
    val source: String,
    val hardConstraint: Boolean = false,
)

data class RecommendationRuntimeIntent(
    val minimumMinutes: Int? = null,
    val maximumMinutes: Int? = null,
    val preferredMinutes: Int? = null,
    val relativeToAnchor: RelativeRuntimePreference? = null,
)

data class RecommendationYearIntent(
    val minimum: Int? = null,
    val maximum: Int? = null,
)

/** Explicit ranking intent. Hard filters and ranking preferences are never conflated. */
data class RecommendationIntent(
    val requiredMediaType: RecommendationContentType?,
    val hardIncludedGenres: Set<String>,
    val softIncludedGenres: Set<String>,
    val excludedGenres: Set<String>,
    val moods: Set<RecommendationMood>,
    val themes: Set<SemanticFacet>,
    val excludedThemes: Set<SemanticFacet>,
    val freeTextPreferences: Set<String>,
    val freeTextExclusions: Set<String>,
    val creators: Set<String>,
    val cast: Set<String>,
    val countries: Set<String>,
    val viewingContext: ViewingContext?,
    val familiarity: FamiliarityPreference?,
    val runtime: RecommendationRuntimeIntent,
    val year: RecommendationYearIntent,
    val minimumImdb: Double?,
    val minimumRottenTomatoes: Int?,
    val minimumTmdb: Double?,
    val originalLanguage: String?,
    val requiredStatus: String?,
    val titleAnchor: TitleAnchorResolution?,
    val semanticQuery: String,
    val surpriseMe: Boolean,
) {
    val isTitleSimilarityRequest: Boolean get() = titleAnchor != null

    val resolvedAnchor: CanonicalTitleAnchor?
        get() = (titleAnchor as? TitleAnchorResolution.Resolved)?.anchor

    val hasSubjectiveIntent: Boolean
        get() = moods.isNotEmpty() || themes.isNotEmpty() ||
            freeTextPreferences.isNotEmpty() || viewingContext != null

    companion object {
        fun from(
            preferences: RecommendationPreferences,
            titleAnchor: TitleAnchorResolution? = preferences.similarityTitle?.value?.let {
                TitleAnchorResolution.NotFound(it)
            },
        ): RecommendationIntent = RecommendationIntent(
            requiredMediaType = preferences.contentType?.value,
            hardIncludedGenres = preferences.includedGenres
                .filter { it.strength == ConstraintStrength.HARD }
                .mapTo(linkedSetOf()) { it.value },
            softIncludedGenres = preferences.includedGenres
                .filter { it.strength == ConstraintStrength.SOFT }
                .mapTo(linkedSetOf()) { it.value },
            excludedGenres = preferences.excludedGenres.mapTo(linkedSetOf()) { it.value },
            moods = preferences.moods.mapTo(linkedSetOf()) { it.value },
            themes = preferences.semanticFacets.mapTo(linkedSetOf()) { it.value },
            excludedThemes = preferences.excludedFacets.mapTo(linkedSetOf()) { it.value },
            freeTextPreferences = preferences.unmatchedPreferences
                .filterNot(UnmatchedPreference::negated)
                .mapTo(linkedSetOf()) { it.text },
            freeTextExclusions = preferences.unmatchedPreferences
                .filter(UnmatchedPreference::negated)
                .mapTo(linkedSetOf()) { it.text },
            creators = preferences.creatorNames.mapTo(linkedSetOf()) { it.value },
            cast = preferences.castNames.mapTo(linkedSetOf()) { it.value },
            countries = preferences.countryPreferences.mapTo(linkedSetOf()) { it.value },
            viewingContext = preferences.viewingContext?.value,
            familiarity = preferences.familiarity?.value,
            runtime = RecommendationRuntimeIntent(
                minimumMinutes = preferences.runtimeMinimumMinutes?.value,
                maximumMinutes = preferences.runtimeMaximumMinutes?.value,
                preferredMinutes = preferences.preferredRuntimeMinutes?.value,
                relativeToAnchor = preferences.relativeRuntime?.value,
            ),
            year = RecommendationYearIntent(
                minimum = preferences.yearMinimum?.value,
                maximum = preferences.yearMaximum?.value,
            ),
            minimumImdb = preferences.minimumImdb?.value,
            minimumRottenTomatoes = preferences.minimumRottenTomatoes?.value,
            minimumTmdb = preferences.minimumTmdb?.value,
            originalLanguage = preferences.originalLanguage?.value,
            requiredStatus = preferences.requiredStatus?.value,
            titleAnchor = titleAnchor,
            semanticQuery = RecommendationQueryBuilder.build(preferences),
            surpriseMe = preferences.surpriseMe,
        )

        fun fromResolvedAnchor(
            preferences: RecommendationPreferences,
            resolvedAnchor: Media?,
        ): RecommendationIntent {
            val resolution = resolvedAnchor?.let { media ->
                TitleAnchorResolution.Resolved(
                    query = preferences.similarityTitle?.value ?: media.title,
                    anchor = CanonicalTitleAnchor.from(media),
                    matchedTitle = media.title,
                    confidence = 1.0,
                )
            } ?: preferences.similarityTitle?.value?.let {
                TitleAnchorResolution.NotFound(it)
            }
            return from(preferences, resolution)
        }
    }
}

enum class RecommendationConfidenceBand {
    HIGH,
    MEDIUM,
    LOW,
}

data class RecommendationRankingSnapshot(
    val ranked: List<RecommendationCandidate>,
    val rejectedLowConfidence: List<RecommendationCandidate>,
    val confidenceThreshold: Double,
    val scoredCandidateCount: Int,
    val diversificationPoolSize: Int,
) {
    val confidenceBand: RecommendationConfidenceBand
        get() {
            val confidence = ranked.firstOrNull()?.score?.confidence
                ?: return RecommendationConfidenceBand.LOW
            return when {
                confidence >= 0.72 -> RecommendationConfidenceBand.HIGH
                confidence >= confidenceThreshold -> RecommendationConfidenceBand.MEDIUM
                else -> RecommendationConfidenceBand.LOW
            }
        }
}

internal fun Iterable<RecommendationEvidence>.combinedStrength(
    weight: (RecommendationEvidence) -> Double = { 1.0 },
): Double {
    var remaining = 1.0
    forEach { evidence ->
        val contribution = (evidence.normalizedStrength * max(0.0, weight(evidence)))
            .coerceIn(0.0, 1.0)
        remaining *= 1.0 - contribution
    }
    return (1.0 - remaining).coerceIn(0.0, 1.0)
}
