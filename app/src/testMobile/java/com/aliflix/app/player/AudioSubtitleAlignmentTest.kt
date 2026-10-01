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
            SubtitleCue(start, time, "حوار / dialogue")
        }
    }
    private fun windows(cues: List<SubtitleCue>, offset: Double, rate: Double = 1.0) = listOf(130.0, 420.0, 660.0).map { start ->
        SpeechWindow(start, DoubleArray(1250) { frame ->
            val subtitleTime = (start + frame / 50.0 - offset) / rate
            if (cues.any { subtitleTime >= it.startSeconds && subtitleTime < it.endSeconds }) 1.0 else 0.0
        })
    }
    @Test fun fftMatchesDirectConvolution() {
        assertArrayEquals(doubleArrayOf(4.0, 13.0, 22.0, 15.0),
            AudioSubtitleAlignment.convolution(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0)), 1e-8)
    }
    @Test fun positiveAndNegativeOffsetsAreLanguageIndependent() {
        val original = cues()
        for (offset in listOf(-7.2, 0.0, 9.4)) {
            val match = AudioSubtitleAlignment.match(original, windows(original, offset))!!
            assertEquals(offset, match.offset, .04)
            assertEquals(1.0, match.rate, .00001)
            assertNotNull(AudioSubtitleAlignment.match(original.map { it.copy(text = "Un autre dialogue") }, windows(original, offset)))
        }
    }
    @Test fun detectsSupportedFrameRateDrift() {
        val original = cues()
        val match = AudioSubtitleAlignment.match(original, windows(original, -3.0, 25.0 / 24.0))!!
        assertEquals(25.0 / 24.0, match.rate, 1e-8)
        assertEquals(-3.0, match.offset, .06)
    }
    @Test fun twentySecondsOfIndependentDialogueCanMatchWithSubtitlePaddingAndVadDropouts() {
        val original = cues()
        val random = Random(721)
        val samples = listOf(130.0, 140.0).map { start ->
            SpeechWindow(start, DoubleArray(500) { frame ->
                val time = start + frame / 50.0 - 6.8
                val spoken = original.any { time >= it.startSeconds + .12 && time < it.endSeconds - .18 }
                if (spoken && random.nextDouble() > .07) 1.0 else 0.0
            })
        }
        val match = AudioSubtitleAlignment.match(original, samples)
        assertNotNull("Padded dialogue with minor VAD dropouts should match without a minute-long collection", match)
        assertEquals(6.8, match!!.offset, .2)
        assertEquals(1.0, match.rate, .000001)
    }
    @Test fun overlappingSamplesCannotFakeIndependentEvidence() {
        val original = cues()
        val samples = windows(original, 4.0)
        assertNull(AudioSubtitleAlignment.match(original, listOf(samples[0], samples[0])))
    }
    @Test fun aWeakIntroCannotPoisonThreeLaterScenesButStrongConflictsAreRejected() {
        val original = cues()
        val random = Random(435)
        val weak = SpeechWindow(75.0, DoubleArray(1250) { if (random.nextDouble() > .6) 1.0 else 0.0 })
        val samples = listOf(weak) + windows(original, 4.0)
        val match = AudioSubtitleAlignment.match(original, samples)
        assertNotNull(match)
        assertEquals(4.0, match!!.offset, .04)
        // Give the conflicting scene an exact but different timeline shift.
        val strong = SpeechWindow(75.0, DoubleArray(1250) { frame ->
            val time = 75.0 + frame / 50.0 - 18.0
            if (original.any { time >= it.startSeconds && time < it.endSeconds }) 1.0 else 0.0
        })
        assertNull(AudioSubtitleAlignment.match(original, listOf(strong) + windows(original, 4.0)))
    }
    @Test fun shortBaselineDriftCannotMasqueradeAsConstantOffset() {
        val original = cues()
        val rate = 25.0 / 24.0
        val samples = listOf(130.0, 165.0).map { start ->
            SpeechWindow(start, DoubleArray(1250) { frame ->
                val time = (start + frame / 50.0 + 3) / rate
                if (original.any { time >= it.startSeconds && time < it.endSeconds }) 1.0 else 0.0
            })
        }
        assertNull(AudioSubtitleAlignment.match(original, samples))
        assertNull(AudioSubtitleAlignment.match(original, windows(original, 0.0, 1.08)))
    }
    @Test fun silenceOneSceneAndConflictingScenesAreRejected() {
        val original = cues()
        assertNull(AudioSubtitleAlignment.match(original, windows(original, 4.0).take(1)))
        assertNull(AudioSubtitleAlignment.match(original, windows(original, 4.0).map { it.copy(speech = DoubleArray(1250)) }))
        val a = windows(original, 4.0); val b = windows(original, 18.0)
        assertNull(AudioSubtitleAlignment.match(original, listOf(a[0], b[1], a[2])))
    }
    @Test fun periodicAmbiguousDialogueIsRejected() {
        val original = List(400) { SubtitleCue(it * 3.0, it * 3.0 + 1.0, "dialogue") }
        assertNull(AudioSubtitleAlignment.match(original, windows(original, 2.0)))
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
