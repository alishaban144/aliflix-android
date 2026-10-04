package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/** Retry a transient fragment failure where it occurred, retaining the audio and seek target. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CineJoyLoadErrorPolicy(private val enabled: () -> Boolean = { true }) : DefaultLoadErrorHandlingPolicy() {
    // HLS video qualities share a track selection; alternate languages use separate
    // selections. This permits another quality of the same stream, never another audio.
    override fun isEligibleForFallback(exception: IOException): Boolean =
        enabled() && (exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode in listOf(502, 504) ||
            super.isEligibleForFallback(exception)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (enabled()) 6 else super.getMinimumLoadableRetryCount(dataType)

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (!enabled()) return super.getRetryDelayMsFor(info)
        val response = info.exception as? HttpDataSource.InvalidResponseCodeException
        if (response != null && response.responseCode !in listOf(408, 429, 500, 502, 503, 504))
            return super.getRetryDelayMsFor(info)
        if (info.errorCount > 6) return C.TIME_UNSET
        if (super.getRetryDelayMsFor(info) == C.TIME_UNSET) return C.TIME_UNSET
        return (250L shl (info.errorCount - 1).coerceIn(0, 4)).coerceAtMost(3_000)
    }
}
