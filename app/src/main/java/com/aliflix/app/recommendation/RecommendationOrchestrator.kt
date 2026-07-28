package com.aliflix.app.recommendation

import com.aliflix.app.data.CatalogClient
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class RecommendationCandidateBatch(
    val candidates: List<RecommendationCandidate>,
    val webAvailable: Boolean,
)

interface RecommendationCandidateRepository {
    suspend fun discover(preferences: RecommendationPreferences): RecommendationCandidateBatch
    suspend fun resolveSimilarityAnchor(title: String): RecommendationCandidate?
}

class CatalogRecommendationCandidateRepository(
    private val client: CatalogClient,
) : RecommendationCandidateRepository {
    override suspend fun discover(
        preferences: RecommendationPreferences,
    ): RecommendationCandidateBatch = supervisorScope {
        val requestedType = when (preferences.contentType?.value) {
            RecommendationContentType.MOVIE -> MediaType.MOVIE
            RecommendationContentType.TV -> MediaType.TV
            RecommendationContentType.EITHER,
            null,
            -> null
        }
        val discovery = client.recommendationCandidates(
            request = RecommendationQueryBuilder.build(preferences),
            requestedType = requestedType,
        )
        val verificationGate = Semaphore(METADATA_CONCURRENCY)
        val verificationLimit = if (hasMetadataHardConstraint(preferences)) {
            HARD_CONSTRAINT_VERIFICATION_LIMIT
        } else {
            DEFAULT_VERIFICATION_LIMIT
        }
        val prioritized = discovery.items
            .sortedWith(
                compareByDescending<com.aliflix.app.data.RecommendationDiscoveryItem> {
                    it.sourceCount
                }
                    .thenBy { it.sourcePosition }
                    .thenByDescending { it.media.rating },
            )
            .take(verificationLimit)
        val verified = prioritized.map { seed ->
            async {
                verificationGate.withPermit {
                    val result = try {
                        client.verifyRecommendationItem(seed.media)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                    RecommendationCandidate(
                        media = result?.media ?: seed.media,
                        metadata = result?.metadata?.let {
                            VerifiedMediaMetadata(
                                runtimeMinutes = it.runtimeMinutes,
                                originalLanguage = it.originalLanguage,
                                status = it.status,
                                director = it.director,
                                seasonCount = it.seasonCount,
                                averageEpisodeRuntimeMinutes =
                                    it.averageEpisodeRuntimeMinutes,
                                verifiedAtMillis = it.verifiedAtMillis,
                            )
                        } ?: VerifiedMediaMetadata(),
                        evidence = seed.evidence,
                        sources = seed.sources,
                        sourceCount = seed.sourceCount,
                        sourcePosition = seed.sourcePosition,
                    )
                }
            }
        }.awaitAll()
        RecommendationCandidateBatch(
            candidates = verified,
            webAvailable = discovery.webAvailable,
        )
    }

    override suspend fun resolveSimilarityAnchor(title: String): RecommendationCandidate? {
        val media = client.resolveRecommendationAnchor(title) ?: return null
        val verified = client.verifyRecommendationItem(media)
        return RecommendationCandidate(
            media = verified.media,
            metadata = VerifiedMediaMetadata(
                runtimeMinutes = verified.metadata.runtimeMinutes,
                originalLanguage = verified.metadata.originalLanguage,
                status = verified.metadata.status,
                director = verified.metadata.director,
                seasonCount = verified.metadata.seasonCount,
                averageEpisodeRuntimeMinutes =
                    verified.metadata.averageEpisodeRuntimeMinutes,
                verifiedAtMillis = verified.metadata.verifiedAtMillis,
            ),
        )
    }

    private fun hasMetadataHardConstraint(
        preferences: RecommendationPreferences,
    ): Boolean =
        preferences.runtimeMinimumMinutes?.strength == ConstraintStrength.HARD ||
            preferences.runtimeMaximumMinutes?.strength == ConstraintStrength.HARD ||
            preferences.yearMinimum?.strength == ConstraintStrength.HARD ||
            preferences.yearMaximum?.strength == ConstraintStrength.HARD ||
            preferences.minimumImdb?.strength == ConstraintStrength.HARD ||
            preferences.minimumRottenTomatoes?.strength == ConstraintStrength.HARD ||
            preferences.minimumTmdb?.strength == ConstraintStrength.HARD ||
            preferences.originalLanguage?.strength == ConstraintStrength.HARD

    private companion object {
        const val METADATA_CONCURRENCY = 4
        const val DEFAULT_VERIFICATION_LIMIT = 18
        const val HARD_CONSTRAINT_VERIFICATION_LIMIT = 28
    }
}

object RecommendationQueryBuilder {
    fun build(preferences: RecommendationPreferences): String {
        val parts = buildList {
            add(
                when (preferences.contentType?.value) {
                    RecommendationContentType.MOVIE -> "movie"
                    RecommendationContentType.TV -> "television series"
                    else -> "movie or television series"
                },
            )
            addAll(preferences.includedGenres.map { it.value })
            addAll(preferences.moods.map { it.value.label })
            preferences.viewingContext?.let { add("for ${it.value.label.lowercase()}") }
            preferences.runtimeMaximumMinutes?.let { add("under ${it.value} minutes") }
            preferences.runtimeMinimumMinutes?.let { add("at least ${it.value} minutes") }
            preferences.preferredRuntimeMinutes?.let { add("around ${it.value} minutes") }
            preferences.yearMinimum?.let { add("released after ${it.value - 1}") }
            preferences.yearMaximum?.let { add("released before ${it.value + 1}") }
            preferences.minimumImdb?.let { add("IMDb ${it.value} or higher") }
            preferences.minimumRottenTomatoes?.let { add("RT critic ${it.value}% or higher") }
            preferences.originalLanguage?.let { add("${it.value} original language") }
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

class RecommendationOrchestrator(
    private val scope: CoroutineScope,
    private val repository: RecommendationCandidateRepository,
    private val store: RecommendationStore,
    private val likesProvider: () -> List<Media>,
    private val recentlyPlayedProvider: () -> List<Media>,
) {
    private val _state = MutableStateFlow<RecommendationUiState>(
        RecommendationUiState.Idle,
    )
    val state: StateFlow<RecommendationUiState> = _state.asStateFlow()

    private var preferences = RecommendationPreferences()
    private var candidatePool: List<RecommendationCandidate> = emptyList()
    private var similarityAnchor: RecommendationCandidate? = null
    private var webAvailable = true
    private var job: Job? = null
    private val history = mutableListOf<RecommendationPreferences>()

    fun submitText(text: String) {
        if (text.isBlank()) return
        val previous = preferences
        val parsed = RecommendationPreferenceParser.parse(text, preferences)
        history += previous
        preferences = parsed.preferences
        parsed.confirmation?.let { confirmation ->
            preferences = preferences.copy(
                askedQuestionIds = preferences.askedQuestionIds + confirmation.id,
            )
            _state.value = RecommendationUiState.Question(
                preferences = preferences,
                question = confirmation,
                progressMessage = "One detail needs confirmation before I continue.",
                canGoBack = history.isNotEmpty(),
            )
            return
        }
        discoverAndDecide()
    }

    fun surpriseMe() {
        history += preferences
        preferences = preferences.copy(surpriseMe = true)
        discoverAndDecide()
    }

    fun answer(question: RecommendationQuestion, selectedValues: List<String>) {
        history += preferences
        preferences = applyAnswer(preferences, question, selectedValues)
        decideOrRefresh()
    }

    fun goBack() {
        val previous = history.removeLastOrNull() ?: return
        preferences = previous
        if (candidatePool.isEmpty()) {
            discoverAndDecide()
        } else {
            decide()
        }
    }

    fun restart() {
        job?.cancel()
        preferences = RecommendationPreferences()
        candidatePool = emptyList()
        similarityAnchor = null
        history.clear()
        _state.value = RecommendationUiState.Idle
    }

    fun retry() {
        discoverAndDecide()
    }

    fun requestAnother(
        rejected: Media,
        reason: String? = null,
    ) {
        store.recordRejected(rejected, reason)
        if (reason.equals("I've already seen it", ignoreCase = true)) {
            store.markSeen(rejected)
        }
        preferences = preferences.copy(
            shownKeys = preferences.shownKeys + rejected.key,
            rejectedKeys = preferences.rejectedKeys + rejected.key,
        )
        decideOrRefresh()
    }

    fun accept(media: Media) {
        store.recordAccepted(media)
    }

    fun applyRelaxation(id: String) {
        history += preferences
        preferences = RecommendationRanker.applyRelaxation(preferences, id)
        discoverAndDecide()
    }

    fun resetTaste() {
        store.resetTaste()
        decideOrRefresh()
    }

    private fun discoverAndDecide() {
        job?.cancel()
        _state.value = RecommendationUiState.Discovering(
            preferences = preferences,
            message = "Searching real titles and verifying the strongest matches…",
        )
        job = scope.launch {
            try {
                val anchorRequest = preferences.similarityTitle?.value
                val anchorDeferred = anchorRequest?.let { title ->
                    async { repository.resolveSimilarityAnchor(title) }
                }
                val batch = repository.discover(preferences)
                candidatePool = batch.candidates
                webAvailable = batch.webAvailable
                similarityAnchor = anchorDeferred?.await()
                if (
                    preferences.relativeRuntime != null &&
                    similarityAnchor == null
                ) {
                    _state.value = RecommendationUiState.Error(
                        preferences = preferences,
                        message = "I couldn't verify the comparison title's runtime.",
                        canRetry = true,
                    )
                    return@launch
                }
                preferences = applyRelativeRuntime(preferences, similarityAnchor)
                decide()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (candidatePool.isNotEmpty()) {
                    webAvailable = false
                    decide()
                } else {
                    _state.value = RecommendationUiState.Error(
                        preferences = preferences,
                        message = error.message ?: "Recommendation lookup failed.",
                        canRetry = true,
                    )
                }
            }
        }
    }

    private fun decideOrRefresh() {
        val eligible = RecommendationRanker.hardFilter(
            preferences = preferences,
            candidates = candidatePool,
            recentlyPlayedKeys = recentlyPlayedProvider().map(Media::key).toSet(),
            seenKeys = store.taste.value.seenKeys,
        )
        if (candidatePool.isEmpty() || eligible.size < MIN_POOL_BEFORE_REFRESH) {
            discoverAndDecide()
        } else {
            decide()
        }
    }

    private fun decide() {
        val eligible = RecommendationRanker.hardFilter(
            preferences = preferences,
            candidates = candidatePool,
            recentlyPlayedKeys = recentlyPlayedProvider().map(Media::key).toSet(),
            seenKeys = store.taste.value.seenKeys,
        )
        if (eligible.isEmpty()) {
            val relaxations = RecommendationRanker.relaxationOptions(
                preferences,
                candidatePool,
            )
            _state.value = if (relaxations.isNotEmpty()) {
                RecommendationUiState.Relaxation(
                    preferences = preferences,
                    message = "I couldn't find a verified match with every requirement.",
                    options = relaxations,
                )
            } else {
                RecommendationUiState.Error(
                    preferences = preferences,
                    message = if (webAvailable) {
                        "I couldn't find a strong verified match. Try changing one preference."
                    } else {
                        "Web discovery is unavailable and the cached catalog has no verified match."
                    },
                    canRetry = true,
                )
            }
            return
        }

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = eligible,
            likes = likesProvider(),
            taste = store.taste.value,
            similarityAnchor = similarityAnchor?.media,
        )
        val question = RecommendationQuestionSelector.nextQuestion(preferences, eligible)
        when {
            RecommendationRanker.shouldRecommend(preferences, ranked, question) ->
                showResults(ranked)
            question != null -> {
                preferences = preferences.copy(
                    askedQuestionIds = preferences.askedQuestionIds + question.id,
                )
                _state.value = RecommendationUiState.Question(
                    preferences = preferences,
                    question = question,
                    progressMessage =
                        RecommendationQuestionSelector.progressMessage(preferences),
                    canGoBack = history.isNotEmpty(),
                )
            }
            ranked.size >= 3 &&
                ranked.firstOrNull()?.score?.total ?: 0.0 >=
                RecommendationRanker.RECOMMEND_THRESHOLD -> showResults(ranked)
            else -> {
                val relaxations = RecommendationRanker.relaxationOptions(
                    preferences,
                    candidatePool,
                )
                _state.value = if (relaxations.isNotEmpty()) {
                    RecommendationUiState.Relaxation(
                        preferences = preferences,
                        message = "The remaining matches are weak. Relax one requirement?",
                        options = relaxations,
                    )
                } else {
                    RecommendationUiState.Error(
                        preferences = preferences,
                        message = "I couldn't find a strong enough match yet.",
                        canRetry = true,
                    )
                }
            }
        }
    }

    private fun showResults(ranked: List<RecommendationCandidate>) {
        val results = ranked.take(3)
        preferences = preferences.copy(
            shownKeys = preferences.shownKeys + results.map { it.media.key },
        )
        _state.value = RecommendationUiState.Results(
            preferences = preferences,
            candidates = results,
            webLimited = !webAvailable,
        )
    }

    private fun applyAnswer(
        current: RecommendationPreferences,
        question: RecommendationQuestion,
        values: List<String>,
    ): RecommendationPreferences {
        val selected = values.filter(String::isNotBlank)
        val base = current.copy(
            answeredDimensions = current.answeredDimensions + question.dimension,
        )
        if (selected.isEmpty() || selected.any { it == "any" }) {
            return RecommendationPreferenceParser.clearDimension(base, question.dimension)
        }
        val explicit = PreferenceOrigin.EXPLICIT
        val hard = ConstraintStrength.HARD
        val soft = ConstraintStrength.SOFT
        return when (question.dimension) {
            RecommendationDimension.MOOD -> base.copy(
                moods = selected.mapNotNull { value ->
                    RecommendationMood.entries.firstOrNull { it.name == value }
                }.map { PreferenceSignal(it, explicit, soft) },
            )
            RecommendationDimension.CONTENT_TYPE -> base.copy(
                contentType = selected.firstNotNullOfOrNull { value ->
                    RecommendationContentType.entries.firstOrNull { it.name == value }
                }?.let { PreferenceSignal(it, explicit, hard) },
            )
            RecommendationDimension.GENRE -> base.copy(
                includedGenres = selected.map {
                    PreferenceSignal(it, explicit, soft)
                },
            )
            RecommendationDimension.VIEWING_CONTEXT -> base.copy(
                viewingContext = selected.firstNotNullOfOrNull { value ->
                    ViewingContext.entries.firstOrNull { it.name == value }
                }?.let { PreferenceSignal(it, explicit, soft) },
            )
            RecommendationDimension.RUNTIME -> applyRuntimeAnswer(base, selected.first())
            RecommendationDimension.ERA -> applyEraAnswer(base, selected.first())
            RecommendationDimension.QUALITY -> applyQualityAnswer(base, selected.first())
            RecommendationDimension.LANGUAGE -> base.copy(
                originalLanguage = PreferenceSignal(selected.first(), explicit, hard),
            )
            RecommendationDimension.FAMILIARITY -> base.copy(
                familiarity = selected.firstNotNullOfOrNull { value ->
                    FamiliarityPreference.entries.firstOrNull { it.name == value }
                }?.let { PreferenceSignal(it, explicit, soft) },
            )
            RecommendationDimension.UNSUPPORTED_CONFIRMATION -> if (
                selected.first() == "remove"
            ) {
                base.copy(unverifiedTerms = emptyList())
            } else {
                base
            }
        }
    }

    private fun applyRuntimeAnswer(
        preferences: RecommendationPreferences,
        value: String,
    ): RecommendationPreferences {
        val hard = ConstraintStrength.HARD
        val explicit = PreferenceOrigin.EXPLICIT
        return when {
            value.startsWith("max:") -> preferences.copy(
                runtimeMinimumMinutes = null,
                runtimeMaximumMinutes = value.substringAfter(':').toIntOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
            )
            value.startsWith("min:") -> preferences.copy(
                runtimeMinimumMinutes = value.substringAfter(':').toIntOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
                runtimeMaximumMinutes = null,
            )
            value.startsWith("range:") -> {
                val parts = value.split(':')
                preferences.copy(
                    runtimeMinimumMinutes = parts.getOrNull(1)?.toIntOrNull()?.let {
                        PreferenceSignal(it, explicit, hard)
                    },
                    runtimeMaximumMinutes = parts.getOrNull(2)?.toIntOrNull()?.let {
                        PreferenceSignal(it, explicit, hard)
                    },
                )
            }
            else -> preferences
        }
    }

    private fun applyEraAnswer(
        preferences: RecommendationPreferences,
        value: String,
    ): RecommendationPreferences {
        val hard = ConstraintStrength.HARD
        val explicit = PreferenceOrigin.EXPLICIT
        return when {
            value.startsWith("min:") -> preferences.copy(
                yearMinimum = value.substringAfter(':').toIntOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
                yearMaximum = null,
            )
            value.startsWith("max:") -> preferences.copy(
                yearMinimum = null,
                yearMaximum = value.substringAfter(':').toIntOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
            )
            value.startsWith("range:") -> {
                val parts = value.split(':')
                preferences.copy(
                    yearMinimum = parts.getOrNull(1)?.toIntOrNull()?.let {
                        PreferenceSignal(it, explicit, hard)
                    },
                    yearMaximum = parts.getOrNull(2)?.toIntOrNull()?.let {
                        PreferenceSignal(it, explicit, hard)
                    },
                )
            }
            else -> preferences
        }
    }

    private fun applyQualityAnswer(
        preferences: RecommendationPreferences,
        value: String,
    ): RecommendationPreferences {
        val hard = ConstraintStrength.HARD
        val explicit = PreferenceOrigin.EXPLICIT
        return when {
            value.startsWith("imdb:") -> preferences.copy(
                minimumImdb = value.substringAfter(':').toDoubleOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
            )
            value.startsWith("rt:") -> preferences.copy(
                minimumRottenTomatoes = value.substringAfter(':').toIntOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
            )
            else -> preferences
        }
    }

    private fun applyRelativeRuntime(
        current: RecommendationPreferences,
        anchor: RecommendationCandidate?,
    ): RecommendationPreferences {
        val relative = current.relativeRuntime?.value ?: return current
        val anchorRuntime = if (anchor?.media?.type == MediaType.TV) {
            anchor.metadata.averageEpisodeRuntimeMinutes
        } else {
            anchor?.metadata?.runtimeMinutes
        } ?: return current
        val signal = PreferenceSignal(
            value = anchorRuntime,
            origin = PreferenceOrigin.EXPLICIT,
            strength = ConstraintStrength.HARD,
        )
        return when (relative) {
            RelativeRuntimePreference.SHORTER_THAN_ANCHOR -> current.copy(
                runtimeMaximumMinutes = signal.copy(value = anchorRuntime - 1),
            )
            RelativeRuntimePreference.LONGER_THAN_ANCHOR -> current.copy(
                runtimeMinimumMinutes = signal.copy(value = anchorRuntime + 1),
            )
        }
    }

    private companion object {
        const val MIN_POOL_BEFORE_REFRESH = 12
    }
}
