package com.aliflix.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val result = discovery.discover(WebDiscoveryMode.RECOMMENDATION)

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

        val result = discovery.discover(WebDiscoveryMode.RECOMMENDATION)

        assertEquals(listOf("brave", "wikipedia", "ddg"), calls)
        assertEquals(2, result.successfulSources)
        assertEquals(setOf("DUCKDUCKGO", "WIKIPEDIA"), result.items.first().sources)
        assertEquals("Paprika", result.items.first().title)
    }

    @Test
    fun describePlotPreservesAllThreeSourceBehavior() = runTest {
        val calls = mutableListOf<String>()
        val discovery = discovery(
            brave = {
                calls += "brave"
                listOf(Candidate("Inception", setOf("BRAVE"), 1))
            },
            wikipedia = {
                calls += "wikipedia"
                listOf(Candidate("Paprika", setOf("WIKIPEDIA"), 2))
            },
            duckDuckGo = {
                calls += "ddg"
                listOf(Candidate("Dreamscape", setOf("DUCKDUCKGO"), 3))
            },
        )

        val result = discovery.discover(WebDiscoveryMode.DESCRIBE_PLOT)

        assertEquals(listOf("brave", "wikipedia", "ddg"), calls)
        assertEquals(3, result.items.size)
        assertEquals(3, result.successfulSources)
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

        val result = discovery.discover(WebDiscoveryMode.RECOMMENDATION)

        assertEquals(listOf("Breaking Bad"), result.items.map { it.title })
        assertEquals(1, result.successfulSources)
        assertTrue(result.items.none { it.title.contains("ignore previous", true) })
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
