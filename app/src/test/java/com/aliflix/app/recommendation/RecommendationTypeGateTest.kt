package com.aliflix.app.recommendation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationTypeGateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun noRepositoryRequestStartsUntilMovieOrSeriesIsSelected() = runTest {
        val repository = CountingRepository()
        val orchestrator = RecommendationOrchestrator(
            scope = this,
            repository = repository,
            store = RecommendationStore(InMemoryContext()),
            likesProvider = ::emptyList,
            recentlyPlayedProvider = ::emptyList,
            dispatchers = RecommendationDispatchers(
                io = StandardTestDispatcher(testScheduler),
                computation = StandardTestDispatcher(testScheduler),
            ),
        )

        orchestrator.submitDraft(com.aliflix.app.recommendation.RecommendationRequestDraft(freeText = "a thriller made after 2015 with IMDb 7 or higher"))
        orchestrator.showMatches()
        orchestrator.surpriseMe()
        advanceUntilIdle()

        assertEquals(0, repository.pageRequests)
        assertTrue(orchestrator.state.value is RecommendationUiState.SelectType)

        orchestrator.selectType(RecommendationMediaKind.MOVIE)
        advanceUntilIdle()
        assertEquals("Selecting a type is local-only.", 0, repository.pageRequests)

        orchestrator.submitDraft(com.aliflix.app.recommendation.RecommendationRequestDraft(freeText = "a thriller made after 2015 with IMDb 7 or higher"))
        orchestrator.showMatches()
        advanceUntilIdle()

        assertEquals(1, repository.pageRequests)
        assertEquals(RecommendationMediaKind.MOVIE, repository.lastSpec?.mediaKind)
    }

    private class CountingRepository : RecommendationCandidateRepository {
        var pageRequests = 0
        var lastSpec: CatalogDiscoverySpec? = null

        override suspend fun discoverPage(
            spec: CatalogDiscoverySpec,
            cursor: RecommendationPageCursor,
            requiredFields: RequiredMetadataFields,
        ): RecommendationPage {
            pageRequests += 1
            lastSpec = spec
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
    }

    /**
     * RecommendationStore only needs private SharedPreferences. A small proxy
     * keeps this a fast JVM test without adding Robolectric or a device.
     */
    private class InMemoryContext : ContextWrapper(null) {
        private val preferences = InMemoryPreferences().value

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences = preferences
    }

    private class InMemoryPreferences : InvocationHandler {
        private val values = linkedMapOf<String, Any?>()
        val value: SharedPreferences
        private val editor: SharedPreferences.Editor

        init {
            value = proxy(SharedPreferences::class.java, this)
            editor = proxy(SharedPreferences.Editor::class.java, EditorHandler(values))
        }

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
        private var clear = false

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
                    clear = true
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
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
            clear = false
            removals.clear()
            pending.clear()
        }
    }

    private companion object {
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
