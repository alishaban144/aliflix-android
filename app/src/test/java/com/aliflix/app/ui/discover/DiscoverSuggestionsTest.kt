package com.aliflix.app.ui.discover

import com.aliflix.app.recommendation.RecommendationMediaKind
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverSuggestionsTest {
    @Test
    fun sessionOrderIsDistinctAndVariesWithSessionEntropy() {
        val first = createSessionSuggestionOrder(Random(11))
        val second = createSessionSuggestionOrder(Random(97))

        assertEquals(first.size, first.distinct().size)
        assertEquals(second.size, second.distinct().size)
        assertNotEquals(first, second)
    }

    @Test
    fun processSessionOrderIsAnImmutableStableSample() {
        val first = currentSessionSuggestionOrder()
        val second = currentSessionSuggestionOrder()

        assertSame(first, second)
        assertEquals(first.size, first.distinct().size)
        assertTrue(first.all { id -> discoverSuggestionLibrary.any { it.id == id } })
    }

    @Test
    fun typeAwareSelectionNeverMixesMoviesAndSeries() {
        val order = createSessionSuggestionOrder(Random(4))
        val movies = suggestionsForSession(order, RecommendationMediaKind.MOVIE)
        val series = suggestionsForSession(order, RecommendationMediaKind.SERIES)

        assertTrue(movies.isNotEmpty())
        assertTrue(series.isNotEmpty())
        assertTrue(movies.all { it.mediaKind == RecommendationMediaKind.MOVIE })
        assertTrue(series.all { it.mediaKind == RecommendationMediaKind.SERIES })
        assertEquals(movies.size, movies.map { it.prompt }.distinct().size)
        assertEquals(series.size, series.map { it.prompt }.distinct().size)
    }

    @Test
    fun scaryPromptUsesTheHard120MinuteRequest() {
        val prompts = discoverSuggestionLibrary.map(DiscoverSuggestion::prompt)

        assertTrue("Something scary under 120 minutes" in prompts)
        assertFalse(prompts.any { it.contains("under 100 minutes", ignoreCase = true) })
    }
}
