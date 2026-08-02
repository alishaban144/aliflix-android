package com.aliflix.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class WebTitleDiscoveryTest {
    @Test
    fun recommendationUsesBraveFirstAndSkipsFallbacksWithEnoughTitles() = runTest {
        val calls = mutableListOf<String>()
        val discovery = discovery(
            brave = {
                calls += "brave"
                (1..18).map { Candidate("Title $it", setOf("BRAVE"), it) }
            },
            wikipedia = {
                calls += "wikipedia"
                emptyList()
            },
            duckDuckGo = {
                calls += "ddg"
                emptyList()
            },
        )

        val result = discovery.discover()

        assertEquals(listOf("brave"), calls)
        assertEquals(18, result.items.size)
        assertEquals(1, result.successfulSources)
    }

    @Test
    fun recommendationFallsBackAfterFailuresAndMergesConsensus() = runTest {
        val calls = mutableListOf<String>()
        val discovery = discovery(
            brave = {
                calls += "brave"
                error("timeout")
            },
            wikipedia = {
                calls += "wikipedia"
                listOf(Candidate("Paprika", setOf("WIKIPEDIA"), 5))
            },
            duckDuckGo = {
                calls += "ddg"
                listOf(
                    Candidate("Paprika", setOf("DUCKDUCKGO"), 2),
                    Candidate("Dreamscape", setOf("DUCKDUCKGO"), 3),
                )
            },
        )

        val result = discovery.discover()

        assertEquals(listOf("brave", "wikipedia", "ddg"), calls)
        assertEquals(2, result.successfulSources)
        assertEquals(setOf("DUCKDUCKGO", "WIKIPEDIA"), result.items.first().sources)
        assertEquals("Paprika", result.items.first().title)
    }

    @Test
    fun malformedProviderOutputCannotStopLaterFallback() = runTest {
        val discovery = discovery(
            brave = { throw IllegalArgumentException("malformed HTML") },
            wikipedia = { throw IllegalStateException("malformed JSON") },
            duckDuckGo = {
                listOf(Candidate("Breaking Bad", setOf("DUCKDUCKGO"), 1))
            },
        )

        val result = discovery.discover()

        assertEquals(listOf("Breaking Bad"), result.items.map { it.title })
        assertEquals(1, result.successfulSources)
        assertTrue(result.items.none { it.title.contains("ignore previous", true) })
    }

    @Test
    fun discoveryMergingAndSortingUseTheInjectedComputationDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "web-title-computation")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val observedThreads = CopyOnWriteArrayList<String>()
        try {
            val discovery = WebTitleDiscovery(
                brave = {
                    observedThreads += Thread.currentThread().name
                    listOf(Candidate("Paprika", setOf("BRAVE"), 2))
                },
                wikipedia = {
                    observedThreads += Thread.currentThread().name
                    listOf(Candidate("Paprika", setOf("WIKIPEDIA"), 1))
                },
                duckDuckGo = {
                    observedThreads += Thread.currentThread().name
                    emptyList()
                },
                keyOf = { it.title.lowercase() },
                merge = { first, second ->
                    observedThreads += Thread.currentThread().name
                    first.copy(sources = first.sources + second.sources)
                },
                sortScore = {
                    observedThreads += Thread.currentThread().name
                    it.sources.size.toDouble()
                },
                computationDispatcher = dispatcher,
            )

            discovery.discover()

            assertTrue(observedThreads.isNotEmpty())
            assertTrue(observedThreads.all { it == "web-title-computation" })
        } finally {
            dispatcher.close()
        }
    }

    private fun discovery(
        brave: suspend () -> List<Candidate>,
        wikipedia: suspend () -> List<Candidate>,
        duckDuckGo: suspend () -> List<Candidate>,
    ) = WebTitleDiscovery(
        brave = brave,
        wikipedia = wikipedia,
        duckDuckGo = duckDuckGo,
        keyOf = { it.title.lowercase() },
        merge = { first, second ->
            first.copy(
                sources = first.sources + second.sources,
                position = minOf(first.position, second.position),
            )
        },
        sortScore = { it.sources.size * 100.0 - it.position },
    )

    private data class Candidate(
        val title: String,
        val sources: Set<String>,
        val position: Int,
    )
}
