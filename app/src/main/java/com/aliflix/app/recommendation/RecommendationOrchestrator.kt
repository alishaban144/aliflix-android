package com.aliflix.app.recommendation

import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.CatalogSource
import com.aliflix.app.data.CatalogSourceException
import com.aliflix.app.data.CatalogVerifiedMetadata
import com.aliflix.app.data.RecommendationDiscoveryItem
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.yield

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
        RecommendationUiState.SelectType(),
    )
    val state: StateFlow<RecommendationUiState> = _state.asStateFlow()

    private var preferences = RecommendationPreferences()
    private val candidatePool = linkedMapOf<String, RecommendationCandidate>()
    private val displayed = mutableListOf<RecommendationCandidate>()
    private var similarityAnchor: RecommendationCandidate? = null
    private var cursor = RecommendationPageCursor()
    private var hasMore = true
    private var sourceHealth = RecommendationSourceHealth()
    private var activeFingerprint: String? = null
    private var job: Job? = null
    private var preparationJob: Job? = null
    private var preparationFingerprint: String? = null
    private var preparationSeedReady: CompletableDeferred<Unit>? = null
    private var attemptGeneration = 0L
    private var retryGenerationInFlight: Long? = null
    private val history = mutableListOf<RecommendationPreferences>()

    /**
     * A local-only preflight. This method never invokes the repository.
     */
    fun selectType(type: RecommendationMediaKind) {
        job?.cancel()
        history += preferences
        preferences = preferences.copy(
            contentType = PreferenceSignal(
                value = type.contentType,
                origin = PreferenceOrigin.EXPLICIT,
                strength = ConstraintStrength.HARD,
            ),
            answeredDimensions =
                preferences.answeredDimensions + RecommendationDimension.CONTENT_TYPE,
        )
        resetPaging()
        _state.value = RecommendationUiState.SelectType(preferences)
    }

    fun submitText(text: String) {
        if (text.isBlank()) return
        val selectedType = preferences.contentType
        if (selectedType == null ||
            selectedType.value == RecommendationContentType.EITHER
        ) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        val previous = preferences
        val parsed = RecommendationPreferenceParser.parse(text, preferences)
        history += previous
        // The explicit preflight owns media type. Free text must not silently
        // switch catalogues and start a different network request.
        preferences = parsed.preferences.copy(
            contentType = selectedType,
            answeredDimensions =
                parsed.preferences.answeredDimensions + RecommendationDimension.CONTENT_TYPE,
        )
        resetPagingIfNeeded()
        parsed.confirmation?.let { confirmation ->
            preferences = preferences.copy(
                askedQuestionIds = preferences.askedQuestionIds + confirmation.id,
            )
            _state.value = RecommendationUiState.Question(
                preferences = preferences,
                question = confirmation,
                progressMessage = "Confirm this preference to continue.",
                canGoBack = history.isNotEmpty(),
            )
            return
        }
        prepareAndAsk()
    }

    fun surpriseMe() {
        if (preferences.contentType == null) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        history += preferences
        preferences = preferences.copy(surpriseMe = true)
        resetPagingIfNeeded()
        prepareAndAsk()
    }

    fun answer(question: RecommendationQuestion, selectedValues: List<String>) {
        history += preferences
        preferences = applyAnswer(preferences, question, selectedValues)
        resetPagingIfNeeded()
        if (candidatePool.isEmpty()) {
            prepareAndAsk()
        } else {
            decideNextStep()
        }
    }

    fun showMatches() {
        if (preferences.contentType == null) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        if (retryGenerationInFlight != null) return
        loadForResults(targetAdditionalItems = RESULT_PAGE_SIZE)
    }

    fun loadMore() {
        val current = _state.value as? RecommendationUiState.Results ?: return
        if (
            retryGenerationInFlight != null ||
            current.refreshing ||
            current.loadingMore ||
            !current.hasMore
        ) {
            return
        }
        loadForResults(targetAdditionalItems = RESULT_PAGE_SIZE)
    }

    fun retryPage() {
        if (retryGenerationInFlight != null) return

        job?.cancel()
        job = null
        preparationJob?.cancel()
        preparationJob = null
        preparationFingerprint = null
        preparationSeedReady = null
        attemptGeneration += 1
        retryGenerationInFlight = attemptGeneration
        // A failed page never exhausts the cursor. Re-open it explicitly so
        // retry also repairs sessions produced by the old terminal-failure
        // behavior without discarding already displayed titles.
        hasMore = true
        sourceHealth = RecommendationSourceHealth()

        when (val current = _state.value) {
            is RecommendationUiState.Results -> _state.value = current.copy(
                refreshing = true,
                loadingMore = false,
                hasMore = true,
                pageError = null,
                sourceHealth = sourceHealth,
            )
            else -> _state.value = RecommendationUiState.Discovering(
                preferences = preferences,
                message = "Finding matches…",
            )
        }
        loadForResults(RESULT_PAGE_SIZE, forceNetwork = true)
    }

    fun goBack() {
        val previous = history.removeLastOrNull() ?: return
        preferences = previous
        resetPagingIfNeeded()
        if (candidatePool.isEmpty()) {
            _state.value = if (preferences.contentType == null) {
                RecommendationUiState.SelectType(preferences)
            } else {
                RecommendationUiState.SelectType(preferences)
            }
        } else {
            decideNextStep()
        }
    }

    fun restart() {
        job?.cancel()
        preferences = RecommendationPreferences()
        history.clear()
        similarityAnchor = null
        resetPaging()
        _state.value = RecommendationUiState.SelectType()
    }

    fun retry() = retryPage()

    fun requestAnother(rejected: Media, reason: String? = null) {
        store.recordRejected(rejected, reason)
        if (reason.equals("I've already seen it", ignoreCase = true)) {
            store.markSeen(rejected)
        }
        preferences = preferences.copy(
            rejectedKeys = preferences.rejectedKeys + rejected.key,
        )
        candidatePool.remove(rejected.key)
        displayed.removeAll { it.media.key == rejected.key }
        loadForResults(1)
    }

    fun accept(media: Media) {
        store.recordAccepted(media)
    }

    fun applyRelaxation(id: String) {
        history += preferences
        preferences = RecommendationRanker.applyRelaxation(preferences, id)
        resetPagingIfNeeded(force = true)
        loadForResults(RESULT_PAGE_SIZE)
    }

    fun resetTaste() {
        store.resetTaste()
        publishResults()
    }

    private fun prepareAndAsk() {
        job?.cancel()
        val spec = CatalogDiscoverySpec.from(preferences)
        if (spec == null) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        if (publishNextQuestion()) {
            startCataloguePreparation(spec)
        } else {
            showMatches()
        }
    }

    private fun decideNextStep() {
        if (!publishNextQuestion()) showMatches()
    }

    private fun publishNextQuestion(): Boolean {
        val eligible = eligibleCandidates()
        val question = RecommendationQuestionSelector.nextQuestion(preferences, eligible)
        if (question != null) {
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
            return true
        }
        return false
    }

    private fun startCataloguePreparation(spec: CatalogDiscoverySpec) {
        if (
            preparationFingerprint == spec.fingerprint &&
            (preparationJob?.isActive == true || preparationJob?.isCompleted == true)
        ) {
            return
        }
        preparationJob?.cancel()
        preparationFingerprint = spec.fingerprint
        val generation = attemptGeneration
        val seedReady = CompletableDeferred<Unit>()
        preparationSeedReady = seedReady
        preparationJob = scope.launch {
            try {
                ensureSimilarityAnchor(generation)
                if (!isActiveAttempt(generation)) return@launch
                val effectiveSpec = CatalogDiscoverySpec.from(preferences)
                    ?: return@launch
                preparationFingerprint = effectiveSpec.fingerprint
                val seeds = repository.seedCandidates(
                    spec = effectiveSpec,
                    requiredFields = RequiredMetadataFields.from(preferences),
                )
                if (!isActiveAttempt(generation)) return@launch
                seeds.forEach { candidate ->
                    candidatePool.putIfAbsent(candidate.media.key, candidate)
                }
                seedReady.complete(Unit)
                fetchOnePage(effectiveSpec, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Speculative preparation must not replace a useful question
                // with an error. The foreground request retries and reports it.
            } finally {
                seedReady.complete(Unit)
            }
        }
    }

    private fun loadForResults(
        targetAdditionalItems: Int,
        forceNetwork: Boolean = false,
    ) {
        job?.cancel()
        val generation = attemptGeneration
        val spec = CatalogDiscoverySpec.from(preferences)
        if (spec == null) {
            completeRetry(generation)
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        if (
            preparationFingerprint != spec.fingerprint ||
            preparationJob == null
        ) {
            startCataloguePreparation(spec)
        }
        val beforeCount = displayed.size
        val existingEligible = eligibleCandidates()
        appendRanked(existingEligible, targetAdditionalItems)
        if (displayed.size > beforeCount) {
            publishResults(
                refreshing = displayed.size - beforeCount < targetAdditionalItems && hasMore,
                loadingMore = beforeCount > 0 && hasMore,
            )
        } else if (beforeCount == 0) {
            _state.value = RecommendationUiState.Discovering(
                preferences = preferences,
                message = "Finding matches…",
            )
        }
        if (
            !forceNetwork &&
            (displayed.size - beforeCount >= targetAdditionalItems || !hasMore)
        ) {
            finishResultLoad()
            completeRetry(generation)
            return
        }

        job = scope.launch {
            var scanned = 0
            try {
                val preparedJob = preparationJob
                val seedReady = preparationSeedReady
                seedReady?.await()
                if (!isActiveAttempt(generation)) return@launch
                appendRanked(
                    eligibleCandidates(),
                    targetAdditionalItems - (displayed.size - beforeCount),
                )
                if (displayed.isNotEmpty()) {
                    publishResults(
                        refreshing = true,
                        loadingMore = beforeCount > 0,
                    )
                }
                preparedJob?.join()
                if (!isActiveAttempt(generation)) return@launch
                ensureSimilarityAnchor(generation)
                if (!isActiveAttempt(generation)) return@launch
                val activeSpec = CatalogDiscoverySpec.from(preferences) ?: spec
                if (activeFingerprint != activeSpec.fingerprint) return@launch
                appendRanked(
                    eligibleCandidates(),
                    targetAdditionalItems - (displayed.size - beforeCount),
                )
                if (displayed.isNotEmpty()) {
                    publishResults(
                        refreshing =
                            displayed.size - beforeCount < targetAdditionalItems &&
                                hasMore,
                        loadingMore = beforeCount > 0 && hasMore,
                    )
                }
                while (
                    displayed.size - beforeCount < targetAdditionalItems &&
                    hasMore
                ) {
                    if (beforeCount > 0 && scanned >= MAX_PAGES_PER_ACTION) break
                    if (!fetchOnePage(activeSpec, generation)) return@launch
                    scanned += 1
                    appendRanked(
                        eligibleCandidates(),
                        targetAdditionalItems - (displayed.size - beforeCount),
                    )
                    if (displayed.isNotEmpty()) {
                        publishResults(
                            refreshing =
                                displayed.size - beforeCount < targetAdditionalItems &&
                                    hasMore,
                            loadingMore = beforeCount > 0 && hasMore,
                        )
                    }
                    if (
                        beforeCount == 0 &&
                        displayed.isEmpty() &&
                        hasMore &&
                        scanned % MAX_PAGES_PER_ACTION == 0
                    ) {
                        _state.value = RecommendationUiState.Discovering(
                            preferences = preferences,
                            message = "Checking more catalogue pages…",
                        )
                        yield()
                    }
                }
                finishResultLoad()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                publishFailure(error)
            } finally {
                completeRetry(generation)
            }
        }
    }

    private suspend fun fetchOnePage(
        spec: CatalogDiscoverySpec,
        generation: Long,
    ): Boolean {
        if (!hasMore || !isActiveAttempt(generation)) return false
        val requestedCursor = cursor
        val page = repository.discoverPage(
            spec = spec,
            cursor = requestedCursor,
            requiredFields = RequiredMetadataFields.from(preferences),
        )
        // A cancelled/stale request cannot mutate the active session.
        if (
            !isActiveAttempt(generation) ||
            activeFingerprint != spec.fingerprint
        ) {
            return false
        }
        page.sourceHealth.requiredFailureOrNull()?.let { throw it }
        page.candidates.forEach { candidate ->
            candidatePool.putIfAbsent(candidate.media.key, candidate)
        }
        // Health represents the latest completed request. A successful page
        // therefore recovers a source that was unavailable on an earlier try.
        sourceHealth = page.sourceHealth
        cursor = page.nextCursor ?: cursor.copy(
            page = cursor.page + 1,
            seenKeys = cursor.seenKeys + page.candidates.map { it.media.key },
        )
        hasMore = page.hasMore && page.nextCursor != null
        return true
    }

    private suspend fun ensureSimilarityAnchor(generation: Long) {
        val title = preferences.similarityTitle?.value ?: return
        if (similarityAnchor?.media?.title.equals(title, ignoreCase = true)) return
        val resolved = repository.resolveSimilarityAnchor(title)
        if (!isActiveAttempt(generation)) return
        similarityAnchor = resolved
        if (preferences.relativeRuntime != null && similarityAnchor == null) {
            throw IllegalStateException(
                "The comparison title's runtime could not be verified.",
            )
        }
        preferences = applyRelativeRuntime(preferences, similarityAnchor)
        val fingerprint = CatalogDiscoverySpec.from(preferences)?.fingerprint
        if (fingerprint != activeFingerprint) {
            // This runs inside the preparation/action coroutine, so preserve
            // the current job while resetting its now-more-specific cursor.
            resetPaging(
                cancelPreparation = false,
                invalidateAttempt = false,
            )
            activeFingerprint = fingerprint
        }
    }

    private fun eligibleCandidates(): List<RecommendationCandidate> =
        RecommendationRanker.hardFilter(
            preferences = preferences,
            candidates = candidatePool.values.toList(),
            recentlyPlayedKeys = recentlyPlayedProvider().map(Media::key).toSet(),
            seenKeys = store.taste.value.seenKeys,
        )

    private fun appendRanked(
        candidates: List<RecommendationCandidate>,
        limit: Int,
    ) {
        if (limit <= 0) return
        val displayedKeys = displayed.mapTo(hashSetOf()) { it.media.key }
        RecommendationRanker.rankAll(
            preferences = preferences,
            candidates = candidates.filterNot { it.media.key in displayedKeys },
            likes = likesProvider(),
            taste = store.taste.value,
            similarityAnchor = similarityAnchor?.media,
        ).take(limit).forEach(displayed::add)
    }

    private fun finishResultLoad() {
        if (displayed.isNotEmpty()) {
            publishResults()
            return
        }
        val relaxations = if (hasMore) {
            emptyList()
        } else {
            RecommendationRanker.relaxationOptions(
                preferences,
                candidatePool.values.toList(),
            )
        }
        _state.value = if (sourceHealth.requiredSourceUnavailable) {
            RecommendationUiState.SourceUnavailable(
                preferences = preferences,
                message = if (
                    sourceHealth.imdb == RecommendationSourceStatus.UNAVAILABLE
                ) {
                    "IMDb is temporarily unavailable. Try again."
                } else {
                    "The catalogue is temporarily unavailable. Try again."
                },
            )
        } else {
            RecommendationUiState.Empty(
                preferences = preferences,
                message = "No titles matched every selected requirement.",
                options = relaxations,
            )
        }
    }

    private fun publishResults(
        refreshing: Boolean = false,
        loadingMore: Boolean = false,
        pageError: String? = null,
    ) {
        if (displayed.isEmpty()) return
        val undisplayedEligible = eligibleCandidates()
            .any { candidate -> displayed.none { it.media.key == candidate.media.key } }
        _state.value = RecommendationUiState.Results(
            preferences = preferences,
            candidates = displayed.toList(),
            refreshing = refreshing,
            loadingMore = loadingMore,
            hasMore = hasMore || undisplayedEligible,
            pageError = pageError,
            sourceHealth = sourceHealth,
            webLimited = sourceHealth.web == RecommendationSourceStatus.UNAVAILABLE,
        )
    }

    private fun publishFailure(error: Throwable) {
        if (error !is CatalogSourceException) {
            _state.value = RecommendationUiState.Error(
                preferences = preferences,
                message = error.message ?: "Something went wrong while loading recommendations.",
                canRetry = true,
            )
            return
        }
        sourceHealth = when (error.source) {
            CatalogSource.TMDB -> sourceHealth.copy(
                catalogue = RecommendationSourceStatus.UNAVAILABLE,
            )
            CatalogSource.IMDB -> sourceHealth.copy(
                imdb = RecommendationSourceStatus.UNAVAILABLE,
            )
        }
        if (displayed.isNotEmpty()) {
            publishResults(pageError = sourceFailureMessage(error.source))
        } else {
            sourceFailure(error)
        }
    }

    private fun sourceFailure(error: CatalogSourceException) {
        _state.value = RecommendationUiState.SourceUnavailable(
            preferences = preferences,
            message = sourceFailureMessage(error.source),
        )
    }

    private fun sourceFailureMessage(source: CatalogSource): String =
        if (source == CatalogSource.IMDB) {
            "IMDb is temporarily unavailable. Try again."
        } else {
            "The catalogue is temporarily unavailable. Try again."
        }

    private fun RecommendationSourceHealth.requiredFailureOrNull():
        CatalogSourceException? = when {
        imdb == RecommendationSourceStatus.UNAVAILABLE ->
            CatalogSourceException(
                source = CatalogSource.IMDB,
                message = "IMDb is temporarily unavailable.",
            )
        catalogue == RecommendationSourceStatus.UNAVAILABLE ->
            CatalogSourceException(
                source = CatalogSource.TMDB,
                message = "The catalogue is temporarily unavailable.",
            )
        else -> null
    }

    private fun isActiveAttempt(generation: Long): Boolean =
        generation == attemptGeneration

    private fun completeRetry(generation: Long) {
        if (retryGenerationInFlight == generation) {
            retryGenerationInFlight = null
        }
    }

    private fun resetPagingIfNeeded(force: Boolean = false) {
        val fingerprint = CatalogDiscoverySpec.from(preferences)?.fingerprint
        if (force || fingerprint != activeFingerprint) {
            resetPaging()
            activeFingerprint = fingerprint
        }
    }

    private fun resetPaging(
        cancelPreparation: Boolean = true,
        invalidateAttempt: Boolean = true,
    ) {
        if (invalidateAttempt) {
            attemptGeneration += 1
            retryGenerationInFlight = null
            job?.cancel()
            job = null
        }
        if (cancelPreparation) {
            preparationJob?.cancel()
            preparationJob = null
            preparationFingerprint = null
            preparationSeedReady = null
        }
        candidatePool.clear()
        displayed.clear()
        cursor = RecommendationPageCursor()
        hasMore = true
        sourceHealth = RecommendationSourceHealth()
        activeFingerprint = CatalogDiscoverySpec.from(preferences)?.fingerprint
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
            RecommendationDimension.CONTENT_TYPE -> base
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
        current: RecommendationPreferences,
        value: String,
    ): RecommendationPreferences {
        val signal: (Int) -> PreferenceSignal<Int> = {
            PreferenceSignal(it, PreferenceOrigin.EXPLICIT, ConstraintStrength.HARD)
        }
        return when {
            value.startsWith("max:") -> current.copy(
                runtimeMinimumMinutes = null,
                runtimeMaximumMinutes = value.substringAfter(':').toIntOrNull()?.let(signal),
            )
            value.startsWith("min:") -> current.copy(
                runtimeMinimumMinutes = value.substringAfter(':').toIntOrNull()?.let(signal),
                runtimeMaximumMinutes = null,
            )
            value.startsWith("range:") -> value.split(':').let { parts ->
                current.copy(
                    runtimeMinimumMinutes = parts.getOrNull(1)?.toIntOrNull()?.let(signal),
                    runtimeMaximumMinutes = parts.getOrNull(2)?.toIntOrNull()?.let(signal),
                )
            }
            else -> current
        }
    }

    private fun applyEraAnswer(
        current: RecommendationPreferences,
        value: String,
    ): RecommendationPreferences {
        val signal: (Int) -> PreferenceSignal<Int> = {
            PreferenceSignal(it, PreferenceOrigin.EXPLICIT, ConstraintStrength.HARD)
        }
        return when {
            value.startsWith("min:") -> current.copy(
                yearMinimum = value.substringAfter(':').toIntOrNull()?.let(signal),
                yearMaximum = null,
            )
            value.startsWith("max:") -> current.copy(
                yearMinimum = null,
                yearMaximum = value.substringAfter(':').toIntOrNull()?.let(signal),
            )
            value.startsWith("range:") -> value.split(':').let { parts ->
                current.copy(
                    yearMinimum = parts.getOrNull(1)?.toIntOrNull()?.let(signal),
                    yearMaximum = parts.getOrNull(2)?.toIntOrNull()?.let(signal),
                )
            }
            else -> current
        }
    }

    private fun applyQualityAnswer(
        current: RecommendationPreferences,
        value: String,
    ): RecommendationPreferences {
        val explicit = PreferenceOrigin.EXPLICIT
        val hard = ConstraintStrength.HARD
        return when {
            value.startsWith("imdb:") -> current.copy(
                minimumRottenTomatoes = null,
                minimumTmdb = null,
                minimumImdb = value.substringAfter(':').toDoubleOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
            )
            value.startsWith("rt:") -> current.copy(
                minimumImdb = null,
                minimumTmdb = null,
                minimumRottenTomatoes = value.substringAfter(':').toIntOrNull()?.let {
                    PreferenceSignal(it, explicit, hard)
                },
            )
            else -> current
        }
    }

    private fun applyRelativeRuntime(
        current: RecommendationPreferences,
        anchor: RecommendationCandidate?,
    ): RecommendationPreferences {
        val relative = current.relativeRuntime?.value ?: return current
        val runtime = if (anchor?.media?.type == MediaType.TV) {
            anchor.metadata.averageEpisodeRuntimeMinutes
        } else {
            anchor?.metadata?.runtimeMinutes
        } ?: return current
        val signal = PreferenceSignal(
            value = runtime,
            origin = PreferenceOrigin.EXPLICIT,
            strength = ConstraintStrength.HARD,
        )
        return when (relative) {
            RelativeRuntimePreference.SHORTER_THAN_ANCHOR -> current.copy(
                runtimeMaximumMinutes = signal.copy(value = runtime - 1),
            )
            RelativeRuntimePreference.LONGER_THAN_ANCHOR -> current.copy(
                runtimeMinimumMinutes = signal.copy(value = runtime + 1),
            )
        }
    }

    private companion object {
        const val RESULT_PAGE_SIZE = 20
        const val MAX_PAGES_PER_ACTION = 6
    }
}
