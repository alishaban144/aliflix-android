package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SubtitleSyncPayloadTest {
    private val originals = listOf(SubtitleCue(.2, 1.2, "First phrase"), SubtitleCue(20.0, 22.0, "Later phrase"))

    @Test fun wrongManualDelayIsCompensatedWithoutLosingEarlyCuesAndResetRestoresIt() {
        for (delay in listOf(-9.0, 9.0)) {
            val verified = AudioSubtitleCorrection(0.0, 1.0, .9)
            val stored = requireNotNull(verified.compensatingManualDelay(delay))
            val actual = parseTimedTextSubtitleCues(correctedMobileVtt(originals, stored, delay))
            assertEquals(originals.size, actual.size)
            actual.zip(originals).forEach { (a,b) ->
                assertEquals(b.text,a.text)
                assertEquals(b.startSeconds,a.startSeconds,.002)
                assertEquals(b.endSeconds,a.endSeconds,.002)
            }
            val reset = parseTimedTextSubtitleCues(correctedMobileVtt(originals, null, delay))
            assertEquals(22.0 + delay, reset.last().endSeconds, .001)
            val adjusted = parseTimedTextSubtitleCues(correctedMobileVtt(originals, stored, delay + .5))
            assertEquals(20.5, adjusted.last().startSeconds, .001)
        }
    }
    @Test fun compensationPreservesVerifiedDriftAndEveryEditRegion() {
        val verified = AudioSubtitleCorrection(3.0, 25.0 / 24, .9, listOf(TimingRegion(0,3.0), TimingRegion(1,8.0)), "piecewise")
        val stored = requireNotNull(verified.compensatingManualDelay(7.0))
        val actual = parseTimedTextSubtitleCues(correctedMobileVtt(originals, stored, 7.0))
        val expected = verified.apply(originals)
        actual.zip(expected).forEach { (a,b) -> assertEquals(b.startSeconds,a.startSeconds,.001); assertEquals(b.endSeconds,a.endSeconds,.001) }
        assertEquals(verified.model, stored.model)
        assertEquals(verified.rate, stored.rate, 0.0)
        assertNull(verified.compensatingManualDelay(Double.NaN))
        assertNull(AudioSubtitleCorrection(599.0,1.0,.9).compensatingManualDelay(-9.0))
    }
}
