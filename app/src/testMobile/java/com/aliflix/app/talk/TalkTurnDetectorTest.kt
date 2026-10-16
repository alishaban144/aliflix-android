package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkTurnDetectorTest {
    @Test fun libraryCompletionCannotRevealTheUnwatchedLastTwentyPercent() {
        assertFalse(talkPlaybackCompleted(800.0,1000.0,true))
        assertFalse(talkPlaybackCompleted(997.0,1000.0,true))
        assertTrue(talkPlaybackCompleted(999.0,1000.0,true))
        assertFalse(talkPlaybackCompleted(Double.NaN,1000.0,true))
        assertFalse(talkPlaybackCompleted(999.0,1000.0,false))
    }
    @Test fun skippedEpisodesAreNotSilentlyAddedToTheViewingBoundary() {
        assertEquals(listOf(Triple(1,1,2),Triple(1,4,4),Triple(2,1,1)),
            compactTalkViewedRanges(listOf(1 to 4,1 to 1,2 to 1,1 to 2,1 to 2,-1 to 1)))
    }
    @Test fun quietRoomAndSingleClicksDoNotStartAnUtterance() {
        val vad=TalkTurnDetector()
        repeat(50) { assertEquals(TalkTurnDetector.NONE,vad.accept(.001,it*20L,false)) }
        assertEquals(TalkTurnDetector.NONE,vad.accept(.2,1000,false))
        assertEquals(TalkTurnDetector.NONE,vad.accept(.001,1020,false));assertFalse(vad.active)
    }
    @Test fun sustainedSpeechStartsOnceAndNaturalPausesUseHangover() {
        val vad=TalkTurnDetector()
        vad.accept(.05,0,false);vad.accept(.05,20,false)
        assertEquals(TalkTurnDetector.START,vad.accept(.05,40,false));assertTrue(vad.active)
        assertEquals(TalkTurnDetector.NONE,vad.accept(.001,600,false))
        assertEquals(TalkTurnDetector.END,vad.accept(.001,800,false));assertFalse(vad.active)
    }
    @Test fun utterancesAreBoundedAndMuteResetsWithoutSpuriousTurns() {
        val vad=TalkTurnDetector();repeat(3){vad.accept(.05,it*20L,false)}
        assertEquals(TalkTurnDetector.END,vad.accept(.05,26000,false));assertFalse(vad.active)
        assertFalse(vad.reset());repeat(3){vad.accept(.05,27000+it*20L,false)}
        assertTrue(vad.reset());assertFalse(vad.active)
    }
}
