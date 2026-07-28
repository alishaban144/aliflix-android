package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType

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
    FAMILIARITY,
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
    val similarityTitle: PreferenceSignal<String>? = null,
    val relativeRuntime: PreferenceSignal<RelativeRuntimePreference>? = null,
    val familiarity: PreferenceSignal<FamiliarityPreference>? = null,
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
            similarityTitle,
            relativeRuntime,
            familiarity,
        ).count { it.origin == PreferenceOrigin.EXPLICIT } +
            moods.count { it.origin == PreferenceOrigin.EXPLICIT } +
            includedGenres.count { it.origin == PreferenceOrigin.EXPLICIT } +
            excludedGenres.count { it.origin == PreferenceOrigin.EXPLICIT }
}

data class VerifiedMediaMetadata(
    val runtimeMinutes: Int? = null,
    val originalLanguage: String? = null,
    val status: String? = null,
    val director: String? = null,
    val seasonCount: Int? = null,
    val averageEpisodeRuntimeMinutes: Int? = null,
    val verifiedAtMillis: Long = System.currentTimeMillis(),
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
)

data class RecommendationCandidate(
    val media: Media,
    val metadata: VerifiedMediaMetadata = VerifiedMediaMetadata(),
    val evidence: String = "",
    val sources: Set<String> = emptySet(),
    val sourceCount: Int = 0,
    val sourcePosition: Int = 99,
    val score: RecommendationScoreBreakdown = RecommendationScoreBreakdown(),
    val explanation: String = "",
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
        val webLimited: Boolean = false,
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
