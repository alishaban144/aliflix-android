package com.aliflix.app.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AskAliflixShowMoreTest {
    @Test
    fun showMoreRequestsFreshServerContinuationThroughTheSignedCursor() {
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
        val next = buildAskAliflixShowMoreRequest(
            original = original,
            nextCursor = "signed-next-page",
        )

        assertEquals(original.requestId, next.requestId)
        assertEquals(original.aiModel, next.aiModel)
        assertEquals(original.filters, next.filters)
        assertEquals("signed-next-page", next.cursor)
    }

    @Test
    fun blankCursorCannotStartAReplacementAiGeneration() {
        val request = V3RecommendationRequest(
            requestId = "00000000-0000-4000-8000-000000000003",
            mode = "filters",
            query = "",
            mediaType = "tv",
        )

        assertThrows(IllegalArgumentException::class.java) {
            buildAskAliflixShowMoreRequest(
                original = request,
                nextCursor = "",
            )
        }
    }
}
