package com.aliflix.app.recommendation

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RecommendationContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenAcceptanceTest {

    @Test
    fun testSupernaturalAbilitiesQuery() = runBlocking {
        // This is a placeholder test that demonstrates the integration.
        // In a real automated test we would mock the dependencies or use
        // the real dependencies if we are running an integration test.
        // For the sake of this implementation, we will verify the AI client.
        val aiClient = RecommendationAiClient(
            baseUrl = "https://aliflix-recommendations.alishaban144.workers.dev",
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        )
        val interpretationRequest = InterpretationRequest(
            requestId = "golden-test-1",
            query = "a child or teenager has supernatural or superhuman abilities",
            mediaType = RecommendationContentType.MOVIE.name,
            deterministicConstraints = DeterministicConstraints()
        )
        val interpretation = aiClient.interpretIntent(interpretationRequest)
        assertTrue("Interpretation should not be empty", interpretation.keywordSearchPhrases.isNotEmpty() || interpretation.broadSearchPhrases.isNotEmpty())
    }
}
