package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkOrbActivityTest {
    private fun state(phase:String="Listening", stage:TalkEvidenceStage=TalkEvidenceStage.NONE,
        text:Boolean=false, sending:Boolean=false, muted:Boolean=false) = TalkVoiceState(phase=phase,
        conversationId="fixture",evidenceStage=stage,textMode=text,sending=sending,muted=muted)

    @Test fun actualEvidenceWorkTakesPriorityOverGenericThinkingAndTextMode() {
        assertEquals(TalkOrbActivity.RECALL,talkOrbActivity(state("Thinking",TalkEvidenceStage.RECALL,true,true)))
        assertEquals(TalkOrbActivity.OBSERVE,talkOrbActivity(state("Thinking",TalkEvidenceStage.OBSERVE,true,true)))
        assertEquals(TalkOrbActivity.THINK,talkOrbActivity(state("Thinking",text=true,sending=true)))
        assertEquals(TalkOrbActivity.SPEAK,talkOrbActivity(state("Speaking",TalkEvidenceStage.RECALL)))
        assertEquals(TalkOrbActivity.CONNECT,talkOrbActivity(state("Reconnecting",TalkEvidenceStage.OBSERVE)))
        assertEquals(TalkOrbActivity.UNAVAILABLE,talkOrbActivity(state("Reconnect",TalkEvidenceStage.RECALL)))
    }
    @Test fun readingMutedAndStreamingRepliesHaveTruthfulDistinctStates() {
        assertEquals(TalkOrbActivity.READ,talkOrbActivity(state(text=true,muted=true)))
        assertEquals(TalkOrbActivity.MUTED,talkOrbActivity(state(muted=true)))
        assertEquals(TalkOrbActivity.LISTEN,talkOrbActivity(state()))
        val writing=state("Thinking",text=true,sending=true).copy(lines=listOf(TalkLine("assistant","The earlier warning explains this.")))
        assertEquals(TalkOrbActivity.WRITE,talkOrbActivity(writing))
    }
    @Test fun expressionsStayBoundedAndMeaningDoesNotDependOnlyOnColor() {
        val appearances=TalkOrbActivity.entries.map(::talkOrbAppearance)
        assertEquals(appearances.size,appearances.map { it.label }.distinct().size)
        for(a in appearances) {
            assertTrue(a.face.gaze.getDistance()<=1f)
            assertTrue(a.face.openness in .65f..1.12f)
            assertTrue(a.glow in 0f..1f && a.flow in 0f..1f)
            assertTrue(kotlin.math.abs(a.tilt)<=3f && a.breath<=.006f)
        }
        assertNotEquals(talkOrbAppearance(TalkOrbActivity.LISTEN).face,talkOrbAppearance(TalkOrbActivity.OBSERVE).face)
        assertNotEquals(talkOrbAppearance(TalkOrbActivity.READ).face,talkOrbAppearance(TalkOrbActivity.THINK).face)
        assertEquals(0f,talkOrbAppearance(TalkOrbActivity.UNAVAILABLE).flow,0f)
    }
}
