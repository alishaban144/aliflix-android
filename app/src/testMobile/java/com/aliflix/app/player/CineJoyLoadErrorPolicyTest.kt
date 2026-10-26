package com.aliflix.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CineJoyLoadErrorPolicyTest {
    private fun error(code: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val spec = DataSpec(Uri.parse("https://cdn.test/video_720p.m3u8"))
        return LoadErrorHandlingPolicy.LoadErrorInfo(LoadEventInfo(1, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA),
            HttpDataSource.InvalidResponseCodeException(code, "failure", null, emptyMap(), spec, byteArrayOf()), 1)
    }

    @Test fun gatewayFailuresPermitAnotherVideoQualityAfterFreshFragmentRetries() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 3, 0)
        for (code in listOf(502, 504)) {
            val fallback = checkNotNull(CineJoyLoadErrorPolicy().getFallbackSelectionFor(options, error(code)))
            assertEquals(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, fallback.type)
            assertTrue(fallback.exclusionDurationMs > 0)
        }
    }

    @Test fun singleAudioRenditionCannotFallBackToAnotherLanguage() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 1, 0)
        assertNull(CineJoyLoadErrorPolicy().getFallbackSelectionFor(options, error(502)))
    }

    @Test fun nonCineJoyPolicyAndUnrelatedHttpErrorsRetainDefaultBehavior() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 3, 0)
        assertNull(CineJoyLoadErrorPolicy { false }.getFallbackSelectionFor(options, error(502)))
        assertNull(CineJoyLoadErrorPolicy().getFallbackSelectionFor(options, error(401)))
    }
}
