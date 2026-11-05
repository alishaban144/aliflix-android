package com.aliflix.app.talk

import com.aliflix.app.player.risingBallLeg
import com.aliflix.app.player.risingBallScale
import org.junit.Assert.*
import org.junit.Test

class Talk193MotionTest {
    @Test fun haloWaitsForVoiceReadinessAndDisappearsOnConnectionLossOrTyping(){
        for(phase in listOf("Ready","Connecting","Reconnecting","Reconnect","Try again later","Audio unavailable"))
            assertFalse(phase,talkVoiceHaloReady(TalkVoiceState(phase=phase)))
        for(phase in listOf("Listening","Speaking","Thinking")){
            assertTrue(phase,talkVoiceHaloReady(TalkVoiceState(phase=phase)))
            assertFalse(talkVoiceHaloReady(TalkVoiceState(phase=phase,muted=true)))
            assertFalse(talkVoiceHaloReady(TalkVoiceState(phase=phase,textMode=true)))
        }
    }
    @Test fun ballRetainsTheReferencesAlternatingLegsAndSquashKeyframes(){
        assertEquals(0f,risingBallLeg(0f),.0001f)
        assertEquals(1f,risingBallLeg(.5f),.0001f)
        assertEquals(risingBallLeg(.25f),risingBallLeg(.75f),.0001f)
        assertEquals(0f,risingBallLeg(1f),.0001f)
        val start=risingBallScale(0f);assertEquals(1f,start.x,.001f);assertEquals(.7f,start.y,.001f)
        val stretch=risingBallScale(.4f);assertEquals(.8f,stretch.x,.001f);assertEquals(1.2f,stretch.y,.001f)
        val settle=risingBallScale(.6f);assertEquals(1f,settle.x,.001f);assertEquals(1f,settle.y,.001f)
    }
}
