package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.security.MessageDigest

data class RetrievalLedger(
    val searchedKeywordPhrases: MutableSet<String> = mutableSetOf(),
    val searchedBroadPhrases: MutableSet<String> = mutableSetOf(),
    val resolvedKeywordIds: MutableSet<Int> = mutableSetOf(),
    val searchedDiscoverParams: MutableSet<String> = mutableSetOf(),
    val searchedPages: MutableSet<String> = mutableSetOf(),
    val discoveredTmdbIds: MutableSet<Int> = mutableSetOf(),
)

enum class PreferenceOrigin {
    EXPLICIT,
    REJECTED,
    INFERRED,
    PERSISTED,
}

enum class ConstraintStrength {
    HARD,
    SOFT,
}

data class PreferenceSignal<T>(
    val value: T,
    val origin: PreferenceOrigin,
    val strength: ConstraintStrength,
    val confidence: Double = 1.0,
)

enum class SemanticFacetCategory {
    SUBGENRE,
    THEME,
    TONE,
    PACE,
    SETTING,
    PLOT_DEVICE,
    STYLE,
    NARRATIVE,
    AUDIENCE,
    CONTENT_INTENSITY,
}

data class SemanticFacet(
    val id: String,
    val label: String,
    val category: SemanticFacetCategory,
    val discoveryTerms: List<String> = listOf(label),
)

data class UnmatchedPreference(
    val text: String,
    val negated: Boolean = false,
    val confidence: Double = 0.65,
)

data class PreferenceCorrection(
    val key: String,
    val replacement: String?,
)

enum class RecommendationContentType {
    MOVIE,
    TV,
    EITHER;

    fun accepts(type: MediaType): Boolean = when (this) {
        MOVIE -> type == MediaType.MOVIE
        TV -> type == MediaType.TV
        EITHER -> true
    }
}

/**
 * The mandatory first choice in recommendation discovery.
 *
 * This intentionally has no "either" value: choosing a concrete catalogue is
 * what lets the app build a structured, paged query before doing any network
 * work.
 */
enum class RecommendationMediaKind {
    MOVIE,
    SERIES;

    val contentType: RecommendationContentType
        get() = when (this) {
            MOVIE -> RecommendationContentType.MOVIE
            SERIES -> RecommendationContentType.TV
        }

    val mediaType: MediaType
        get() = when (this) {
            MOVIE -> MediaType.MOVIE
            SERIES -> MediaType.TV
        }
}

