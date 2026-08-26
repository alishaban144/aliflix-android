package com.aliflix.app.ui.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliflixLaunchTimingTest {

    @Test
    fun testWordCycleTimingAndSlotDuration() {
        val word0BeforeStart = calculateWordState(seq = 0.8f, wordIndex = 0, isReducedMotion = false)
        assertEquals(0f, word0BeforeStart.first, 0.001f)

        assertEquals(0.7f, LAUNCH_WORD_SLOT_SECONDS, 0.001f)

        val word0MidFadeIn = calculateWordState(seq = 1.02f, wordIndex = 0, isReducedMotion = false)
        assertTrue("Word 0 should be fading in", word0MidFadeIn.first > 0f && word0MidFadeIn.first < 1f)

        val word0Hold = calculateWordState(seq = 1.25f, wordIndex = 0, isReducedMotion = false)
        assertEquals("Word 0 should have full opacity during readable hold", 1f, word0Hold.first, 0.001f)
        assertEquals("Word 0 should have 0 translation Y during hold", 0f, word0Hold.second, 0.001f)

        val word0AtSlotEnd = calculateWordState(seq = 1.65f, wordIndex = 0, isReducedMotion = false)
        assertEquals("Word 0 should be 0 at end of slot", 0f, word0AtSlotEnd.first, 0.001f)

        // Word 1 (SERIES) during hold
        val word1Hold = calculateWordState(seq = 1.95f, wordIndex = 1, isReducedMotion = false)
        assertEquals("Word 1 should have full opacity during readable hold", 1f, word1Hold.first, 0.001f)

        // Word 2 (STORIES) during hold
        val word2Hold = calculateWordState(seq = 2.65f, wordIndex = 2, isReducedMotion = false)
        assertEquals("Word 2 should have full opacity during readable hold", 1f, word2Hold.first, 0.001f)

        val word2WhileWaitingForHome = calculateWordState(seq = 8f, wordIndex = 2, isReducedMotion = false)
        assertEquals("STORIES should remain visible while Home is still loading", 1f, word2WhileWaitingForHome.first, 0.001f)
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

    @Test
    fun testLaunchCannotExitBeforeEveryWordHasHadItsSlot() {
        val sequenceEnd = LAUNCH_WORD_SEQUENCE_START_SECONDS +
            LAUNCH_WORD_SLOT_SECONDS * LAUNCH_WORD_COUNT

        assertTrue(!isLaunchSequenceComplete(sequenceEnd - 0.01f, isReducedMotion = false))
        assertTrue(isLaunchSequenceComplete(sequenceEnd, isReducedMotion = false))
        assertTrue(!isLaunchExitReady(isHomeReady = true, elapsedSeconds = sequenceEnd - 0.01f, isReducedMotion = false))
        assertTrue(!isLaunchExitReady(isHomeReady = false, elapsedSeconds = sequenceEnd, isReducedMotion = false))
        assertTrue(isLaunchExitReady(isHomeReady = true, elapsedSeconds = sequenceEnd, isReducedMotion = false))
        assertTrue(!isLaunchSequenceComplete(0.64f, isReducedMotion = true))
        assertTrue(isLaunchSequenceComplete(0.65f, isReducedMotion = true))
    }

    @Test
    fun testPostStoriesExitFadeCompletesInThreeTenthsOfASecond() {
        assertEquals(0.3f, LAUNCH_EXIT_DURATION_SECONDS, 0.001f)
        assertEquals(0f, calculateLaunchExitProgress(elapsedSeconds = 4f, exitStartSeconds = -1f), 0.001f)
        assertEquals(0.5f, calculateLaunchExitProgress(elapsedSeconds = 4.15f, exitStartSeconds = 4f), 0.001f)
        assertEquals(1f, calculateLaunchExitProgress(elapsedSeconds = 4.3f, exitStartSeconds = 4f), 0.001f)
    }
}
