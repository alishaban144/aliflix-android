package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class AudioSubtitleAlignmentTest {
    @Test fun externalSubtitleMergeDoesNotChangeSelectedAudioIdentity() {
        val original = androidx.media3.common.Format.Builder().setId("7").setLanguage("ja").setSampleRate(48000).setChannelCount(2).build()
        assertEquals(audioSyncFingerprint(original), audioSyncFingerprint(original.buildUpon().setId("0:7").build(), true))
        assertNotEquals(audioSyncFingerprint(original), audioSyncFingerprint(original.buildUpon().setId("0:7").build()))
    }
    @Test fun fftMatchesDirectConvolution() {
        assertArrayEquals(doubleArrayOf(4.0, 13.0, 22.0, 15.0),
            AudioSubtitleAlignment.convolution(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0)), 1e-8)
    }
    @Test fun fftAnalysisCooperatesWithCancellation() {
        var checks = 0
        try {
            AudioSubtitleAlignment.convolution(DoubleArray(10000), DoubleArray(10000)) {
                if (++checks > 12) throw java.util.concurrent.CancellationException("cancelled")
            }
            fail("Cancellation must leave analysis without a correction")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun correctionsAlwaysUseOriginalsAndKeepManualDelaySeparate() {
        val original = listOf(SubtitleCue(10.0, 12.0, "original"))
        val correction = AudioSubtitleCorrection(3.0, 25.0 / 24.0, .9)
        val first = correction.apply(original, -.4)
        assertEquals(first, correction.apply(original, -.4))
        assertEquals(10.0 * 25 / 24 + 3 - .4, first.single().startSeconds, 1e-8)
        assertEquals(10.0, original.single().startSeconds, 0.0)
    }
    @Test fun fingerprintsSeparateSourceSubtitleAndAudioTrack() {
        val request = NativePlaybackRequest("https://example.com/a.mp4?v=1", "video/mp4", "https://example.com", "ua", "", "title", 0, true)
        val key = subtitleCorrectionKey(request, "tv:1:s1:e1", "original", "track-en")
        assertNotEquals(key, subtitleCorrectionKey(request.copy(url = "https://example.com/a.mp4?v=2"), "tv:1:s1:e1", "original", "track-en"))
        assertNotEquals(key, subtitleCorrectionKey(request, "tv:1:s1:e1", "different", "track-en"))
        assertNotEquals(key, subtitleCorrectionKey(request, "tv:1:s1:e1", "original", "track-fr"))
    }
}
