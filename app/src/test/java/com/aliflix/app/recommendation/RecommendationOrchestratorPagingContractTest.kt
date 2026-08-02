package com.aliflix.app.recommendation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.aliflix.app.data.CatalogSource
import com.aliflix.app.data.CatalogSourceException
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationOrchestratorPagingContractTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun firstTwentyAppearThenLoadMoreAppendsWithoutReordering() = runTest {
        val repository = FixturePageRepository(
            pages = mapOf(
                1 to page(1..20, nextPage = 2),
                2 to page(21..40, nextPage = null),
            ),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        val first = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..20).map(::key), first.candidates.map { it.media.key })
        assertTrue(first.hasMore)

        orchestrator.loadMore()
        advanceUntilIdle()

        val second = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..40).map(::key), second.candidates.map { it.media.key })
        assertFalse(second.hasMore)
        assertEquals(listOf(1, 2), repository.requestedPages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun oneToNineteenHardValidTitlesAreShownInsteadOfRejected() = runTest {
        val repository = FixturePageRepository(
            pages = mapOf(1 to page(1..13, nextPage = null)),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(13, result.candidates.size)
        assertFalse(result.hasMore)
        assertEquals((1..13).map(::key), result.candidates.map { it.media.key })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun generalTagRequestScansTwoBoundedPagesAndKeepsEveryTagMatch() = runTest {
        val repository = FixturePageRepository(
            pages = mapOf(
                1 to page(1..20, nextPage = 2),
                2 to page(21..40, nextPage = null),
            ),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.submitText("thriller movie")
        advanceUntilIdle()
        if (orchestrator.state.value is RecommendationUiState.Question) {
            orchestrator.showMatches()
            advanceUntilIdle()
        }

        val first = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf(1, 2), repository.requestedPages)
        assertEquals(20, first.candidates.size)
        assertTrue(first.candidates.all { "Thriller" in it.media.genres })

        orchestrator.loadMore()
        advanceUntilIdle()
        val expanded = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(40, expanded.candidates.size)
        assertEquals(listOf(1, 2), repository.requestedPages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changingTypeCancelsOldRequestAndRestartsAtFirstCursor() = runTest {
        val repository = CancellingRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        runCurrent()
        assertTrue(repository.movieStarted.isCompleted)

        orchestrator.selectType(RecommendationMediaKind.SERIES)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        assertTrue(repository.movieCancelled)
        assertEquals(
            listOf(
                RecommendationMediaKind.MOVIE to 1,
                RecommendationMediaKind.SERIES to 1,
            ),
            repository.requests,
        )
        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf(MediaType.TV), result.candidates.map { it.media.type }.distinct())
        assertEquals(listOf("tv:9001"), result.candidates.map { it.media.key })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changingHardPreferencesCancelsStaleCatalogueWork() = runTest {
        val repository = HardPreferenceCancellingRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.submitText("a thriller made after 2015 with IMDb 7 or higher")
        runCurrent()
        assertTrue(repository.firstStarted.isCompleted)

        orchestrator.submitText("a thriller made after 2020 with IMDb 8 or higher")
        advanceUntilIdle()

        assertTrue(repository.firstCancelled)
        assertEquals(2, repository.specs.size)
        assertTrue(repository.specs[0].fingerprint != repository.specs[1].fingerprint)
        assertTrue((repository.specs.last().yearMinimum ?: 0) > 2015)
        assertEquals(8.0, repository.specs.last().minimumImdb ?: 0.0, 0.001)
        assertEquals(listOf(1, 1), repository.requestedPages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exhaustedHealthyCatalogueIsARealEmptyResult() = runTest {
        val repository = FixturePageRepository(
            pages = mapOf(
                1 to RecommendationPage(
                    candidates = emptyList(),
                    nextCursor = null,
                    hasMore = false,
                    sourceHealth = RecommendationSourceHealth(
                        catalogue = RecommendationSourceStatus.AVAILABLE,
                    ),
                ),
            ),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue(state is RecommendationUiState.Empty)
        assertFalse(state is RecommendationUiState.SourceUnavailable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun requiredProviderOutageIsNotReportedAsNoMatches() = runTest {
        val repository = FixturePageRepository(
            pages = mapOf(
                1 to RecommendationPage(
                    candidates = emptyList(),
                    nextCursor = null,
                    hasMore = false,
                    sourceHealth = RecommendationSourceHealth(
                        catalogue = RecommendationSourceStatus.UNAVAILABLE,
                    ),
                ),
            ),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue(state is RecommendationUiState.SourceUnavailable)
        assertFalse(state is RecommendationUiState.Empty)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun initialScanContinuesPastBoundedYieldUntilPageSevenFindsMatch() = runTest {
        val repository = SparseMatchingRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.submitText("a movie with IMDb 8 or higher")
        orchestrator.showMatches()
        advanceUntilIdle()

        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf(7), result.candidates.map { it.media.id })
        assertEquals((1..7).toList(), repository.requestedPages)
        assertFalse(result.hasMore)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retrievalStartsBeforeAnyCandidateInformedQuestion() = runTest {
        val repository = DelayedRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.submitText("a funny movie")

        assertTrue(orchestrator.state.value is RecommendationUiState.Discovering)
        assertEquals(0, repository.requests)

        runCurrent()
        assertTrue(repository.started.isCompleted)
        assertTrue(orchestrator.state.value is RecommendationUiState.Discovering)

        repository.release.complete(Unit)
        advanceUntilIdle()
        assertTrue(orchestrator.state.value is RecommendationUiState.Results)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun providerFailureWaitsForAnExplicitRetryAtTheSameCursor() = runTest {
        val repository = FailOnceRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        assertEquals(listOf(1), repository.requestedPages)
        assertTrue(orchestrator.state.value is RecommendationUiState.SourceUnavailable)

        orchestrator.retryPage()
        advanceUntilIdle()

        assertEquals(listOf(1, 1), repository.requestedPages)
        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf("movie:9300"), result.candidates.map { it.media.key })
        assertEquals(
            RecommendationSourceStatus.AVAILABLE,
            result.sourceHealth.catalogue,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryMakesARealRequestAndRecoversRequiredSourceHealth() = runTest {
        val repository = FailUntilRetryRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is RecommendationUiState.SourceUnavailable)
        assertEquals(listOf(1), repository.requestedPages)

        orchestrator.retryPage()
        advanceUntilIdle()

        assertEquals(listOf(1, 1), repository.requestedPages)
        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf("movie:9301"), result.candidates.map { it.media.key })
        assertEquals(
            RecommendationSourceStatus.AVAILABLE,
            result.sourceHealth.catalogue,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedLoadMoreRetryPreservesCursorAndDisplayedOrder() = runTest {
        val repository = RecoveringSecondPageRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()
        val first = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..20).map(::key), first.candidates.map { it.media.key })

        orchestrator.loadMore()
        advanceUntilIdle()
        val failed = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..20).map(::key), failed.candidates.map { it.media.key })
        assertTrue(failed.pageError != null)

        orchestrator.retryPage()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 2), repository.requestedPages)
        val recovered = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..40).map(::key), recovered.candidates.map { it.media.key })
        assertFalse(recovered.hasMore)
        assertEquals(
            RecommendationSourceStatus.AVAILABLE,
            recovered.sourceHealth.catalogue,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidRepeatedPreferenceAnswersStartOnlyTheFirstMutation() = runTest {
        val repository = RecordingSpecRepository()
        val orchestrator = orchestrator(repository)
        val genreQuestion = RecommendationQuestion(
            id = "genre_fixture",
            dimension = RecommendationDimension.GENRE,
            text = "Which genre?",
            type = RecommendationQuestionType.SINGLE_SELECT,
            options = emptyList(),
        )

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.answer(genreQuestion, listOf("Crime"))
        orchestrator.answer(genreQuestion, listOf("Horror"))
        orchestrator.answer(genreQuestion, listOf("Comedy"))
        advanceUntilIdle()

        assertEquals(1, repository.specs.size)
        assertEquals(listOf("Crime"), repository.specs.single().includedGenres)
        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf("movie:9500"), result.candidates.map { it.media.key })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidSurpriseMeTapsAreSingleFlight() = runTest {
        val repository = CountingSuccessRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        repeat(12) { orchestrator.surpriseMe() }
        advanceUntilIdle()

        assertEquals(1, repository.requests)
        assertTrue(orchestrator.state.value is RecommendationUiState.Results)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun repeatedNearEndSignalsRequestOnePageWhileAppendIsInFlight() = runTest {
        val repository = SingleFlightAppendRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        repeat(10) { orchestrator.loadMore() }
        assertTrue(
            (orchestrator.state.value as RecommendationUiState.Results).loadingMore,
        )
        runCurrent()
        assertTrue(repository.secondPageStarted.isCompleted)
        repeat(10) { orchestrator.loadMore() }
        runCurrent()
        assertEquals(listOf(1, 2), repository.requestedPages)

        repository.releaseSecondPage.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), repository.requestedPages)
        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(40, result.candidates.size)
        assertFalse(result.loadingMore)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun overlappingCataloguePagesAreDeduplicatedByCanonicalMediaKey() = runTest {
        val repository = FixturePageRepository(
            pages = mapOf(
                1 to page(1..20, nextPage = 2),
                2 to page(11..30, nextPage = null),
            ),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()
        orchestrator.loadMore()
        advanceUntilIdle()

        val result = orchestrator.state.value as RecommendationUiState.Results
        val keys = result.candidates.map { it.media.key }
        assertEquals(30, keys.size)
        assertEquals(30, keys.distinct().size)
        assertEquals((1..30).map(::key).toSet(), keys.toSet())
        assertEquals(listOf(1, 2), repository.requestedPages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun laterDuplicateEvidenceEnrichesDisplayedCandidateWithoutReordering() = runTest {
        val enrichedDuplicate = candidate(1).copy(
            evidence = "Later editorial relationship evidence.",
            sources = setOf("EDITORIAL_GRAPH"),
            sourceRanks = mapOf("EDITORIAL_GRAPH" to 1),
            relevanceEvidence = listOf(
                RecommendationEvidence(
                    type = RecommendationEvidenceType.SHARED_CREATOR,
                    strength = 0.9,
                    source = "EDITORIAL_GRAPH",
                    description = "Shares a creator with the anchor",
                ),
            ),
        )
        val repository = FixturePageRepository(
            pages = mapOf(
                1 to page(1..20, nextPage = 2),
                2 to RecommendationPage(
                    candidates = listOf(enrichedDuplicate) + (21..40).map(::candidate),
                    nextCursor = null,
                    hasMore = false,
                    sourceHealth = RecommendationSourceHealth(),
                ),
            ),
        )
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()
        orchestrator.loadMore()
        advanceUntilIdle()

        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..40).map(::key), result.candidates.map { it.media.key })
        val first = result.candidates.first()
        assertTrue("TMDB" in first.sources)
        assertTrue("EDITORIAL_GRAPH" in first.sources)
        assertTrue(first.evidence.contains("Later editorial relationship evidence"))
        assertTrue(
            first.relevanceEvidence.any {
                it.type == RecommendationEvidenceType.SHARED_CREATOR
            },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nonAdvancingDuplicateCursorIsExhaustedAfterBoundedAttempts() = runTest {
        val repository = StuckCursorRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()
        repeat(8) { orchestrator.loadMore() }
        advanceUntilIdle()

        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..20).map(::key), result.candidates.map { it.media.key })
        assertFalse(result.hasMore)
        assertEquals(listOf(1, 2, 3), repository.requestedPages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun appendTimeoutPreservesEarlierResultsAndWaitsForRetry() = runTest {
        val repository = TimingOutAppendRepository()
        val orchestrator = orchestrator(repository, pageTimeoutMillis = 100L)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()
        orchestrator.loadMore()
        advanceUntilIdle()

        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals((1..20).map(::key), result.candidates.map { it.media.key })
        assertTrue(result.pageError.orEmpty().contains("too long"))
        assertEquals(listOf(1, 2), repository.requestedPages)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationIgnoringOldSearchCannotPublishIntoANewerSession() = runTest {
        val repository = LateCompletionRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.submitText("a thriller made after 2015")
        runCurrent()
        assertTrue(repository.firstStarted.isCompleted)

        orchestrator.submitText("a thriller made after 2020")
        advanceUntilIdle()
        assertEquals(2, repository.requests)
        val fresh = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf("movie:9401"), fresh.candidates.map { it.media.key })

        repository.releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, repository.requests)
        val result = orchestrator.state.value as RecommendationUiState.Results
        assertEquals(listOf("movie:9401"), result.candidates.map { it.media.key })
        assertFalse(result.candidates.any { it.media.id == 9400 })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ambiguousCanonicalAnchorRequestsAChoiceBeforeCatalogueDiscovery() = runTest {
        val repository = AmbiguousAnchorRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.SERIES)
        orchestrator.submitText("Series similar to The Office")
        advanceUntilIdle()

        val state = orchestrator.state.value as RecommendationUiState.Question
        assertTrue(state.question.text.contains("The Office"))
        assertEquals(2, state.question.options.size)
        assertEquals(0, repository.pageRequests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retrievalAndSemanticInferenceExecuteOffTheCallerDispatcher() = runTest {
        val callerThread = Thread.currentThread().name
        val repositoryThread = CompletableDeferred<String>()
        val semanticThread = CompletableDeferred<String>()
        val workerExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "recommendation-test-worker").apply { isDaemon = true }
        }
        val worker = workerExecutor.asCoroutineDispatcher()
        try {
            val repository = ThreadRecordingRepository(repositoryThread)
            val scorer = SemanticBatchScorer { _, documents ->
                semanticThread.complete(Thread.currentThread().name)
                documents.associate { it.mediaKey to 1.0 }
            }
            val orchestrator = orchestrator(
                repository = repository,
                semanticBatchScorerProvider = { scorer },
                dispatchers = RecommendationDispatchers(
                    io = worker,
                    computation = worker,
                ),
            )

            orchestrator.selectType(RecommendationMediaKind.MOVIE)
            orchestrator.submitText("a thriller")
            runCurrent()

            val retrievalExecutionThread = repositoryThread.await()
            val semanticExecutionThread = semanticThread.await()
            val workerDrained = CompletableDeferred<Unit>()
            workerExecutor.execute { workerDrained.complete(Unit) }
            workerDrained.await()
            advanceUntilIdle()

            assertTrue(retrievalExecutionThread.startsWith("recommendation-test-worker"))
            assertTrue(semanticExecutionThread.startsWith("recommendation-test-worker"))
            assertTrue(retrievalExecutionThread != callerThread)
            assertTrue(semanticExecutionThread != callerThread)
            assertTrue(orchestrator.state.value is RecommendationUiState.Results)
        } finally {
            worker.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unexpectedRepositoryFailureUsesGeneralErrorState() = runTest {
        val repository = UnexpectedFailureRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.surpriseMe()
        advanceUntilIdle()

        assertEquals(1, repository.requests)
        assertTrue(orchestrator.state.value is RecommendationUiState.Error)
        assertFalse(orchestrator.state.value is RecommendationUiState.SourceUnavailable)
    }

    private fun TestScope.orchestrator(
        repository: RecommendationCandidateRepository,
        semanticBatchScorerProvider: () -> SemanticBatchScorer? = { null },
        dispatchers: RecommendationDispatchers = RecommendationDispatchers(
            io = StandardTestDispatcher(testScheduler),
            computation = StandardTestDispatcher(testScheduler),
        ),
        pageTimeoutMillis: Long = 8_000L,
    ) = RecommendationOrchestrator(
        scope = this,
        repository = repository,
        store = RecommendationStore(InMemoryContext()),
        likesProvider = ::emptyList,
        recentlyPlayedProvider = ::emptyList,
        semanticBatchScorerProvider = semanticBatchScorerProvider,
        dispatchers = dispatchers,
        pageTimeoutMillis = pageTimeoutMillis,
    )

    private class FixturePageRepository(
        private val pages: Map<Int, RecommendationPage>,
    ) : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            return pages.getValue(cursor.page)
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class CancellingRepository : RecommendationCandidateRepository {
        val movieStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<RecommendationMediaKind, Int>>()
        var movieCancelled = false

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requests += spec.mediaKind to cursor.page
            if (spec.mediaKind == RecommendationMediaKind.MOVIE) {
                movieStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    movieCancelled = true
                }
            }
            return RecommendationPage(
                candidates = listOf(candidate(9001, MediaType.TV)),
                nextCursor = null,
                hasMore = false,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class HardPreferenceCancellingRepository :
        RecommendationCandidateRepository {
        val firstStarted = CompletableDeferred<Unit>()
        val specs = mutableListOf<CatalogDiscoverySpec>()
        val requestedPages = mutableListOf<Int>()
        var firstCancelled = false

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            specs += spec
            requestedPages += cursor.page
            if (specs.size == 1) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled = true
                }
            }
            return RecommendationPage(
                candidates = listOf(
                    candidate(9100).copy(
                        media = candidate(9100).media.copy(
                            year = "2024",
                            imdbRating = 8.4,
                            genres = listOf("Thriller"),
                        ),
                    ),
                ),
                nextCursor = null,
                hasMore = false,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class SparseMatchingRepository : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            val isMatch = cursor.page == 7
            val nextPage = if (cursor.page < 7) cursor.page + 1 else null
            return RecommendationPage(
                candidates = listOf(
                    candidate(cursor.page).let { candidate ->
                        candidate.copy(
                            media = candidate.media.copy(
                                imdbRating = if (isMatch) 8.6 else 6.0,
                            ),
                            precomputedSemanticScore = if (isMatch) 1.0 else null,
                            evidence = if (isMatch) {
                                "Directly satisfies the requested IMDb quality constraint."
                            } else {
                                candidate.evidence
                            },
                        )
                    },
                ),
                nextCursor = nextPage?.let { RecommendationPageCursor(page = it) },
                hasMore = nextPage != null,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class DelayedRepository : RecommendationCandidateRepository {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requests += 1
            started.complete(Unit)
            release.await()
            return RecommendationPage(
                candidates = listOf(
                    candidate(9200).copy(
                        media = candidate(9200).media.copy(
                            genres = listOf("Comedy"),
                            overview = "A funny comedy with warm character humor.",
                        ),
                        evidence = "A funny comedy matching the requested mood.",
                        precomputedSemanticScore = 1.0,
                    ),
                ),
                nextCursor = null,
                hasMore = false,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class RecordingSpecRepository : RecommendationCandidateRepository {
        val specs = mutableListOf<CatalogDiscoverySpec>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            specs += spec
            return page(9500..9500, nextPage = null).copy(
                candidates = listOf(
                    candidate(9500).copy(
                        media = candidate(9500).media.copy(
                            genres = spec.includedGenres.ifEmpty { listOf("Crime") },
                        ),
                        evidence = "A crime story matching the selected genre.",
                        sourceRanks = mapOf("TMDB" to 1),
                        sourcePosition = 1,
                    ),
                ),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class CountingSuccessRepository : RecommendationCandidateRepository {
        var requests = 0

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requests += 1
            return page(9510..9510, nextPage = null)
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class SingleFlightAppendRepository : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()
        val secondPageStarted = CompletableDeferred<Unit>()
        val releaseSecondPage = CompletableDeferred<Unit>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            if (cursor.page == 2) {
                secondPageStarted.complete(Unit)
                releaseSecondPage.await()
            }
            return if (cursor.page == 1) {
                page(1..20, nextPage = 2)
            } else {
                page(21..40, nextPage = null)
            }
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class StuckCursorRepository : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            return RecommendationPage(
                candidates = (1..20).map(::candidate),
                nextCursor = cursor,
                hasMore = true,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class TimingOutAppendRepository : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            if (cursor.page == 2) delay(10_000L)
            return if (cursor.page == 1) {
                page(1..20, nextPage = 2)
            } else {
                page(21..40, nextPage = null)
            }
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class ThreadRecordingRepository(
        private val executionThread: CompletableDeferred<String>,
    ) : RecommendationCandidateRepository {
        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            executionThread.complete(Thread.currentThread().name)
            return page(9520..9520, nextPage = null).copy(
                candidates = listOf(
                    candidate(9520).copy(
                        evidence = "A tense psychological thriller.",
                        precomputedSemanticScore = 1.0,
                    ),
                ),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class FailOnceRepository : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            if (requestedPages.size == 1) {
                throw CatalogSourceException(
                    CatalogSource.TMDB,
                    "Temporary fixture outage",
                )
            }
            return page(9300..9300, nextPage = null)
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class FailUntilRetryRepository : RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            if (requestedPages.size == 1) {
                throw CatalogSourceException(
                    CatalogSource.TMDB,
                    "Temporary fixture outage",
                )
            }
            return page(9301..9301, nextPage = null)
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class RecoveringSecondPageRepository :
        RecommendationCandidateRepository {
        val requestedPages = mutableListOf<Int>()
        private var pageTwoAttempts = 0

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requestedPages += cursor.page
            if (cursor.page == 1) return page(1..20, nextPage = 2)
            pageTwoAttempts += 1
            // A failed append must leave the cursor on page two. The explicit
            // retry is the second page-two attempt.
            if (pageTwoAttempts == 1) {
                throw CatalogSourceException(
                    CatalogSource.TMDB,
                    "Temporary fixture outage",
                )
            }
            return page(21..40, nextPage = null)
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class LateCompletionRepository : RecommendationCandidateRepository {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var requests = 0

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requests += 1
            if (requests == 1) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseFirst.await()
                }
                return page(9400..9400, nextPage = null).copy(
                    candidates = listOf(
                        candidate(9400).copy(
                            media = candidate(9400).media.copy(year = "2018"),
                        ),
                    ),
                )
            }
            return page(9401..9401, nextPage = null).copy(
                candidates = listOf(
                    candidate(9401).copy(
                        media = candidate(9401).media.copy(year = "2024"),
                    ),
                ),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class UnexpectedFailureRepository : RecommendationCandidateRepository {
        var requests = 0

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            requests += 1
            error("Fixture programming error")
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
    }

    private class AmbiguousAnchorRepository : RecommendationCandidateRepository {
        var pageRequests = 0

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            pageRequests += 1
            return RecommendationPage(
                candidates = emptyList(),
                nextCursor = null,
                hasMore = false,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null

        override suspend fun resolveSimilarityAnchorCandidates(
            title: String,
            mediaKind: RecommendationMediaKind,
        ): List<RecommendationCandidate> = listOf(
            officeCandidate(9600, "2001", "The Office UK"),
            officeCandidate(9601, "2005", "The Office US"),
        )

        private fun officeCandidate(
            id: Int,
            year: String,
            alias: String,
        ): RecommendationCandidate {
            val base = candidate(id, MediaType.TV)
            return base.copy(
                media = base.media.copy(title = "The Office", year = year),
                alternativeTitles = setOf(alias),
            )
        }
    }

    private class InMemoryContext : ContextWrapper(null) {
        private val preferences = InMemoryPreferences().value

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences = preferences
    }

    private class InMemoryPreferences : InvocationHandler {
        private val values = linkedMapOf<String, Any?>()
        private val editor = proxy(
            SharedPreferences.Editor::class.java,
            EditorHandler(values),
        )
        val value: SharedPreferences = proxy(SharedPreferences::class.java, this)

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[arguments[0]] as? String ?: arguments[1]
                "getStringSet" ->
                    @Suppress("UNCHECKED_CAST")
                    ((values[arguments[0]] as? Set<String>) ?: arguments[1])
                "getInt" -> values[arguments[0]] as? Int ?: arguments[1]
                "getLong" -> values[arguments[0]] as? Long ?: arguments[1]
                "getFloat" -> values[arguments[0]] as? Float ?: arguments[1]
                "getBoolean" -> values[arguments[0]] as? Boolean ?: arguments[1]
                "contains" -> values.containsKey(arguments[0])
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> Unit
                "toString" -> "InMemorySharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private class EditorHandler(
        private val values: MutableMap<String, Any?>,
    ) : InvocationHandler {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearRequested = false

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (method.name) {
                "putString",
                "putStringSet",
                "putInt",
                "putLong",
                "putFloat",
                "putBoolean",
                -> {
                    val key = arguments[0] as String
                    pending[key] = arguments[1]
                    removals -= key
                    proxy
                }
                "remove" -> {
                    val key = arguments[0] as String
                    removals += key
                    pending -= key
                    proxy
                }
                "clear" -> {
                    clearRequested = true
                    proxy
                }
                "commit" -> {
                    applyChanges()
                    true
                }
                "apply" -> {
                    applyChanges()
                    Unit
                }
                "toString" -> "InMemorySharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }

        private fun applyChanges() {
            if (clearRequested) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
            pending.clear()
            removals.clear()
            clearRequested = false
        }
    }

    private companion object {
        fun page(
            ids: IntRange,
            nextPage: Int?,
        ) = RecommendationPage(
            candidates = ids.map(::candidate),
            nextCursor = nextPage?.let { RecommendationPageCursor(page = it) },
            hasMore = nextPage != null,
            sourceHealth = RecommendationSourceHealth(),
        )

        fun candidate(
            id: Int,
            type: MediaType = MediaType.MOVIE,
        ) = RecommendationCandidate(
            media = Media(
                id = id,
                type = type,
                title = "Fixture $id",
                overview = "A deterministic fixture title.",
                posterPath = "/fixture-$id.jpg",
                year = "2024",
                rating = 8.0,
                imdbRating = 8.0,
                genres = listOf("Thriller"),
            ),
            metadata = VerifiedMediaMetadata(
                genresVerified = true,
                runtimeMinutes = if (type == MediaType.MOVIE) 105 else null,
                averageEpisodeRuntimeMinutes = if (type == MediaType.TV) 48 else null,
                originalLanguage = "English",
            ),
            evidence = "A deterministic ${if (type == MediaType.MOVIE) "movie" else "series"} fixture.",
            sources = setOf("TMDB"),
            sourceRanks = mapOf("TMDB" to id),
            sourceCount = 1,
            sourcePosition = id,
        )

        fun key(id: Int) = "movie:$id"

        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                handler,
            ) as T

        fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
