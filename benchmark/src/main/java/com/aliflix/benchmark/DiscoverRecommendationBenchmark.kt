package com.aliflix.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class DiscoverRecommendationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Measures the real, release-optimized Discover path. The query deliberately
     * contains one unsupported hard preference so the clarification interaction
     * is deterministic instead of depending on whichever optional question the
     * recommender happens to choose.
     */
    @Test
    fun discoverRecommendationAndNextPage() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            FrameTimingMetric(),
        ),
        compilationMode = CompilationMode.Partial(warmupIterations = 1),
        startupMode = StartupMode.COLD,
        iterations = 3,
        setupBlock = {
            pressHome()
        },
    ) {
        startActivityAndWait()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        device.requireTag(TAG_DISCOVER_TAB).click()
        device.requireTag(TAG_SEARCH_FIELD)
        // Explicit tab selection opens the IME. Dismiss it before changing the
        // request type; the field must remain normally dismissible.
        device.pressBack()

        device.requireTag(TAG_RECOMMEND_MODE).click()
        device.requireTag(TAG_SERIES_TYPE).click()
        device.requireTag(TAG_SEARCH_FIELD).apply {
            click()
            text = QUERY
        }
        device.requireTag(TAG_SUBMIT).click()

        val clarification = device.requireAnyTag(
            tags = listOf(
                TAG_QUESTION,
                TAG_RESULTS,
                TAG_LOW_CONFIDENCE,
                TAG_ERROR,
                TAG_SOURCE_ERROR,
            ),
            timeoutMs = INITIAL_RESULT_TIMEOUT_MS,
        )
        if (clarification.tag != TAG_QUESTION) {
            fail(
                "Expected the deterministic unsupported-preference clarification, " +
                    "but Discover entered ${clarification.tag}.",
            )
        }

        device.requireTag(TAG_PREFERENCE).click()
        device.requireTag(TAG_QUESTION_CONTINUE).click()

        val initial = device.requireAnyTag(
            tags = listOf(
                TAG_RESULTS,
                TAG_LOW_CONFIDENCE,
                TAG_ERROR,
                TAG_SOURCE_ERROR,
            ),
            timeoutMs = INITIAL_RESULT_TIMEOUT_MS,
        )
        if (initial.tag != TAG_RESULTS) {
            fail("Recommendation did not produce usable initial results: ${initial.tag}")
        }

        // Repeated swipes intentionally exercise the list's near-end backpressure.
        // Seeing the append indicator proves that a later ranked page, rather than
        // merely another viewport, was requested.
        var appendStarted = false
        for (attempt in 0 until MAX_PAGE_SWIPES) {
            val results = device.requireTag(TAG_RESULTS_LIST, timeoutMs = 5_000)
            results.swipe(Direction.UP, 0.82f)
            if (device.findTag(TAG_LOAD_MORE) != null) {
                appendStarted = true
                break
            }
        }
        if (!appendStarted) {
            fail("Scrolling never started a later recommendation page")
        }

        device.waitUntilTagGone(TAG_LOAD_MORE, PAGE_RESULT_TIMEOUT_MS)
        val afterAppend = device.requireAnyTag(
            tags = listOf(TAG_RESULTS, TAG_PAGE_ERROR),
            timeoutMs = PAGE_RESULT_TIMEOUT_MS,
        )
        // A later-page failure is an allowed partial result: the successful initial
        // results must stay on-screen and interactive.
        device.requireTag(TAG_RESULTS)
        if (afterAppend.tag == TAG_PAGE_ERROR) {
            device.requireTag(TAG_RESULTS_LIST)
        }
    }

    private data class TaggedObject(
        val tag: String,
        val node: UiObject2,
    )

    private fun UiDevice.findTag(tag: String): UiObject2? =
        findObject(By.res(tag)) ?: findObject(By.res(TARGET_PACKAGE, tag))

    private fun UiDevice.requireTag(
        tag: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): UiObject2 = requireAnyTag(listOf(tag), timeoutMs).node

    private fun UiDevice.requireAnyTag(
        tags: List<String>,
        timeoutMs: Long,
    ): TaggedObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            tags.forEach { tag ->
                findTag(tag)?.let { return TaggedObject(tag, it) }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out waiting for one of ${tags.joinToString()}")
        error("unreachable")
    }

    private fun UiDevice.waitUntilTagGone(tag: String, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (findTag(tag) != null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        if (findTag(tag) != null) fail("$tag remained visible after $timeoutMs ms")
    }

    private companion object {
        const val TARGET_PACKAGE = "com.aliflix.app"
        const val QUERY = "Series similar to Breaking Bad with subtitles required"

        const val TAG_DISCOVER_TAB = "bottom-tab-discover"
        const val TAG_SEARCH_FIELD = "discover-search-field"
        const val TAG_RECOMMEND_MODE = "discover-mode-recommend"
        const val TAG_SERIES_TYPE = "discover-type-series"
        const val TAG_SUBMIT = "discover-recommend-submit"
        const val TAG_QUESTION = "discover-recommendation-question"
        const val TAG_PREFERENCE = "discover-preference-action"
        const val TAG_QUESTION_CONTINUE = "discover-question-continue"
        const val TAG_RESULTS = "discover-recommendation-results"
        const val TAG_RESULTS_LIST = "discover-results-list"
        const val TAG_LOAD_MORE = "discover-load-more"
        const val TAG_LOW_CONFIDENCE = "discover-recommendation-low-confidence"
        const val TAG_ERROR = "discover-recommendation-error"
        const val TAG_SOURCE_ERROR = "discover-recommendation-source-error"
        const val TAG_PAGE_ERROR = "discover-recommendation-page-error"

        const val UI_TIMEOUT_MS = 15_000L
        const val INITIAL_RESULT_TIMEOUT_MS = 60_000L
        const val PAGE_RESULT_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_PAGE_SWIPES = 40
    }
}
