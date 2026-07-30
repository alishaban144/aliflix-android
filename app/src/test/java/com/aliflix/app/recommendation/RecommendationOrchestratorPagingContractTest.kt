package com.aliflix.app.recommendation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun usefulQuestionAppearsBeforeSlowPreparationCompletes() = runTest {
        val repository = DelayedRepository()
        val orchestrator = orchestrator(repository)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        orchestrator.submitText("a funny movie")

        assertTrue(orchestrator.state.value is RecommendationUiState.Question)
        assertEquals(0, repository.requests)

        runCurrent()
        assertTrue(repository.started.isCompleted)
        assertTrue(orchestrator.state.value is RecommendationUiState.Question)

        repository.release.complete(Unit)
        advanceUntilIdle()
        assertTrue(orchestrator.state.value is RecommendationUiState.Question)
    }

    private fun CoroutineScope.orchestrator(
        repository: RecommendationCandidateRepository,
    ) = RecommendationOrchestrator(
        scope = this,
        repository = repository,
        store = RecommendationStore(InMemoryContext()),
        likesProvider = ::emptyList,
        recentlyPlayedProvider = ::emptyList,
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
                candidates = listOf(candidate(9200)),
                nextCursor = null,
                hasMore = false,
                sourceHealth = RecommendationSourceHealth(),
            )
        }

        override suspend fun resolveSimilarityAnchor(
            title: String,
        ): RecommendationCandidate? = null
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
