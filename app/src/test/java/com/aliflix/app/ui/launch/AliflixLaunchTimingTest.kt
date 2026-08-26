package com.aliflix.app.ui.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliflixLaunchTimingTest {

    @Test
    fun testWordCycleTimingAndSlotDuration() {
        val word0BeforeStart = calculateWordState(seq = 1.0f, wordIndex = 0, isReducedMotion = false)
        assertEquals(0f, word0BeforeStart.first, 0.001f)

        // Word 0 (MOVIES) starts at 1.15s
        // At 1.15s + 0.18 * 1.35s = 1.393s -> fade in completes
        val word0MidFadeIn = calculateWordState(seq = 1.25f, wordIndex = 0, isReducedMotion = false)
        assertTrue("Word 0 should be fading in", word0MidFadeIn.first > 0f && word0MidFadeIn.first < 1f)

        val word0Hold = calculateWordState(seq = 1.80f, wordIndex = 0, isReducedMotion = false)
        assertEquals("Word 0 should have full opacity during readable hold", 1f, word0Hold.first, 0.001f)
        assertEquals("Word 0 should have 0 translation Y during hold", 0f, word0Hold.second, 0.001f)

        // At 1.15s + 1.35s = 2.50s, Word 0 is completely faded out and Word 1 (SERIES) starts
        val word0AtSlotEnd = calculateWordState(seq = 2.50f, wordIndex = 0, isReducedMotion = false)
        assertEquals("Word 0 should be 0 at end of slot", 0f, word0AtSlotEnd.first, 0.001f)

        // Word 1 (SERIES) during hold
        val word1Hold = calculateWordState(seq = 3.15f, wordIndex = 1, isReducedMotion = false)
        assertEquals("Word 1 should have full opacity during readable hold", 1f, word1Hold.first, 0.001f)

        // Word 2 (STORIES) during hold
        val word2Hold = calculateWordState(seq = 4.50f, wordIndex = 2, isReducedMotion = false)
        assertEquals("Word 2 should have full opacity during readable hold", 1f, word2Hold.first, 0.001f)
    }

    @Test
    fun testZeroWordOverlapGuarantee() {
        // Sample at 0.05s increments from 1.15s to 6.0s
        var t = 1.15f
        while (t <= 6.0f) {
            val opacities = (0..2).map { idx ->
                calculateWordState(seq = t, wordIndex = idx, isReducedMotion = false).first
            }
            val visibleWordsCount = opacities.count { it > 0f }
            assertTrue("At most 1 word should be visible at any instant (seq=$t)", visibleWordsCount <= 1)
            t += 0.05f
        }
    }

    @Test
    fun testReducedMotionShowsFirstWordImmediately() {
        val word0 = calculateWordState(seq = 0.5f, wordIndex = 0, isReducedMotion = true)
        val word1 = calculateWordState(seq = 0.5f, wordIndex = 1, isReducedMotion = true)
        val word2 = calculateWordState(seq = 0.5f, wordIndex = 2, isReducedMotion = true)

        assertEquals(1f, word0.first, 0.001f)
        assertEquals(0f, word0.second, 0.001f)
        assertEquals(0f, word1.first, 0.001f)
        assertEquals(0f, word2.first, 0.001f)
    }
}
