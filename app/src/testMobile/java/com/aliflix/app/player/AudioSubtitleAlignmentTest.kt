package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class AudioSubtitleAlignmentTest {
    private fun cues(): List<SubtitleCue> {
        val random = Random(981)
        var time = 0.0
        return List(220) {
            time += .8 + random.nextDouble() * 3.5
            val start = time
            time += .4 + random.nextDouble() * 2.8
            SubtitleCue(start, time, "Dialogue / حوار")
        }
    }
    private fun current(cues: List<SubtitleCue>, offset: Double, rate: Double = 1.0, padding: Boolean = false): SpeechWindow {
        val random = Random(721)
        return SpeechWindow(420.0, DoubleArray(600) { frame ->
            val time = (420.0 + frame / 50.0 - offset) / rate
            val spoken = cues.any { time >= it.startSeconds + (if (padding) .12 else 0.0) && time < it.endSeconds - (if (padding) .18 else 0.0) }
            if (spoken && (!padding || random.nextDouble() > .07)) 1.0 else 0.0
        })
    }
    @Test fun fftMatchesDirectConvolution() {
        assertArrayEquals(doubleArrayOf(4.0, 13.0, 22.0, 15.0),
            AudioSubtitleAlignment.convolution(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0)), 1e-8)
    }
    @Test fun oneCurrentExchangeFindsPositiveAndNegativeOffsetsWithoutOtherScenes() {
        val original = cues()
        for (offset in listOf(-7.2, 0.0, 9.4)) {
            val match = AudioSubtitleAlignment.matchCurrent(original, current(original, offset))
            assertNotNull("Current exchange should identify offset $offset", match)
            assertEquals(offset, match!!.offset, .08)
            assertEquals(1.0, match.rate, .00001)
            assertNotNull(AudioSubtitleAlignment.matchCurrent(original.map { it.copy(text = "Un autre dialogue") }, current(original, offset)))
        }
    }
    @Test fun paddedSubtitlesAndVadDropoutsMatchCurrentDialogue() {
        val original = cues()
        val match = AudioSubtitleAlignment.matchCurrent(original, current(original, 6.8, padding = true))
        assertNotNull(match)
        assertEquals(6.8, match!!.offset, .2)
    }
    @Test fun manualDelayAndExistingVerifiedRateRemainSeparateFromNewOffset() {
        val original = cues()
        val rate = 25.0 / 24.0
        val match = AudioSubtitleAlignment.matchCurrent(original, current(original, 11.0, rate), rate, manualDelay = 4.0)
        assertNotNull(match)
        assertEquals(7.0, match!!.offset, .08)
        assertEquals(rate, match.rate, 1e-8)
        assertEquals(original.first().startSeconds * rate + 11, match.apply(original, 4.0).first().startSeconds, .08)
    }
    @Test fun ambiguousRepeatedDialogueContinuousSpeechAndSilenceFailImmediately() {
        val repeated = List(400) { SubtitleCue(it * 3.0, it * 3.0 + 1.0, "dialogue") }
        assertNull(AudioSubtitleAlignment.matchCurrent(repeated, current(repeated, 2.0)))
        assertNull(AudioSubtitleAlignment.matchCurrent(cues(), SpeechWindow(420.0, DoubleArray(600))))
        assertNull(AudioSubtitleAlignment.matchCurrent(cues(), SpeechWindow(420.0, DoubleArray(600) { 1.0 })))
    }
    @Test fun invalidOrTooShortInputCannotProduceACorrection() {
        val original = cues()
        assertNull(AudioSubtitleAlignment.matchCurrent(original, SpeechWindow(420.0, DoubleArray(299) { (it % 2).toDouble() })))
        assertNull(AudioSubtitleAlignment.matchCurrent(original, SpeechWindow(Double.NaN, DoubleArray(600))))
        assertNull(AudioSubtitleAlignment.matchCurrent(original, SpeechWindow(420.0, DoubleArray(600) { Double.NaN })))
        assertNull(AudioSubtitleAlignment.matchCurrent(original, current(original, 4.0), rate = 2.0))
    }
    @Test fun fftAnalysisCooperatesWithCancellation() {
        var checks = 0
        try {
            AudioSubtitleAlignment.matchCurrent(cues(), current(cues(), 4.0)) {
                if (++checks > 225) throw java.util.concurrent.CancellationException("cancelled")
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