data class CatalogDiscoverySpec(
    val mediaKind: RecommendationMediaKind,
    val includedGenres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val runtimeMinimumMinutes: Int? = null,
    val runtimeMaximumMinutes: Int? = null,
    val yearMinimum: Int? = null,
    val yearMaximum: Int? = null,
    val minimumImdb: Double? = null,
    val minimumRottenTomatoes: Int? = null,
    val minimumTmdb: Double? = null,
    val originalLanguage: String? = null,
    val requiredStatus: String? = null,
    val moods: List<String> = emptyList(),
    val viewingContext: String? = null,
    val familiarity: String? = null,
    val similarityTitle: String? = null,
    val semanticFacets: List<String> = emptyList(),
    val excludedFacets: List<String> = emptyList(),
    val creatorNames: List<String> = emptyList(),
    val castNames: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val supplementalTerms: List<String> = emptyList(),
    val discoveryText: String = "",
    val surpriseMe: Boolean = false,
    val sourcePolicyVersion: Int = 2,
    val semanticModelVersion: String = "use-v1",
) {
    /**
     * A stable, conversation-free key suitable for persistent page caches.
     */
    val fingerprint: String
        get() = listOf(
            mediaKind.name,
            includedGenres.normalizedKey(),
            excludedGenres.normalizedKey(),
            runtimeMinimumMinutes.orEmptyKey(),
            runtimeMaximumMinutes.orEmptyKey(),
            yearMinimum.orEmptyKey(),
            yearMaximum.orEmptyKey(),
            minimumImdb?.toString().orEmpty(),
            minimumRottenTomatoes.orEmptyKey(),
            minimumTmdb?.toString().orEmpty(),
            originalLanguage.orEmpty().trim().lowercase(),
            requiredStatus.orEmpty().trim().lowercase(),
            moods.normalizedKey(),
            viewingContext.orEmpty().trim().lowercase(),
            familiarity.orEmpty().trim().lowercase(),
            similarityTitle.orEmpty().trim().lowercase(),
            semanticFacets.normalizedKey(),
            excludedFacets.normalizedKey(),
            creatorNames.normalizedKey(),
            castNames.normalizedKey(),
            countries.normalizedKey(),
            stableHash(supplementalTerms.normalizedKey()),
            stableHash(discoveryText.trim().lowercase()),
            surpriseMe.toString(),
            sourcePolicyVersion.toString(),
            semanticModelVersion,
        ).joinToString("|")

    private fun List<String>.normalizedKey(): String = asSequence()
        .map { it.trim().lowercase() }
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .joinToString(",")

    private fun Int?.orEmptyKey(): String = this?.toString().orEmpty()

    private fun stableHash(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(10)
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun from(preferences: RecommendationPreferences): CatalogDiscoverySpec? {
            val kind = when (preferences.contentType?.value) {
                RecommendationContentType.MOVIE -> RecommendationMediaKind.MOVIE
                RecommendationContentType.TV -> RecommendationMediaKind.SERIES
                RecommendationContentType.EITHER,
                null,
                -> null
            } ?: return null
            return CatalogDiscoverySpec(
                mediaKind = kind,
                includedGenres = preferences.includedGenres.map { it.value },
                excludedGenres = preferences.excludedGenres.map { it.value },
                runtimeMinimumMinutes = preferences.runtimeMinimumMinutes?.value,
                runtimeMaximumMinutes = preferences.runtimeMaximumMinutes?.value,
                yearMinimum = preferences.yearMinimum?.value,
                yearMaximum = preferences.yearMaximum?.value,
                minimumImdb = preferences.minimumImdb?.value,
                minimumRottenTomatoes =
                    preferences.minimumRottenTomatoes?.value,
                minimumTmdb = preferences.minimumTmdb?.value,
                originalLanguage = preferences.originalLanguage?.value,
                requiredStatus = preferences.requiredStatus?.value,
                moods = preferences.moods.map { it.value.label },
                viewingContext = preferences.viewingContext?.value?.label,
                familiarity = preferences.familiarity?.value?.label,
                similarityTitle = preferences.similarityTitle?.value,
                semanticFacets = preferences.semanticFacets.map { it.value.label },
                excludedFacets = preferences.excludedFacets.map { it.value.label },
                creatorNames = preferences.creatorNames.map { it.value },
                castNames = preferences.castNames.map { it.value },
                countries = preferences.countryPreferences.map { it.value },
                supplementalTerms = (
                    preferences.semanticFacets.flatMap { it.value.discoveryTerms } +
                        preferences.unmatchedPreferences.map { it.text } +
                        preferences.unverifiedTerms
                    ).distinct(),
                discoveryText = RecommendationQueryBuilder.build(preferences),
                surpriseMe = preferences.surpriseMe,
            )
        }
    }
}

data class RecommendationRequestDraft(
    val mediaType: MediaType? = null,
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val yearRule: String? = null,
    val runtimeRule: String? = null,
    val minimumImdb: Double? = null,
    val language: String? = null,
    val status: String? = null,
    val exclusions: List<String> = emptyList(),
    val similarityTitle: String? = null,
    val similarityAnchor: Media? = null,
    val similarityAnchorTmdbId: Int? = null,
    val similarityAnchorMediaType: MediaType? = null,
    val similarityAnchorImdbId: String? = null,
    val freeText: String = "",
)

data class ResolvedKeywordGroup(
    val groupId: String,
    val keywordIds: List<Int>,
    val phrases: List<String> = emptyList(),
)

data class RecommendationPageCursor(
    val page: Int = 1,
    val seenKeys: Set<String> = emptySet(),
    val imdbPopularityCursor: String? = null,
    val imdbRatingCursor: String? = null,
    val imdbHtmlFallback: Boolean = false,
    val imdbTmdbFallback: Boolean = false,
    val exhaustedSources: Set<String> = emptySet(),
)

