package com.aliflix.app.talk

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class TalkTopicMotionTest {
    @Test fun lettersOrbitThenStayExactlySettledWhileShineReversesSmoothly() {
        for(index in 0..40) {
            assertTrue(abs(talkTopicOrbit(.03f,index).x)+abs(talkTopicOrbit(.03f,index).y)>0f)
            assertEquals(TalkLetterPose(0f,0f,0f,1f),talkTopicOrbit(.9f,index))
            assertEquals(talkTopicOrbit(2f,index),talkTopicOrbit(60f,index))
        }
        for(hz in listOf(60,90,120)) {
            var previous=talkTopicShine(0f)
            repeat(hz*8){frame->val next=talkTopicShine((frame+1f)/hz);assertTrue(abs(next-previous)<1.5f/hz);previous=next}
        }
        assertTrue(talkTopicShine(1.1f)>.999f)
        assertTrue(talkTopicShine(2.2f)<.001f)
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
