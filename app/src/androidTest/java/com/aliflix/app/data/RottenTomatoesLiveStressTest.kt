package com.aliflix.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RottenTomatoesLiveStressTest {
    @Test fun twentyEstablishedTitlesResolveOnAndroid() = runBlocking {
        val titles = listOf(
            media("The Godfather", MediaType.MOVIE, "1972"), media("The Dark Knight", MediaType.MOVIE, "2008"),
            media("Inception", MediaType.MOVIE, "2010"), media("Pulp Fiction", MediaType.MOVIE, "1994"),
            media("The Matrix", MediaType.MOVIE, "1999"), media("Parasite", MediaType.MOVIE, "2019"),
            media("Get Out", MediaType.MOVIE, "2017"), media("Alien", MediaType.MOVIE, "1979"),
            media("The Shining", MediaType.MOVIE, "1980"), media("Toy Story", MediaType.MOVIE, "1995"),
            media("Breaking Bad", MediaType.TV, "2008"), media("Better Call Saul", MediaType.TV, "2015"),
            media("The Sopranos", MediaType.TV, "1999"), media("Chernobyl", MediaType.TV, "2019"),
            media("The Last of Us", MediaType.TV, "2023"), media("Stranger Things", MediaType.TV, "2016"),
            media("Severance", MediaType.TV, "2022"), media("The Bear", MediaType.TV, "2022"),
            media("Dark", MediaType.TV, "2017"), media("Game of Thrones", MediaType.TV, "2011"),
        )
        val latencies = mutableListOf<Long>()
        val diagnostics = mutableListOf<RtFetchDiagnostic>()
        val client = RottenTomatoesClient(AndroidRottenTomatoesTransport(), diagnostics::add)
        var successes = 0
        titles.forEach { item ->
            diagnostics.clear()
            val started = System.currentTimeMillis()
            val result = client.loadFetchResult(item)
            val total = System.currentTimeMillis() - started
            if (result is RottenTomatoesFetchResult.Verified) { successes++; latencies += total }
            val final = when (result) {
                is RottenTomatoesFetchResult.Verified -> diagnostics.lastOrNull { it.finalState == RatingSourceState.VERIFIED && it.ratingParsed == result.rating }
                RottenTomatoesFetchResult.ConfirmedNotRated -> diagnostics.lastOrNull { it.finalState == RatingSourceState.NOT_RATED }
                is RottenTomatoesFetchResult.Unavailable -> diagnostics.lastOrNull { it.failureReason == result.reason }
            } ?: diagnostics.lastOrNull()
            println("RT_STRESS title=${item.title} type=${item.type} requested=${final?.requestedUrl} http=${final?.statusCode} final=${final?.finalUrl} networkMs=${final?.networkMs} identity=${final?.identityVerified} rating=${(result as? RottenTomatoesFetchResult.Verified)?.rating} state=${final?.finalState} totalMs=$total failure=${final?.failureReason}")
            assertTrue("${item.title} remained loading", final?.finalState != RatingSourceState.LOADING)
            assertTrue("${item.title} exceeded deadline: $total", total < 4_500)
        }
        val sorted = latencies.sorted()
        val median = percentile(sorted, .50)
        val p90 = percentile(sorted, .90)
        val p95 = percentile(sorted, .95)
        println("RT_STRESS_SUMMARY successes=$successes total=${titles.size} percentage=${successes * 100.0 / titles.size} medianMs=$median p90Ms=$p90 p95Ms=$p95")
        assertTrue("Only $successes/20 RT ratings resolved", successes >= 18)
        assertTrue("Median cold latency was ${median}ms", median < 2_500)
    }

    private fun media(title: String, type: MediaType, year: String) = Media(title.hashCode(), type, title, year = year)
    private fun percentile(values: List<Long>, fraction: Double): Long = values[((values.size - 1) * fraction).toInt().coerceAtLeast(0)]
}