data class RequiredMetadataFields(
    val genres: Boolean = false,
    val runtime: Boolean = false,
    val originalLanguage: Boolean = false,
    val imdbRating: Boolean = false,
    val rottenTomatoesRating: Boolean = false,
    val tmdbRating: Boolean = false,
    val tvEpisodeRuntime: Boolean = false,
    val status: Boolean = false,
) {
    val needsTitlePage: Boolean
        get() = genres || runtime || originalLanguage || tvEpisodeRuntime || status

    companion object {
        fun from(preferences: RecommendationPreferences): RequiredMetadataFields =
            RequiredMetadataFields(
                genres = preferences.excludedGenres.isNotEmpty() ||
                    preferences.includedGenres.isNotEmpty(),
                runtime = preferences.runtimeMinimumMinutes?.strength ==
                    ConstraintStrength.HARD ||
                    preferences.runtimeMaximumMinutes?.strength == ConstraintStrength.HARD,
                originalLanguage = preferences.originalLanguage?.strength ==
                    ConstraintStrength.HARD,
                imdbRating = preferences.minimumImdb?.strength == ConstraintStrength.HARD,
                rottenTomatoesRating =
                    preferences.minimumRottenTomatoes?.strength == ConstraintStrength.HARD,
                tmdbRating = preferences.minimumTmdb?.strength == ConstraintStrength.HARD,
                tvEpisodeRuntime = preferences.contentType?.value ==
                    RecommendationContentType.TV &&
                    (
                        preferences.runtimeMinimumMinutes?.strength ==
                            ConstraintStrength.HARD ||
                            preferences.runtimeMaximumMinutes?.strength ==
                            ConstraintStrength.HARD
                        ),
                status = preferences.requiredStatus?.strength ==
                    ConstraintStrength.HARD,
            )
    }
}

enum class RecommendationSourceStatus {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
    NOT_REQUIRED,
}

data class RecommendationSourceHealth(
    val catalogue: RecommendationSourceStatus = RecommendationSourceStatus.AVAILABLE,
    val imdb: RecommendationSourceStatus = RecommendationSourceStatus.NOT_REQUIRED,
    val web: RecommendationSourceStatus = RecommendationSourceStatus.NOT_REQUIRED,
    val reddit: RecommendationSourceStatus = RecommendationSourceStatus.NOT_REQUIRED,
) {
    val requiredSourceUnavailable: Boolean
        get() = catalogue == RecommendationSourceStatus.UNAVAILABLE ||
            imdb == RecommendationSourceStatus.UNAVAILABLE

    fun merge(other: RecommendationSourceHealth): RecommendationSourceHealth =
        RecommendationSourceHealth(
            catalogue = catalogue.merge(other.catalogue),
            imdb = imdb.merge(other.imdb),
            web = web.merge(other.web),
            reddit = reddit.merge(other.reddit),
        )

    private fun RecommendationSourceStatus.merge(
        other: RecommendationSourceStatus,
    ): RecommendationSourceStatus {
        if (this == RecommendationSourceStatus.UNAVAILABLE ||
            other == RecommendationSourceStatus.UNAVAILABLE
        ) {
            return RecommendationSourceStatus.UNAVAILABLE
        }
        if (this == RecommendationSourceStatus.DEGRADED ||
            other == RecommendationSourceStatus.DEGRADED
        ) {
            return RecommendationSourceStatus.DEGRADED
        }
        if (this == RecommendationSourceStatus.AVAILABLE ||
            other == RecommendationSourceStatus.AVAILABLE
        ) {
            return RecommendationSourceStatus.AVAILABLE
        }
        return RecommendationSourceStatus.NOT_REQUIRED
    }
}

enum class RecommendationMood(val label: String) {
    FUNNY("Funny"),
    SCARY("Scary"),
    EMOTIONAL("Emotional"),
    RELAXING("Relaxing"),
    MIND_BENDING("Mind-bending"),
    INTENSE("Intense"),
    ROMANTIC("Romantic"),
    EXCITING("Exciting"),
    DARK("Dark"),
    FEEL_GOOD("Feel-good"),
    THOUGHT_PROVOKING("Thought-provoking"),
    NOSTALGIC("Nostalgic"),
}

enum class ViewingContext(val label: String) {
    ALONE("Alone"),
    PARTNER("With a partner"),
    FRIENDS("With friends"),
    FAMILY("With family"),
    CHILDREN("With children"),
    GROUP("With a group"),
}

enum class FamiliarityPreference(val label: String) {
    POPULAR("Popular"),
    HIDDEN_GEM("Hidden gem"),
    OBSCURE("Something obscure"),
    FAMILIAR("Something everyone knows"),
}

enum class RelativeRuntimePreference {
    SHORTER_THAN_ANCHOR,
    LONGER_THAN_ANCHOR,
}

