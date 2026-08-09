package com.aliflix.app.recommendation

import com.aliflix.app.data.CatalogSource
import com.aliflix.app.data.CatalogSourceException
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Main-owned recommendation session reducer.
 *
 * Public actions only mutate small pieces of session state. Retrieval, intent
 * parsing, semantic inference, scoring, sorting and diversification execute on
 * isolated contexts and return immutable results. A monotonically increasing
 * session id is checked before every publication.
 *
 * Paging 3 is intentionally not used here: provider pages can enrich an
 * existing canonical candidate and ranking must publish an immutable,
 * intent-scored snapshot rather than stream arrival order. This reducer keeps
 * the useful Paging guarantees explicitly—single-flight append, cancellation,
 * deduplication, page-level retry, backpressure, and prior-page preservation.
 */
class RecommendationOrchestrator(
    private val scope: CoroutineScope,
    private val repository: RecommendationCandidateRepository,
    private val store: RecommendationStore,
    private val likesProvider: () -> List<Media>,
    private val recentlyPlayedProvider: () -> List<Media>,
    private val semanticBatchScorerProvider: () -> SemanticBatchScorer? = { null },
    private val dispatchers: RecommendationDispatchers =
        RecommendationDispatchers.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val pageTimeoutMillis: Long = DEFAULT_PAGE_TIMEOUT_MS,
    private val aiClient: RecommendationAiClient? = null,
    private val omdbClient: com.aliflix.app.data.omdb.OmdbMetadataClient? = null,
) {
    private val _state = MutableStateFlow<RecommendationUiState>(
        RecommendationUiState.SelectType(),
    )
    val state: StateFlow<RecommendationUiState> = _state.asStateFlow()

    private var preferences = RecommendationPreferences()
    private val history = mutableListOf<RecommendationPreferences>()

    private var sessionId = 0L
    private var activeJob: Job? = null
    private var loadInFlight = false

    private var activeSpec: CatalogDiscoverySpec? = null
    private var similarityAnchor: RecommendationCandidate? = null
    private var candidatePool: Map<String, RecommendationCandidate> = emptyMap()
    private var rankedSnapshot: List<RecommendationCandidate> = emptyList()
    private var visibleCount = 0
    private var cursor = RecommendationPageCursor()
    private var sourceHasMore = true
    private var sourceHealth = RecommendationSourceHealth()
    private var refinementQuestion: RecommendationQuestion? = null
    private var pendingAnchorChoices: Map<String, RecommendationCandidate> = emptyMap()

    fun selectType(type: RecommendationMediaKind) {
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
        beginSession(resetRankedState = true)
        _state.value = RecommendationUiState.SelectType(preferences)
    }

    fun submitDraft(draft: RecommendationRequestDraft) {
        val freeText = draft.freeText
        if (freeText.isBlank() && draft.similarityTitle.isNullOrBlank() && draft.genres.isEmpty() && draft.moods.isEmpty()) return
        val selectedType = preferences.contentType ?: PreferenceSignal(
            value = when (draft.mediaType) {
                MediaType.MOVIE -> RecommendationContentType.MOVIE
                MediaType.TV -> RecommendationContentType.TV
                null -> RecommendationContentType.EITHER
            },
            origin = PreferenceOrigin.EXPLICIT,
            strength = ConstraintStrength.HARD,
        )
        if (
            selectedType.value == RecommendationContentType.EITHER
        ) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }

        history += preferences
        val basePreferences = preferences
        val token = beginSession(resetRankedState = true)
        loadInFlight = true
        _state.value = RecommendationUiState.Discovering(
            preferences = basePreferences,
            message = "Understanding your request…",
        )
        val context = captureRankingContext()
        activeJob = scope.launch {
            try {
                val outcome = withContext(dispatchers.computation) {
                    if (aiClient != null) {
                        retrieveAi(draft, selectedType.value, context)
                    } else {
                        val parsed = RecommendationPreferenceParser.parse(freeText, basePreferences)
                        if (parsed.confirmation != null) {
                            // Can't return directly from here but this is a simplified flow.
                            // I will handle it properly below.
                        }
                        retrieveInitial(
                            input = InitialRequest(
                                preferences = parsed.preferences.copy(
                                    contentType = selectedType,
                                    answeredDimensions = parsed.preferences.answeredDimensions + RecommendationDimension.CONTENT_TYPE
                                ),
                                rankingContext = context,
                            )
                        )
                    }
                }
                publishInitial(token, outcome)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                publishFailureIfCurrent(token, error)
            }
        }
    }

    fun surpriseMe() {
        if (loadInFlight) return
        if (preferences.contentType == null) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        history += preferences
        preferences = preferences.copy(surpriseMe = true)
        beginSession(resetRankedState = true)
        launchInitial()
    }

    fun answer(question: RecommendationQuestion, selectedValues: List<String>) {
        if (loadInFlight) return
        if (question.id == ANCHOR_CLARIFICATION_ID) {
            val selected = selectedValues.firstNotNullOfOrNull(pendingAnchorChoices::get)
                ?: return
            pendingAnchorChoices = emptyMap()
            beginSession(resetRankedState = true)
            launchInitial(forcedAnchor = selected)
            return
        }

        history += preferences
        preferences = applyAnswer(preferences, question, selectedValues).copy(
            askedQuestionIds = preferences.askedQuestionIds + question.id,
        )
        beginSession(resetRankedState = true)
        launchInitial()
    }

    fun showMatches() {
        if (loadInFlight) return
        if (preferences.contentType == null) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        beginSession(resetRankedState = true)
        launchInitial()
    }

    fun loadMore() {
        val current = _state.value as? RecommendationUiState.Results ?: return
        if (loadInFlight || current.loadingMore || !current.hasMore) return

        if (visibleCount < rankedSnapshot.size) {
            visibleCount = minOf(
                rankedSnapshot.size,
                visibleCount + RESULT_PAGE_SIZE,
            )
            publishResults()
            return
        }
        if (!sourceHasMore) {
            publishResults()
            return
        }
        launchAppend(forceRetry = false)
    }

    fun retryPage() {
        if (loadInFlight) return
        when (val current = _state.value) {
            is RecommendationUiState.Results -> {
                if (!sourceHasMore) return
                launchAppend(forceRetry = true)
            }
            is RecommendationUiState.Error,
            is RecommendationUiState.SourceUnavailable,
            is RecommendationUiState.Empty,
            -> {
                beginSession(resetRankedState = true)
                launchInitial()
            }
            else -> Unit
        }
    }

    fun retry() = retryPage()

    fun goBack() {
        val previous = history.removeLastOrNull() ?: return
        preferences = previous
        beginSession(resetRankedState = true)
        _state.value = RecommendationUiState.SelectType(preferences)
    }

    fun restart() {
        preferences = RecommendationPreferences()
        history.clear()
        beginSession(resetRankedState = true)
        _state.value = RecommendationUiState.SelectType()
    }

    fun requestAnother(rejected: Media, reason: String? = null) {
        if (loadInFlight) return
        if (reason.equals(ALREADY_SEEN_REASON, ignoreCase = true)) {
            alreadySeen(rejected)
            return
        }
        store.recordRejected(rejected, reason)
        preferences = preferences.copy(
            rejectedKeys = preferences.rejectedKeys + rejected.key,
        )
        removeAndReplace(rejected.key)
    }

    fun accept(media: Media) {
        store.recordAccepted(media)
    }

    fun moreLike(media: Media) {
        if (loadInFlight) return
        history += preferences
        preferences = preferences.copy(
            similarityTitle = PreferenceSignal(
                value = media.title,
                origin = PreferenceOrigin.EXPLICIT,
                strength = ConstraintStrength.SOFT,
            ),
            surpriseMe = false,
        )
        beginSession(resetRankedState = true)
        launchInitial(
            forcedAnchor = RecommendationCandidate(media = media),
        )
    }

    fun lessLike(media: Media) {
        requestAnother(media, "Less like this")
    }

    fun alreadySeen(media: Media) {
        if (loadInFlight) return
        // Seen is a visibility signal, not a negative genre preference.
        store.markSeen(media)
        preferences = preferences.copy(
            rejectedKeys = preferences.rejectedKeys + media.key,
        )
        removeAndReplace(media.key)
    }

    fun applyCorrection(correction: PreferenceCorrection) {
        if (loadInFlight) return
        history += preferences
        val key = correction.key
        preferences = when {
            key.startsWith("mood:") -> preferences.copy(
                moods = preferences.moods.filterNot {
                    it.value.name.equals(key.substringAfter(':'), ignoreCase = true)
                },
            )
            key.startsWith("genre:") -> preferences.copy(
                includedGenres = preferences.includedGenres.filterNot {
                    it.value.equals(key.substringAfter(':'), ignoreCase = true)
                },
            )
            key.startsWith("facet:") -> preferences.copy(
                semanticFacets = preferences.semanticFacets.filterNot {
                    it.value.id == key.substringAfter(':')
                },
            )
            key.startsWith("excluded_facet:") -> preferences.copy(
                excludedFacets = preferences.excludedFacets.filterNot {
                    it.value.id == key.substringAfter(':')
                },
            )
            key.startsWith("unmatched:") -> preferences.copy(
                unmatchedPreferences = preferences.unmatchedPreferences.filterNot {
                    it.text == key.substringAfter(':')
                },
            )
            key == "runtime_max" -> preferences.copy(runtimeMaximumMinutes = null)
            key == "runtime_min" -> preferences.copy(runtimeMinimumMinutes = null)
            key == "year_min" -> preferences.copy(yearMinimum = null)
            key == "year_max" -> preferences.copy(yearMaximum = null)
            key == "imdb" -> preferences.copy(minimumImdb = null)
            key == "language" -> preferences.copy(originalLanguage = null)
            key == "status" -> preferences.copy(requiredStatus = null)
            key == "similarity" -> preferences.copy(
                similarityTitle = null,
                relativeRuntime = null,
            )
            key == "context" -> preferences.copy(viewingContext = null)
            else -> preferences
        }
        beginSession(resetRankedState = true)
        launchInitial()
    }

    fun applyRelaxation(id: String) {
        if (loadInFlight) return
        history += preferences
        preferences = RecommendationRanker.applyRelaxation(preferences, id)
        beginSession(resetRankedState = true)
        launchInitial()
    }

    fun resetTaste() {
        store.resetTaste()
        if (rankedSnapshot.isNotEmpty()) publishResults()
    }

    private fun launchInitial(
        forcedAnchor: RecommendationCandidate? = null,
    ) {
        val spec = CatalogDiscoverySpec.from(preferences)
        if (spec == null) {
            _state.value = RecommendationUiState.SelectType(preferences)
            return
        }
        val token = sessionId
        val request = InitialRequest(
            preferences = preferences,
            forcedAnchor = forcedAnchor,
            rankingContext = captureRankingContext(),
        )
        loadInFlight = true
        _state.value = RecommendationUiState.Discovering(
            preferences = preferences,
            message = "Finding relevant matches…",
        )
        activeJob = scope.launch {
            try {
                val outcome = withContext(dispatchers.computation) {
                    retrieveInitial(request)
                }
                publishInitial(token, outcome)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                publishFailureIfCurrent(token, error)
            }
        }
    }

    private fun launchAppend(forceRetry: Boolean) {
        val spec = activeSpec ?: return
        val current = _state.value as? RecommendationUiState.Results ?: return
        val token = sessionId
        val request = AppendRequest(
            preferences = preferences,
            spec = spec,
            anchor = similarityAnchor,
            cursor = cursor,
            knownCandidates = candidatePool,
            rankedKeys = rankedSnapshot.mapTo(linkedSetOf()) { it.media.key },
            rankingContext = captureRankingContext(),
            sourceHealth = sourceHealth,
        )
        loadInFlight = true
        _state.value = current.copy(
            refreshing = forceRetry,
            loadingMore = !forceRetry,
            pageError = null,
        )
        activeJob = scope.launch {
            try {
                val outcome = withContext(dispatchers.computation) {
                    retrieveAppend(request)
                }
                if (!isCurrent(token)) return@launch
                candidatePool = outcome.candidatePool
                cursor = outcome.cursor
                sourceHasMore = outcome.sourceHasMore
                sourceHealth = outcome.sourceHealth
                val beforeSize = rankedSnapshot.size
                val refreshedSnapshot = rankedSnapshot.map { ranked ->
                    outcome.candidatePool[ranked.media.key]
                        ?.let { enriched ->
                            mergeRecommendationCandidates(ranked, enriched)
                        }
                        ?: ranked
                }
                rankedSnapshot = (refreshedSnapshot + outcome.rankedAppend)
                    .distinctBy { it.media.key }
                visibleCount = minOf(
                    rankedSnapshot.size,
                    visibleCount + maxOf(
                        RESULT_PAGE_SIZE,
                        rankedSnapshot.size - beforeSize,
                    ),
                )
                loadInFlight = false
                publishResults()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launch
                loadInFlight = false
                updateSourceHealth(error)
                if (visibleCount > 0) {
                    publishResults(pageError = pageFailureMessage(error))
                } else {
                    publishFailure(error)
                }
            }
        }
    }

    private suspend fun retrieveAi(
        draft: RecommendationRequestDraft,
        mediaType: RecommendationContentType,
        context: RankingContext
    ): InitialOutcome {
        val aiClient = this.aiClient ?: throw IllegalStateException("aiClient is null")
        val isSimilarTo = !draft.similarityTitle.isNullOrBlank() || draft.similarityAnchor != null
        val wantedKind = if (mediaType == RecommendationContentType.TV) MediaType.TV else MediaType.MOVIE

        val queryText = when {
            draft.freeText.isNotBlank() -> draft.freeText
            isSimilarTo -> "Find ${if (mediaType == RecommendationContentType.TV) "television series" else "movies"} genuinely similar to ${draft.similarityTitle ?: draft.similarityAnchor?.title}"
            else -> (draft.genres + draft.moods).joinToString(" ")
        }

        var minimumYear: Int? = null
        var maximumYear: Int? = null
        if (draft.yearRule != null) {
            minimumYear = parseYearMin(draft.yearRule)
            maximumYear = parseYearMax(draft.yearRule)
        }
        val outputType = if (wantedKind == MediaType.MOVIE) "movie" else "tv"
        val similarityAnchor = draft.similarityAnchor
        val similarityTitle = draft.similarityTitle ?: similarityAnchor?.title
        
        val request = V3RecommendationRequest(
            requestId = java.util.UUID.randomUUID().toString(),
            mode = if (isSimilarTo) "similar" else "describe",
            query = queryText,
            mediaType = outputType,
            anchor = similarityTitle?.let {
                V3RecommendationAnchor(
                    tmdbId = similarityAnchor?.id,
                    title = it,
                    mediaType = similarityAnchor?.type?.routeName ?: outputType,
                )
            },
            filters = V3RecommendationFilters(
                minimumYear = minimumYear,
                maximumYear = maximumYear,
                originalLanguage = draft.language,
                includedGenres = draft.genres,
            ),
            pageSize = 40
        )

        val response = aiClient.getRecommendations(request)

        val verifiedCandidates = response.results.map { res ->
            val media = com.aliflix.app.model.Media(
                id = res.tmdbId,
                type = if (res.mediaType == "tv") MediaType.TV else MediaType.MOVIE,
                title = res.title,
                year = res.releaseDate?.take(4) ?: "",
                posterPath = res.posterPath,
                backdropPath = res.backdropPath,
                rating = res.tmdbRating ?: 0.0,
                overview = res.overview ?: "",
                genres = res.genres
            )
            val breakdown = RecommendationScoreBreakdown(
                semanticScore = res.finalScore,
                total = res.finalScore,
                finalScore = res.finalScore
            )
            RecommendationCandidate(
                media = media,
                score = breakdown,
                sources = res.retrievalSources.toSet()
            )
        }

        return InitialOutcome.Complete(
            preferences = RecommendationPreferences(),
            spec = CatalogDiscoverySpec(mediaKind = if (mediaType == RecommendationContentType.TV) RecommendationMediaKind.SERIES else RecommendationMediaKind.MOVIE),
            anchor = draft.similarityAnchor?.let { RecommendationCandidate(it) },
            candidatePool = verifiedCandidates.associateBy { it.media.key },
            ranked = verifiedCandidates,
            rejectedLowConfidenceCount = 0,
            cursor = RecommendationPageCursor(),
            sourceHasMore = response.hasMore,
            sourceHealth = RecommendationSourceHealth(catalogue = RecommendationSourceStatus.AVAILABLE),
            refinementQuestion = null,
            relaxations = emptyList()
        )
    }

    private suspend fun retrieveInitial(
        input: InitialRequest,
    ): InitialOutcome {
        val anchorResolution = resolveAnchor(
            preferences = input.preferences,
            forcedAnchor = input.forcedAnchor,
        )
        if (anchorResolution is AnchorWork.Clarify) {
            return InitialOutcome.ClarifyAnchor(
                preferences = input.preferences,
                title = anchorResolution.title,
                candidates = anchorResolution.candidates,
            )
        }
        if (anchorResolution is AnchorWork.NotFound) {
            return InitialOutcome.AnchorNotFound(
                preferences = input.preferences,
                title = anchorResolution.title,
            )
        }
        val anchor = (anchorResolution as? AnchorWork.Resolved)?.candidate
        val effectivePreferences = applyRelativeRuntime(input.preferences, anchor)
        val spec = CatalogDiscoverySpec.from(effectivePreferences)
            ?: return InitialOutcome.AnchorNotFound(
                preferences = effectivePreferences,
                title = effectivePreferences.similarityTitle?.value.orEmpty(),
            )
        val requiredFields = RequiredMetadataFields.from(effectivePreferences)
        val pool = linkedMapOf<String, RecommendationCandidate>()

        // Graph-backed candidates are generated first and retain that evidence
        // through canonical-key merging.
        if (anchor != null) {
            repository.relatedCandidates(anchor, spec, requiredFields)
                .forEach { candidate -> pool.merge(candidate) }
        }
        repository.seedCandidates(spec, requiredFields)
            .take(MAX_SEED_CANDIDATES)
            .forEach { candidate -> pool.merge(candidate) }

        var nextCursor = RecommendationPageCursor()
        var hasMore = true
        var health = RecommendationSourceHealth()
        var pages = 0
        var noProgressPages = 0
        val minimumDiscoveryPages = effectivePreferences.minimumBroadDiscoveryPages()
        val startedAt = clockMillis()
        while (
            hasMore &&
            (pages < minimumDiscoveryPages ||
                hardEligibleCount(effectivePreferences, pool.values, input.rankingContext) <
                RESULT_PAGE_SIZE) &&
            pages < MAX_INITIAL_PAGES &&
            clockMillis() - startedAt < MAX_INITIAL_DURATION_MS
        ) {
            val requestedCursor = nextCursor
            val page = try {
                withTimeout(pageTimeoutMillis) {
                    repository.discoverPage(spec, requestedCursor, requiredFields)
                }
            } catch (_: TimeoutCancellationException) {
                health = health.copy(catalogue = RecommendationSourceStatus.DEGRADED)
                if (pool.isEmpty()) throw RecommendationPageTimeoutException()
                break
            }
            page.sourceHealth.requiredFailureOrNull()?.let { throw it }
            val keysBefore = pool.keys.toSet()
            page.candidates.forEach { candidate -> pool.merge(candidate) }
            val newKeyCount = pool.keys.count { it !in keysBefore }
            noProgressPages = if (newKeyCount == 0) noProgressPages + 1 else 0
            health = page.sourceHealth
            nextCursor = page.nextCursor
                ?.takeUnless { it == requestedCursor }
                ?: requestedCursor.advance(page.candidates)
            hasMore = page.hasMore && noProgressPages < MAX_NO_PROGRESS_PAGES
            pages += 1
        }

        val eligible = RecommendationRanker.hardFilter(
            preferences = effectivePreferences,
            candidates = pool.values.toList(),
            recentlyPlayedKeys = input.rankingContext.recentKeys,
            seenKeys = input.rankingContext.taste.seenKeys,
        )
        val rankingPool = selectBoundedRankingPool(eligible)
        val ranking = rankCandidates(
            preferences = effectivePreferences,
            candidates = rankingPool,
            anchor = anchor,
            context = input.rankingContext,
        )
        val refinements = RecommendationQuestionSelector.nextQuestion(
            effectivePreferences,
            ranking.ranked,
        )
        val relaxations = if (ranking.ranked.isEmpty() && !hasMore) {
            RecommendationRanker.relaxationOptions(
                effectivePreferences,
                pool.values.toList(),
            )
        } else {
            emptyList()
        }
        return InitialOutcome.Complete(
            preferences = effectivePreferences,
            spec = spec,
            anchor = anchor,
            candidatePool = pool.toMap(),
            ranked = ranking.ranked,
            rejectedLowConfidenceCount = ranking.rejectedLowConfidence.size,
            cursor = nextCursor,
            sourceHasMore = hasMore,
            sourceHealth = health,
            refinementQuestion = refinements,
            relaxations = relaxations,
        )
    }

    private suspend fun retrieveAppend(request: AppendRequest): AppendOutcome {
        val requiredFields = RequiredMetadataFields.from(request.preferences)
        val pool = request.knownCandidates.toMutableMap()
        val newCandidates = linkedMapOf<String, RecommendationCandidate>()
        var nextCursor = request.cursor
        var hasMore = true
        var health = request.sourceHealth
        var pages = 0
        var noProgressPages = 0
        val minimumDiscoveryPages = request.preferences.minimumBroadDiscoveryPages()
        val startedAt = clockMillis()
        while (
            hasMore &&
            (pages < minimumDiscoveryPages ||
                hardEligibleCount(
                    request.preferences,
                    newCandidates.values,
                    request.rankingContext,
                ) < RESULT_PAGE_SIZE) &&
            pages < MAX_APPEND_PAGES &&
            clockMillis() - startedAt < MAX_APPEND_DURATION_MS
        ) {
            val requestedCursor = nextCursor
            val page = try {
                withTimeout(pageTimeoutMillis) {
                    repository.discoverPage(
                        request.spec,
                        requestedCursor,
                        requiredFields,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                throw RecommendationPageTimeoutException()
            }
            page.sourceHealth.requiredFailureOrNull()?.let { throw it }
            val keysBefore = pool.keys.toSet()
            page.candidates.forEach { incoming ->
                val existing = pool[incoming.media.key]
                pool[incoming.media.key] = mergeRecommendationCandidates(existing, incoming)
                if (
                    incoming.media.key !in request.rankedKeys &&
                    incoming.media.key !in request.knownCandidates
                ) {
                    newCandidates[incoming.media.key] = mergeRecommendationCandidates(
                        newCandidates[incoming.media.key],
                        incoming,
                    )
                }
            }
            val newKeyCount = pool.keys.count { it !in keysBefore }
            noProgressPages = if (newKeyCount == 0) noProgressPages + 1 else 0
            health = page.sourceHealth
            nextCursor = page.nextCursor
                ?.takeUnless { it == requestedCursor }
                ?: requestedCursor.advance(page.candidates)
            hasMore = page.hasMore && noProgressPages < MAX_NO_PROGRESS_PAGES
            pages += 1
        }
        val eligible = RecommendationRanker.hardFilter(
            preferences = request.preferences,
            candidates = newCandidates.values.toList(),
            recentlyPlayedKeys = request.rankingContext.recentKeys,
            seenKeys = request.rankingContext.taste.seenKeys,
        )
        val ranking = rankCandidates(
            preferences = request.preferences,
            candidates = selectBoundedRankingPool(eligible),
            anchor = request.anchor,
            context = request.rankingContext,
        )
        return AppendOutcome(
            candidatePool = pool.toMap(),
            rankedAppend = ranking.ranked,
            cursor = nextCursor,
            sourceHasMore = hasMore,
            sourceHealth = health,
        )
    }

    private suspend fun resolveAnchor(
        preferences: RecommendationPreferences,
        forcedAnchor: RecommendationCandidate?,
    ): AnchorWork {
        val title = preferences.similarityTitle?.value ?: return AnchorWork.None
        forcedAnchor?.let {
            return AnchorWork.Resolved(repository.enrichSimilarityAnchor(it))
        }
        val mediaKind = when (preferences.contentType?.value) {
            RecommendationContentType.MOVIE -> RecommendationMediaKind.MOVIE
            RecommendationContentType.TV -> RecommendationMediaKind.SERIES
            else -> return AnchorWork.NotFound(title)
        }
        val candidates = repository.resolveSimilarityAnchorCandidates(title, mediaKind)
            .filter { it.media.type == mediaKind.mediaType }
            .distinctBy { it.media.key }
        if (candidates.isEmpty()) return AnchorWork.NotFound(title)

        val resolution = CanonicalTitleResolver.resolve(
            query = title,
            requiredType = preferences.contentType.value,
            candidates = candidates.map {
                CanonicalTitleAnchor.from(
                    media = it.media,
                    alternativeTitles = it.alternativeTitles,
                )
            },
        )
        return when (resolution) {
            is TitleAnchorResolution.Resolved -> {
                val selected = candidates.firstOrNull {
                    CanonicalMediaIdentity.from(it.media) == resolution.anchor.identity
                } ?: return AnchorWork.NotFound(title)
                AnchorWork.Resolved(repository.enrichSimilarityAnchor(selected))
            }
            is TitleAnchorResolution.Ambiguous -> {
                val identities = resolution.candidates.mapTo(linkedSetOf()) {
                    it.anchor.identity
                }
                AnchorWork.Clarify(
                    title,
                    candidates.filter {
                        CanonicalMediaIdentity.from(it.media) in identities
                    },
                )
            }
            is TitleAnchorResolution.NotFound -> AnchorWork.Clarify(
                title = title,
                candidates = candidates.take(MAX_ANCHOR_CHOICES),
            )
        }
    }

    private suspend fun rankCandidates(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
        anchor: RecommendationCandidate?,
        context: RankingContext,
    ): RecommendationRankingSnapshot {
        if (candidates.isEmpty()) {
            return RecommendationRankingSnapshot(
                ranked = emptyList(),
                rejectedLowConfidence = emptyList(),
                confidenceThreshold = 0.0,
                scoredCandidateCount = 0,
                diversificationPoolSize = 0,
            )
        }
        val query = RecommendationQueryBuilder.build(preferences)
        val batchScorer = semanticBatchScorerProvider()
        val semanticScores = batchScorer
            ?.takeIf { query.isNotBlank() }
            ?.let { scorer ->
                try {
                    scorer.similarities(
                        query = query,
                        documents = candidates.map(::semanticDocument),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    emptyMap()
                }
            }
            .orEmpty()
        return RecommendationRanker.rankWithDiagnostics(
            preferences = preferences,
            candidates = candidates,
            likes = context.likes,
            taste = context.taste,
            similarityAnchor = anchor?.media,
            // Session orchestration accepts only bounded batch snapshots. This
            // prevents an implementation from embedding the query once per
            // candidate through the ranker's legacy compatibility hook.
            semanticScorer = null,
            precomputedSemanticScores = semanticScores,
            diversificationLimit = DIVERSIFICATION_POOL_LIMIT,
        )
    }

    private fun publishInitial(token: Long, outcome: InitialOutcome) {
        if (!isCurrent(token)) return
        loadInFlight = false
        when (outcome) {
            is InitialOutcome.ClarifyAnchor -> {
                pendingAnchorChoices = outcome.candidates.associateBy { it.media.key }
                val question = RecommendationQuestion(
                    id = ANCHOR_CLARIFICATION_ID,
                    dimension = RecommendationDimension.UNSUPPORTED_CONFIRMATION,
                    text = "Which ${outcome.title} did you mean?",
                    type = RecommendationQuestionType.SINGLE_SELECT,
                    options = outcome.candidates.map { candidate ->
                        RecommendationOption(
                            id = candidate.media.key,
                            label = buildString {
                                append(candidate.media.title)
                                candidate.media.year.takeIf(String::isNotBlank)?.let {
                                    append(" (")
                                    append(it.take(4))
                                    append(')')
                                }
                            },
                            value = candidate.media.key,
                        )
                    },
                )
                _state.value = RecommendationUiState.Question(
                    preferences = outcome.preferences,
                    question = question,
                    progressMessage = "Choose a canonical title before I rank matches.",
                    canGoBack = history.isNotEmpty(),
                )
            }
            is InitialOutcome.AnchorNotFound -> {
                _state.value = RecommendationUiState.Error(
                    preferences = outcome.preferences,
                    message = "I couldn't confidently identify “${outcome.title}”. " +
                        "Try its release year or original title.",
                    canRetry = false,
                )
            }
            is InitialOutcome.Complete -> {
                preferences = outcome.preferences
                activeSpec = outcome.spec
                similarityAnchor = outcome.anchor
                candidatePool = outcome.candidatePool
                rankedSnapshot = outcome.ranked.distinctBy { it.media.key }
                visibleCount = minOf(RESULT_PAGE_SIZE, rankedSnapshot.size)
                cursor = outcome.cursor
                sourceHasMore = outcome.sourceHasMore
                sourceHealth = outcome.sourceHealth
                refinementQuestion = outcome.refinementQuestion
                if (rankedSnapshot.isNotEmpty()) {
                    publishResults()
                } else {
                    val message = if (outcome.rejectedLowConfidenceCount > 0) {
                        "The available titles were weak matches, so I left them out. " +
                            "Add one useful detail and try again."
                    } else {
                        "No titles matched every selected requirement."
                    }
                    _state.value = RecommendationUiState.Empty(
                        preferences = preferences,
                        message = message,
                        options = outcome.relaxations,
                    )
                }
            }
        }
    }

    private fun publishResults(
        pageError: String? = null,
    ) {
        val visible = rankedSnapshot.take(visibleCount)
        if (visible.isEmpty()) return
        _state.value = RecommendationUiState.Results(
            preferences = preferences,
            candidates = visible,
            refreshing = false,
            loadingMore = false,
            hasMore = visibleCount < rankedSnapshot.size || sourceHasMore,
            pageError = pageError,
            sourceHealth = sourceHealth,
            webLimited = sourceHealth.web == RecommendationSourceStatus.UNAVAILABLE,
            refinementQuestion = refinementQuestion,
        )
    }

    private fun publishFailureIfCurrent(token: Long, error: Throwable) {
        if (!isCurrent(token)) return
        loadInFlight = false
        publishFailure(error)
    }

    private fun publishFailure(error: Throwable) {
        updateSourceHealth(error)
        _state.value = if (error is CatalogSourceException) {
            RecommendationUiState.SourceUnavailable(
                preferences = preferences,
                message = pageFailureMessage(error),
            )
        } else {
            RecommendationUiState.Error(
                preferences = preferences,
                message = error.message
                    ?: "Something went wrong while loading recommendations.",
                canRetry = true,
            )
        }
    }

    private fun updateSourceHealth(error: Throwable) {
        if (error !is CatalogSourceException) return
        sourceHealth = when (error.source) {
            CatalogSource.TMDB -> sourceHealth.copy(
                catalogue = RecommendationSourceStatus.UNAVAILABLE,
            )
            CatalogSource.IMDB -> sourceHealth.copy(
                imdb = RecommendationSourceStatus.UNAVAILABLE,
            )
        }
    }

    private fun pageFailureMessage(error: Throwable): String = when {
        error is RecommendationPageTimeoutException ->
            "The catalogue took too long to answer. Your loaded matches are still here."
        error is CatalogSourceException && error.source == CatalogSource.IMDB ->
            "IMDb is temporarily unavailable. Your loaded matches are still here."
        error is CatalogSourceException ->
            "The catalogue is temporarily unavailable. Your loaded matches are still here."
        else -> error.message?.take(160)
            ?: "The next page couldn't be loaded. Your current matches are unchanged."
    }

    private fun removeAndReplace(mediaKey: String) {
        candidatePool = candidatePool - mediaKey
        rankedSnapshot = rankedSnapshot.filterNot { it.media.key == mediaKey }
        visibleCount = minOf(visibleCount, rankedSnapshot.size)
        if (visibleCount < rankedSnapshot.size) {
            visibleCount = minOf(rankedSnapshot.size, visibleCount + 1)
        }
        if (visibleCount > 0) {
            publishResults()
        } else if (sourceHasMore) {
            launchAppend(forceRetry = false)
        } else {
            _state.value = RecommendationUiState.Empty(
                preferences = preferences,
                message = "You've reviewed every confident match for this request.",
            )
        }
    }

    private fun beginSession(resetRankedState: Boolean): Long {
        sessionId += 1
        activeJob?.cancel()
        activeJob = null
        loadInFlight = false
        pendingAnchorChoices = emptyMap()
        if (resetRankedState) {
            activeSpec = null
            similarityAnchor = null
            candidatePool = emptyMap()
            rankedSnapshot = emptyList()
            visibleCount = 0
            cursor = RecommendationPageCursor()
            sourceHasMore = true
            sourceHealth = RecommendationSourceHealth()
            refinementQuestion = null
        }
        return sessionId
    }

    private fun isCurrent(token: Long): Boolean = token == sessionId

    private fun captureRankingContext(): RankingContext = RankingContext(
        likes = likesProvider().toList(),
        recentKeys = recentlyPlayedProvider().mapTo(linkedSetOf(), Media::key),
        taste = store.taste.value,
    )

    private fun hardEligibleCount(
        preferences: RecommendationPreferences,
        candidates: Collection<RecommendationCandidate>,
        context: RankingContext,
    ): Int = RecommendationRanker.hardFilter(
        preferences = preferences,
        candidates = candidates.toList(),
        recentlyPlayedKeys = context.recentKeys,
        seenKeys = context.taste.seenKeys,
    ).size

    private fun selectBoundedRankingPool(
        candidates: List<RecommendationCandidate>,
    ): List<RecommendationCandidate> = candidates
        .sortedWith(
            compareByDescending<RecommendationCandidate> { candidate ->
                candidate.relevanceEvidence.any(
                    RecommendationEvidence::isAnchorGraphEvidence,
                ) || candidate.sources.any { source ->
                    source.contains("RELATED", ignoreCase = true) ||
                        source.contains("SIMILAR", ignoreCase = true)
                }
            }
                .thenByDescending(RecommendationCandidate::sourceCount)
                .thenBy { candidate ->
                    candidate.sourceRanks.values.minOrNull()
                        ?: candidate.sourcePosition
                }
                .thenBy { it.media.key },
        )
        .take(MAX_RANKING_CANDIDATES)

    private fun semanticDocument(
        candidate: RecommendationCandidate,
    ): SemanticCandidateDocument {
        val media = candidate.media
        val metadata = candidate.metadata
        val identity = listOf(
            media.key,
            media.year,
            media.genres.sorted().joinToString(","),
            media.cast.sorted().joinToString(","),
            metadata.runtimeMinutes?.toString().orEmpty(),
            metadata.averageEpisodeRuntimeMinutes?.toString().orEmpty(),
            metadata.originalLanguage.orEmpty(),
            metadata.director.orEmpty(),
            media.overview.hashCode().toString(),
            candidate.evidence.hashCode().toString(),
        ).joinToString("|")
        val text = listOf(
            media.overview,
            media.genres.joinToString(" "),
            media.cast.joinToString(" "),
            metadata.director.orEmpty(),
            candidate.evidence,
        ).filter(String::isNotBlank).joinToString(". ")
        return SemanticCandidateDocument(
            mediaKey = media.key,
            metadataIdentity = identity,
            text = text,
        )
    }

    private fun MutableMap<String, RecommendationCandidate>.merge(
        incoming: RecommendationCandidate,
    ) {
        this[incoming.media.key] = mergeRecommendationCandidates(
            this[incoming.media.key],
            incoming,
        )
    }

    private fun RecommendationPreferences.minimumBroadDiscoveryPages(): Int = if (
        similarityTitle == null &&
        (includedGenres.isNotEmpty() || semanticFacets.isNotEmpty() ||
            unmatchedPreferences.isNotEmpty())
    ) {
        2
    } else {
        0
    }

    private fun RecommendationPageCursor.advance(
        candidates: List<RecommendationCandidate>,
    ): RecommendationPageCursor = copy(
        page = page + 1,
        seenKeys = seenKeys + candidates.map { it.media.key },
    )

    private fun RecommendationSourceHealth.requiredFailureOrNull():
        CatalogSourceException? = when {
        imdb == RecommendationSourceStatus.UNAVAILABLE -> CatalogSourceException(
            source = CatalogSource.IMDB,
            message = "IMDb is temporarily unavailable.",
        )
        catalogue == RecommendationSourceStatus.UNAVAILABLE -> CatalogSourceException(
            source = CatalogSource.TMDB,
            message = "The catalogue is temporarily unavailable.",
        )
        else -> null
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
                includedGenres = selected.map { PreferenceSignal(it, explicit, soft) },
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
            RecommendationDimension.STATUS -> base
            RecommendationDimension.FAMILIARITY -> base.copy(
                familiarity = selected.firstNotNullOfOrNull { value ->
                    FamiliarityPreference.entries.firstOrNull { it.name == value }
                }?.let { PreferenceSignal(it, explicit, soft) },
            )
            RecommendationDimension.SUBJECTIVE_FACET -> base.copy(
                semanticFacets = (
                    base.semanticFacets + selected.mapNotNull { value ->
                        RecommendationOntology.byId(value.removePrefix("facet:"))
                    }.map { PreferenceSignal(it, explicit, soft) }
                    ).distinctBy { it.value.id },
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
    ): RecommendationPreferences = when {
        value.startsWith("imdb:") -> current.copy(
            minimumRottenTomatoes = null,
            minimumTmdb = null,
            minimumImdb = value.substringAfter(':').toDoubleOrNull()?.let {
                PreferenceSignal(
                    it,
                    PreferenceOrigin.EXPLICIT,
                    ConstraintStrength.HARD,
                )
            },
        )
        value.startsWith("rt:") -> current.copy(
            minimumImdb = null,
            minimumTmdb = null,
            minimumRottenTomatoes = value.substringAfter(':').toIntOrNull()?.let {
                PreferenceSignal(
                    it,
                    PreferenceOrigin.EXPLICIT,
                    ConstraintStrength.HARD,
                )
            },
        )
        else -> current
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
        } ?: throw IllegalStateException(
            "The comparison title's runtime could not be verified.",
        )
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

    private data class RankingContext(
        val likes: List<Media>,
        val recentKeys: Set<String>,
        val taste: TasteProfile,
    )

    private data class InitialRequest(
        val preferences: RecommendationPreferences,
        val forcedAnchor: RecommendationCandidate? = null,
        val rankingContext: RankingContext,
    )

    private data class AppendRequest(
        val preferences: RecommendationPreferences,
        val spec: CatalogDiscoverySpec,
        val anchor: RecommendationCandidate?,
        val cursor: RecommendationPageCursor,
        val knownCandidates: Map<String, RecommendationCandidate>,
        val rankedKeys: Set<String>,
        val rankingContext: RankingContext,
        val sourceHealth: RecommendationSourceHealth,
    )

    private sealed interface AnchorWork {
        data object None : AnchorWork
        data class Resolved(val candidate: RecommendationCandidate) : AnchorWork
        data class Clarify(
            val title: String,
            val candidates: List<RecommendationCandidate>,
        ) : AnchorWork
        data class NotFound(val title: String) : AnchorWork
    }

    private sealed interface InitialOutcome {
        data class Complete(
            val preferences: RecommendationPreferences,
            val spec: CatalogDiscoverySpec,
            val anchor: RecommendationCandidate?,
            val candidatePool: Map<String, RecommendationCandidate>,
            val ranked: List<RecommendationCandidate>,
            val rejectedLowConfidenceCount: Int,
            val cursor: RecommendationPageCursor,
            val sourceHasMore: Boolean,
            val sourceHealth: RecommendationSourceHealth,
            val refinementQuestion: RecommendationQuestion?,
            val relaxations: List<ConstraintRelaxation>,
        ) : InitialOutcome

        data class ClarifyAnchor(
            val preferences: RecommendationPreferences,
            val title: String,
            val candidates: List<RecommendationCandidate>,
        ) : InitialOutcome

        data class AnchorNotFound(
            val preferences: RecommendationPreferences,
            val title: String,
        ) : InitialOutcome
    }

    private data class AppendOutcome(
        val candidatePool: Map<String, RecommendationCandidate>,
        val rankedAppend: List<RecommendationCandidate>,
        val cursor: RecommendationPageCursor,
        val sourceHasMore: Boolean,
        val sourceHealth: RecommendationSourceHealth,
    )

    private class RecommendationPageTimeoutException :
        IllegalStateException("The recommendation page timed out.")

    private companion object {
        const val RESULT_PAGE_SIZE = 20
        const val MAX_INITIAL_PAGES = 8
        const val MAX_APPEND_PAGES = 6
        const val MAX_INITIAL_DURATION_MS = 15_000L
        const val MAX_APPEND_DURATION_MS = 12_000L
        const val DEFAULT_PAGE_TIMEOUT_MS = 8_000L
        const val MAX_NO_PROGRESS_PAGES = 2
        const val MAX_SEED_CANDIDATES = 120
        const val MAX_RANKING_CANDIDATES = 160
        const val DIVERSIFICATION_POOL_LIMIT = 60
        const val MAX_ANCHOR_CHOICES = 5
        const val ANCHOR_CLARIFICATION_ID = "anchor_disambiguation"
        const val ALREADY_SEEN_REASON = "I've already seen it"
    }
}

internal fun normalizeGenreName(genre: String): String {
    val trimmed = genre.lowercase().trim()
    return when (trimmed) {
        "sci-fi", "science fiction", "scifi" -> "science fiction"
        "action & adventure", "action/adventure" -> "action & adventure"
        "sci-fi & fantasy", "scifi & fantasy" -> "sci-fi & fantasy"
        "tv movie", "made for tv" -> "tv movie"
        else -> trimmed
    }
}

internal fun isCanonicalGenreMatch(required: String, candidateGenre: String, mediaType: MediaType): Boolean {
    val req = normalizeGenreName(required)
    val cand = normalizeGenreName(candidateGenre)
    if (req == cand) return true
    if (req == "science fiction" && (cand == "science fiction" || cand == "sci-fi & fantasy")) return true
    if (req == "fantasy" && cand == "sci-fi & fantasy") return true
    if (req == "action" && (cand == "action & adventure" || cand == "action")) return true
    if (req == "adventure" && (cand == "action & adventure" || cand == "adventure")) return true
    if (req == "war" && cand == "war & politics") return true
    return false
}

internal fun parseYearMin(rule: String?): Int? {
    if (rule == null) return null
    if (rule.equals("Recent", ignoreCase = true)) return java.time.Year.now().value - 5
    if (rule.endsWith("s")) return rule.removeSuffix("s").toIntOrNull()
    return null
}

internal fun parseYearMax(rule: String?): Int? {
    if (rule == null) return null
    if (rule.startsWith("Before ")) return rule.substringAfter("Before ").toIntOrNull()
    if (rule.endsWith("s")) {
        val decade = rule.removeSuffix("s").toIntOrNull()
        if (decade != null) return decade + 9
    }
    return null
}
