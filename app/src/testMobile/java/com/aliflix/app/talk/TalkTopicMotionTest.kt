package com.aliflix.app.talk

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class TalkTopicMotionTest {
    @Test fun waveMovesEveryFrameWithoutTheFormerColorStepsOrSettleSnap() {
        for(hz in listOf(60,90,120))for(column in 0..64) {
            var previous=talkTopicWave(0f,column/64f)
            var movement=0f
            repeat(hz*12) { frame ->
                val next=talkTopicWave((frame+1f)/hz,column/64f)
                assertTrue("Bounded velocity at $hz Hz",abs(next-previous)<=1.251f/hz)
                movement+=abs(next-previous);previous=next
            }
            assertTrue("No frozen columns or quantized plateaus",movement>8f)
        }
        for(t in listOf(.32f,1.8f,5.02655f,60f,3600f)) {
            assertTrue(abs(talkTopicWave(t-.0005f,.3f)-talkTopicWave(t+.0005f,.3f))<.002f)
        }
    }
    @Test fun wordCrossfadeHasNoOpacityJumpAndSharesTheOngoingPhase() {
        assertEquals(0f,talkTopicReveal(-1f),0f)
        assertEquals(1f,talkTopicReveal(1f),0f)
        for(frame in 0..120) {
            val age=frame/120f
            assertEquals(1f,talkTopicReveal(age)+(1f-talkTopicReveal(age)),0f)
            assertTrue(abs(talkTopicReveal(age+.0001f)-talkTopicReveal(age))<.001f)
        }
    }
}