enum class RecommendationDimension {
    MOOD,
    CONTENT_TYPE,
    GENRE,
    VIEWING_CONTEXT,
    RUNTIME,
    ERA,
    QUALITY,
    LANGUAGE,
    STATUS,
    FAMILIARITY,
    SUBJECTIVE_FACET,
    UNSUPPORTED_CONFIRMATION,
}

data class RecommendationPreferences(
    val contentType: PreferenceSignal<RecommendationContentType>? = null,
    val moods: List<PreferenceSignal<RecommendationMood>> = emptyList(),
    val includedGenres: List<PreferenceSignal<String>> = emptyList(),
    val excludedGenres: List<PreferenceSignal<String>> = emptyList(),
    val viewingContext: PreferenceSignal<ViewingContext>? = null,
    val runtimeMinimumMinutes: PreferenceSignal<Int>? = null,
    val runtimeMaximumMinutes: PreferenceSignal<Int>? = null,
    val preferredRuntimeMinutes: PreferenceSignal<Int>? = null,
    val yearMinimum: PreferenceSignal<Int>? = null,
    val yearMaximum: PreferenceSignal<Int>? = null,
    val minimumImdb: PreferenceSignal<Double>? = null,
    val minimumRottenTomatoes: PreferenceSignal<Int>? = null,
    val minimumTmdb: PreferenceSignal<Double>? = null,
    val originalLanguage: PreferenceSignal<String>? = null,
    val requiredStatus: PreferenceSignal<String>? = null,
    val similarityTitle: PreferenceSignal<String>? = null,
    val relativeRuntime: PreferenceSignal<RelativeRuntimePreference>? = null,
    val familiarity: PreferenceSignal<FamiliarityPreference>? = null,
    val semanticFacets: List<PreferenceSignal<SemanticFacet>> = emptyList(),
    val excludedFacets: List<PreferenceSignal<SemanticFacet>> = emptyList(),
    val unmatchedPreferences: List<UnmatchedPreference> = emptyList(),
    val creatorNames: List<PreferenceSignal<String>> = emptyList(),
    val castNames: List<PreferenceSignal<String>> = emptyList(),
    val countryPreferences: List<PreferenceSignal<String>> = emptyList(),
    val surpriseMe: Boolean = false,
    val unverifiedTerms: List<String> = emptyList(),
    val answeredDimensions: Set<RecommendationDimension> = emptySet(),
    val askedQuestionIds: List<String> = emptyList(),
    val shownKeys: Set<String> = emptySet(),
    val rejectedKeys: Set<String> = emptySet(),
) {
    val explicitSignalCount: Int
        get() = listOfNotNull(
            contentType,
            viewingContext,
            runtimeMinimumMinutes,
            runtimeMaximumMinutes,
            preferredRuntimeMinutes,
            yearMinimum,
            yearMaximum,
            minimumImdb,
            minimumRottenTomatoes,
            minimumTmdb,
            originalLanguage,
            requiredStatus,
            similarityTitle,
            relativeRuntime,
            familiarity,
        ).count { it.origin == PreferenceOrigin.EXPLICIT } +
            moods.count { it.origin == PreferenceOrigin.EXPLICIT } +
            includedGenres.count { it.origin == PreferenceOrigin.EXPLICIT } +
            excludedGenres.count { it.origin == PreferenceOrigin.EXPLICIT } +
            semanticFacets.count { it.origin == PreferenceOrigin.EXPLICIT } +
            excludedFacets.count { it.origin == PreferenceOrigin.EXPLICIT } +
            creatorNames.count { it.origin == PreferenceOrigin.EXPLICIT } +
            castNames.count { it.origin == PreferenceOrigin.EXPLICIT } +
            countryPreferences.count { it.origin == PreferenceOrigin.EXPLICIT }
}

data class VerifiedMediaMetadata(
    val genresVerified: Boolean = true,
    val runtimeMinutes: Int? = null,
    val originalLanguage: String? = null,
    val originCountries: List<String> = emptyList(),
    val tmdbVoteCount: Int? = null,
    val status: String? = null,
    val director: String? = null,
    val seasonCount: Int? = null,
    val averageEpisodeRuntimeMinutes: Int? = null,
    val verifiedAtMillis: Long = System.currentTimeMillis(),
)

enum class MetadataSource {
    IMDB_PUBLIC,
    TMDB,
    LOCAL_ANALYSIS,
    RELATIONSHIP_DATA,
    USER_INPUT,
    CACHE
}

