package com.aliflix.app.ui.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliflixLaunchTimingTest {

    @Test
    fun brandAndWordsBeginTogetherWithoutExtendingLaunch() {
        assertEquals(LAUNCH_TITLE_START_SECONDS, LAUNCH_WORD_SEQUENCE_START_SECONDS, 0f)
        assertEquals(0f, LAUNCH_WORD_SEQUENCE_START_SECONDS, 0f)
        assertEquals(2.38f, LAUNCH_SEQUENCE_SECONDS, .0001f)
        assertTrue(LAUNCH_WORD_SLOT_SECONDS - LAUNCH_WORD_FADE_SECONDS >= .65f)
        assertTrue("Stories has a readable final hold",LAUNCH_FINAL_WORD_SLOT_SECONDS-LAUNCH_WORD_FADE_SECONDS >= .25f)
        assertTrue("Brand reveal lasts long enough to see", LAUNCH_BRAND_REVEAL_SECONDS >= .85f)
        assertTrue("Home remains bounded",LAUNCH_SEQUENCE_SECONDS+LAUNCH_EXIT_DURATION_SECONDS < 2.7f)
        var previous = 0f
        repeat(101) { index ->
            val progress = launchBrandProgress(index/100f,false)
            assertTrue(progress in previous..1f)
            previous = progress
        }
        assertEquals(0f,launchBrandProgress(0f,false),0f)
        assertTrue(launchBrandProgress(.42f,false) < 1f)
        assertEquals(1f,launchBrandProgress(LAUNCH_BRAND_REVEAL_SECONDS,false),0f)
        assertEquals(1f,launchBrandProgress(0f,true),0f)
    }

    @Test
    fun testWordmarkUsesTheRequestedSubtleTopAnchoredCurve() {
        assertEquals("ALIFLIX", ALIFLIX_WORDMARK)
        assertEquals(
            listOf(1.055f, 1.035f, 1.017f, 1f, 1.017f, 1.035f, 1.055f),
            ALIFLIX_WORDMARK_HEIGHT_SCALES,
        )
        ALIFLIX_WORDMARK_HEIGHT_SCALES.indices.forEach { index ->
            assertEquals(
                ALIFLIX_WORDMARK_HEIGHT_SCALES[index],
                ALIFLIX_WORDMARK_HEIGHT_SCALES[ALIFLIX_WORDMARK_HEIGHT_SCALES.lastIndex - index],
                0.001f,
            )
        }
    }

    @Test
    fun testWordCycleTimingAndSlotDuration() {
        val word0BeforeStart = calculateWordState(seq = -0.01f, wordIndex = 0, isReducedMotion = false)
        assertEquals(0f, word0BeforeStart.first, 0.001f)

        assertEquals(0.80f, LAUNCH_WORD_SLOT_SECONDS, 0.001f)

        val word0MidFadeIn = calculateWordState(seq = LAUNCH_WORD_SEQUENCE_START_SECONDS + .05f, wordIndex = 0, isReducedMotion = false)
        assertTrue("Word 0 should be fading in", word0MidFadeIn.first > 0f && word0MidFadeIn.first < 1f)

        val word0Hold = calculateWordState(seq = LAUNCH_WORD_SEQUENCE_START_SECONDS + .125f, wordIndex = 0, isReducedMotion = false)
        assertEquals("Word 0 should have full opacity during readable hold", 1f, word0Hold.first, 0.001f)
        assertEquals("Word 0 should have 0 translation Y during hold", 0f, word0Hold.second, 0.001f)

        val word0AtSlotEnd = calculateWordState(seq = LAUNCH_WORD_SEQUENCE_START_SECONDS + LAUNCH_WORD_SLOT_SECONDS, wordIndex = 0, isReducedMotion = false)
        assertEquals("Word 0 should be 0 at end of slot", 0f, word0AtSlotEnd.first, 0.001f)

        // Word 1 (SERIES) during hold
        val word1Hold = calculateWordState(seq = LAUNCH_WORD_SEQUENCE_START_SECONDS + LAUNCH_WORD_SLOT_SECONDS + .125f, wordIndex = 1, isReducedMotion = false)
        assertEquals("Word 1 should have full opacity during readable hold", 1f, word1Hold.first, 0.001f)

        // Word 2 (STORIES) during hold
        val word2Hold = calculateWordState(seq = 2.15f, wordIndex = 2, isReducedMotion = false)
        assertEquals("Word 2 should have full opacity during readable hold", 1f, word2Hold.first, 0.001f)

        val word2WhileWaitingForHome = calculateWordState(seq = 8f, wordIndex = 2, isReducedMotion = false)
        assertEquals("STORIES should remain visible while Home is still loading", 1f, word2WhileWaitingForHome.first, 0.001f)
    }

    @Test
    fun testZeroWordOverlapGuarantee() {
        // Cover entry, all three short words, and the slow-network fallback period.
        var t = 0.4f
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
        val sequenceEnd = LAUNCH_SEQUENCE_SECONDS

        assertTrue(!isLaunchSequenceComplete(sequenceEnd - 0.01f, isReducedMotion = false))
        assertTrue(isLaunchSequenceComplete(sequenceEnd, isReducedMotion = false))
        assertTrue(!isLaunchExitReady(isHomeReady = true, elapsedSeconds = sequenceEnd - 0.01f, isReducedMotion = false))
        assertTrue(isLaunchExitReady(isHomeReady = false, elapsedSeconds = sequenceEnd, isReducedMotion = false))
        assertTrue(isLaunchExitReady(isHomeReady = true, elapsedSeconds = sequenceEnd, isReducedMotion = false))
        assertTrue(!isLaunchSequenceComplete(0.14f, isReducedMotion = true))
        assertTrue(isLaunchSequenceComplete(0.15f, isReducedMotion = true))
    }

    @Test
    fun postStoriesExitIsAQuickContinuousCrossfade() {
        assertEquals(0.30f, LAUNCH_EXIT_DURATION_SECONDS, 0.001f)
        assertEquals(0f, calculateLaunchExitProgress(elapsedSeconds = 4f, exitStartSeconds = -1f), 0.001f)
        assertEquals(0.5f, calculateLaunchExitProgress(elapsedSeconds = 4.15f, exitStartSeconds = 4f), 0.001f)
        assertEquals(1f, calculateLaunchExitProgress(elapsedSeconds = 4.30f, exitStartSeconds = 4f), 0.001f)
    }
    @Test fun droppedFramesCannotExtendTheSequenceOrTheExit() {
        assertTrue(isLaunchExitReady(true,3.5f,false))
        assertEquals(1f,calculateLaunchExitProgress(10f,3.5f),0f)
        assertEquals(0f,calculateLaunchExitProgress(3.4f,3.5f),0f)
        assertTrue(isLaunchExitReady(false,60f,false))
    }

    @Test fun cachedContentIsReadyWhileUncachedContentUsesLoadingHome() {
        assertTrue(isLaunchHomeReady(loading=true,hasContent=true,hasError=false))
        assertTrue(isLaunchHomeReady(loading=false,hasContent=true,hasError=false))
        assertTrue(isLaunchHomeReady(loading=false,hasContent=false,hasError=true))
        assertTrue(!isLaunchHomeReady(loading=true,hasContent=false,hasError=false))
        assertTrue(!isLaunchHomeReady(loading=true,hasContent=false,hasError=true))
        assertTrue(!isLaunchHomeReady(loading=false,hasContent=false,hasError=false))
    }

    @Test fun homePreparesBeforeAnimationAndNetworkCannotHoldTheBrandedScreen() {
        assertTrue(isLaunchContentPreparationReady(0f,false))
        assertTrue(!isLaunchExitReady(true,2.27f,false))
        assertEquals(1f,calculateWordState(2.15f,2,false).first,.001f)
        assertTrue(!isLaunchExitReady(false,2.27f,false))
        assertTrue(isLaunchExitReady(false,LAUNCH_SEQUENCE_SECONDS,false))
        assertTrue(!isLaunchExitReady(false,.29f,true))
        assertTrue(isLaunchExitReady(false,.3f,true))
        assertTrue(isLaunchContentPreparationReady(0f,true))
    }
}
