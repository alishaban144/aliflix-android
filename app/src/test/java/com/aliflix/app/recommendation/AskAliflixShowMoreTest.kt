package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AskAliflixShowMoreTest {
    @Test
    fun freshBatchUsesSelectedModelAndExcludesEveryDisplayedResult() {
        val original = V3RecommendationRequest(
            requestId = "00000000-0000-4000-8000-000000000001",
            mode = "describe",
            query = "space adventure",
            mediaType = "movie",
            filters = V3RecommendationFilters(
                excludedTmdbIds = listOf(99),
                excludedTitles = listOf("Previously excluded"),
            ),
        )
        val displayed = listOf(
            RecommendationCandidate(Media(id = 11, type = MediaType.MOVIE, title = "First Match")),
            RecommendationCandidate(Media(id = 12, type = MediaType.MOVIE, title = "Second Match")),
        )

        val next = buildAskAliflixShowMoreRequest(
            original = original,
            displayed = displayed,
            selectedModel = GeminiRecommendationModel.GEMINI_3_7_FLASH,
            requestId = "00000000-0000-4000-8000-000000000002",
        )

        assertEquals("00000000-0000-4000-8000-000000000002", next.requestId)
        assertEquals("gemini-3.7-flash", next.geminiModel)
        assertEquals(listOf(99, 11, 12), next.filters.excludedTmdbIds)
        assertEquals(listOf("Previously excluded", "First Match", "Second Match"), next.filters.excludedTitles)
        assertNull(next.cursor)
    }

    @Test
    fun filterPaginationCannotAccidentallyBecomeAGeminiGeneration() {
        val request = V3RecommendationRequest(
            requestId = "00000000-0000-4000-8000-000000000003",
            mode = "filters",
            query = "",
            mediaType = "tv",
        )

        assertThrows(IllegalArgumentException::class.java) {
            buildAskAliflixShowMoreRequest(
                original = request,
                displayed = emptyList(),
                selectedModel = GeminiRecommendationModel.GEMINI_3_5_FLASH,
            )
        }
    }
}