enum class RecommendationRejectionReason {
    WRONG_MEDIA_TYPE,
    EXCLUDED_GENRE,
    EXCLUDED_TRAIT,
    EXCLUDED_TITLE,
    CONTRADICTION,
    LOW_RELEVANCE,
    BELOW_RATING_THRESHOLD,
    ALREADY_SEEN,
    EXCLUDED_KEYWORD,
    OUTSIDE_YEAR_RANGE,
    OUTSIDE_RUNTIME_RANGE,
    WRONG_LANGUAGE,
    WRONG_COUNTRY,
}

data class RecommendationTag(
    val name: String,
    val category: String = "trait",
    val specificityWeight: Double = 1.0,
    val source: MetadataSource = MetadataSource.LOCAL_ANALYSIS,
)

data class WeightedTagMatch(
    val tag: String,
    val weight: Double,
    val matchedText: String,
    val source: MetadataSource = MetadataSource.LOCAL_ANALYSIS,
)

data class WeightedTagConflict(
    val tag: String,
    val weight: Double,
    val conflictReason: String,
)

data class AnchorTitleProfile(
    val titleId: String,
    val mediaType: MediaType,
    val genres: Set<String> = emptySet(),
    val keywords: Set<RecommendationTag> = emptySet(),
    val themes: Set<String> = emptySet(),
    val tones: Set<String> = emptySet(),
    val settings: Set<String> = emptySet(),
    val narrativeTraits: Set<String> = emptySet(),
    val creators: Set<String> = emptySet(),
    val writers: Set<String> = emptySet(),
    val directors: Set<String> = emptySet(),
    val mainCast: Set<String> = emptySet(),
    val companies: Set<String> = emptySet(),
    val networks: Set<String> = emptySet(),
    val franchiseIds: Set<String> = emptySet(),
    val relatedTitleIds: Set<String> = emptySet(),
    val releaseYear: Int? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val voteCount: Long? = null,
)

data class CandidateRelevanceProfile(
    val mediaKey: String,
    val mediaType: MediaType,
    val genres: Set<String> = emptySet(),
    val keywords: Set<RecommendationTag> = emptySet(),
    val themes: Set<String> = emptySet(),
    val tones: Set<String> = emptySet(),
    val settings: Set<String> = emptySet(),
    val narrativeTraits: Set<String> = emptySet(),
    val creators: Set<String> = emptySet(),
    val writers: Set<String> = emptySet(),
    val directors: Set<String> = emptySet(),
    val mainCast: Set<String> = emptySet(),
    val companies: Set<String> = emptySet(),
    val networks: Set<String> = emptySet(),
    val franchiseIds: Set<String> = emptySet(),
    val relatedTitleIds: Set<String> = emptySet(),
    val releaseYear: Int? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val voteCount: Long? = null,
)

data class RecommendationScoreBreakdown(
    val contentMatch: Double = 0.0,
    val similarity: Double = 0.0,
    val contextualFit: Double = 0.0,
    val quality: Double = 0.0,
    val taste: Double = 0.0,
    val discovery: Double = 0.0,
    val novelty: Double = 0.0,
    val coverage: Double = 1.0,
    val total: Double = 0.0,
    val anchorRelevance: Double = 0.0,
    val semanticRelevance: Double = 0.0,
    val confidence: Double = 0.0,
    val hardConstraintsPassed: Boolean = true,
    val matchedTags: List<WeightedTagMatch> = emptyList(),
    val contradictedTags: List<WeightedTagConflict> = emptyList(),
    val relationshipScore: Double = 0.0,
    val traitScore: Double = 0.0,
    val semanticScore: Double = 0.0,
    val qualityScore: Double = 0.0,
    val personalizationScore: Double = 0.0,
    val finalScore: Double = 0.0,
    val rejectionReason: RecommendationRejectionReason? = null,
)

data class RecommendationCandidate(
    val media: Media,
    val metadata: VerifiedMediaMetadata = VerifiedMediaMetadata(),
    val evidence: String = "",
    val sources: Set<String> = emptySet(),
    val sourceRanks: Map<String, Int> = emptyMap(),
    val sourceCount: Int = 0,
    val sourcePosition: Int = 99,
    val score: RecommendationScoreBreakdown = RecommendationScoreBreakdown(),
    val explanation: String = "",
    val relevanceEvidence: List<RecommendationEvidence> = emptyList(),
    val precomputedSemanticScore: Double? = null,
    val matchReasons: List<RecommendationMatchReason> = emptyList(),
    val alternativeTitles: Set<String> = emptySet(),
)

