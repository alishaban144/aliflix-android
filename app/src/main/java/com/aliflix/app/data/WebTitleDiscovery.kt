package com.aliflix.app.data

import kotlinx.coroutines.CancellationException

internal enum class WebDiscoveryMode {
    DESCRIBE_PLOT,
    RECOMMENDATION,
}

internal data class WebDiscoveryResult<T>(
    val items: List<T>,
    val successfulSources: Int,
)

/**
 * Deterministic source coordinator shared by Describe Plot and Aliflix AI.
 *
 * Provider output is untrusted discovery evidence only. Callers must still resolve every title
 * through the native catalogue before it can become user-visible.
 */
internal class WebTitleDiscovery<T>(
    private val brave: suspend () -> List<T>,
    private val wikipedia: suspend () -> List<T>,
    private val duckDuckGo: suspend () -> List<T>,
    private val keyOf: (T) -> String,
    private val merge: (T, T) -> T,
    private val sortScore: (T) -> Double,
) {
    suspend fun discover(mode: WebDiscoveryMode): WebDiscoveryResult<T> {
        var successfulSources = 0
        val discovered = linkedMapOf<String, T>()

        suspend fun collect(source: suspend () -> List<T>) {
            try {
                source().forEach { item ->
                    val key = keyOf(item)
                    discovered[key] = discovered[key]?.let { merge(it, item) } ?: item
                }
                successfulSources += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A failed source is expected to fall through to the next public source.
            }
        }

        collect(brave)
        when (mode) {
            WebDiscoveryMode.DESCRIBE_PLOT -> {
                collect(wikipedia)
                collect(duckDuckGo)
            }
            WebDiscoveryMode.RECOMMENDATION -> {
                if (discovered.size < WIKIPEDIA_THRESHOLD) collect(wikipedia)
                if (discovered.size < DUCKDUCKGO_THRESHOLD) collect(duckDuckGo)
            }
        }
        return WebDiscoveryResult(
            items = discovered.values.sortedByDescending(sortScore),
            successfulSources = successfulSources,
        )
    }

    private companion object {
        const val WIKIPEDIA_THRESHOLD = 18
        const val DUCKDUCKGO_THRESHOLD = 12
    }
}