data class CandidateSourceEvidence(
    val sourceFamily: String,
    val rank: Int,
    val evidence: String = "",
)

data class CandidateDocument(
    val mediaKey: String,
    val text: String,
)

data class CandidateAggregate(
    val candidate: RecommendationCandidate,
    val evidence: List<CandidateSourceEvidence>,
)

data class RecommendationQueryGeneration(
    val id: Long,
    val fingerprint: String,
)

data class RecommendationPageV2(
    val generation: RecommendationQueryGeneration,
    val items: List<RecommendationCandidate>,
    val hasMore: Boolean,
    val sourceHealth: RecommendationSourceHealth,
)

enum class RecommendationQuestionType {
    SINGLE_SELECT,
    MULTI_SELECT,
}

data class RecommendationOption(
    val id: String,
    val label: String,
    val value: String,
)

data class RecommendationQuestion(
    val id: String,
    val dimension: RecommendationDimension,
    val text: String,
    val type: RecommendationQuestionType,
    val options: List<RecommendationOption>,
    val supportingText: String? = null,
)

data class ConstraintRelaxation(
    val id: String,
    val label: String,
    val recoveredCandidates: Int,
)

sealed interface RecommendationDecision {
    data class AskQuestion(
        val question: RecommendationQuestion,
        val progressMessage: String,
    ) : RecommendationDecision

    data class Recommend(
        val candidates: List<RecommendationCandidate>,
    ) : RecommendationDecision

    data class RelaxConstraint(
        val message: String,
        val options: List<ConstraintRelaxation>,
    ) : RecommendationDecision

    data class Failed(
        val message: String,
        val canRetry: Boolean,
    ) : RecommendationDecision
}

sealed interface RecommendationUiState {
    data object Idle : RecommendationUiState

    data class SelectType(
        val preferences: RecommendationPreferences = RecommendationPreferences(),
    ) : RecommendationUiState

    data class Discovering(
        val preferences: RecommendationPreferences,
        val message: String,
    ) : RecommendationUiState

    data class Question(
        val preferences: RecommendationPreferences,
        val question: RecommendationQuestion,
        val progressMessage: String,
        val canGoBack: Boolean,
    ) : RecommendationUiState

    data class Results(
        val preferences: RecommendationPreferences,
        val candidates: List<RecommendationCandidate>,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val pageError: String? = null,
        val refinementQuestion: RecommendationQuestion? = null,
        val sourceHealth: RecommendationSourceHealth = RecommendationSourceHealth(),
        @Deprecated("Use sourceHealth")
        val webLimited: Boolean = false,
    ) : RecommendationUiState

    data class Empty(
        val preferences: RecommendationPreferences,
        val message: String,
        val options: List<ConstraintRelaxation> = emptyList(),
    ) : RecommendationUiState

    data class SourceUnavailable(
        val preferences: RecommendationPreferences,
        val message: String,
        val canRetry: Boolean = true,
    ) : RecommendationUiState

    data class Relaxation(
        val preferences: RecommendationPreferences,
        val message: String,
        val options: List<ConstraintRelaxation>,
    ) : RecommendationUiState

    data class Error(
        val preferences: RecommendationPreferences,
        val message: String,
        val canRetry: Boolean,
    ) : RecommendationUiState
}

data class RecommendationPage(
    val candidates: List<RecommendationCandidate>,
    val nextCursor: RecommendationPageCursor?,
    val hasMore: Boolean,
    val sourceHealth: RecommendationSourceHealth,
    val fromCache: Boolean = false,
)

data class TasteSignal(
    val key: String,
    val positiveObservations: Int = 0,
    val negativeObservations: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val confidence: Double
        get() = (
            positiveObservations + negativeObservations
            ).toDouble() / (positiveObservations + negativeObservations + 3.0)

    val affinity: Double
        get() {
            val observations = positiveObservations + negativeObservations
            if (observations == 0) return 0.0
            return (positiveObservations - negativeObservations).toDouble() / observations
        }
}

data class TasteProfile(
    val signals: Map<String, TasteSignal> = emptyMap(),
    val seenKeys: Set<String> = emptySet(),
)
