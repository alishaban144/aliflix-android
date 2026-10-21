package com.aliflix.app.talk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkFunctionResponseTest {
    @Test fun namedQuotaErrorsStopRetryingAndSavedDanglingQuestionsDoNotBecomeNewTurns() {
        assertTrue(talkFailureIsLimited(TalkHttpException(429,"DAILY_LIMIT")))
        assertTrue(talkFailureIsLimited(TalkHttpException(429,"TRY_LATER")))
        assertFalse(talkFailureIsLimited(TalkHttpException(503,"LIVE_UNAVAILABLE")))
        val completed=listOf(TalkLine("user","A completed question"),TalkLine("assistant","Its answer"))
        val restored=talkArchiveResumeLines(completed+TalkLine("user","An interrupted question"))
        assertEquals(completed,restored.take(2));assertTrue(restored.last().stopped)
        assertFalse(talkContinuityTurns(restored,false).toString().contains("interrupted question"))
    }
    @Test fun stoppedRequestsRemainVisibleButCannotBecomeRestoredInstructions() {
        val lines=listOf(TalkLine("user","Recent films"),TalkLine("assistant","A completed recommendation"),
            TalkLine("user","Explain Dark",stopped=true),TalkLine("assistant","An unfinished episode answer",stopped=true))
        for(resuming in listOf(false,true)) {
            val restored=talkContinuityTurns(lines,resuming).toString()
            assertTrue(restored.contains("completed recommendation"))
            assertFalse(restored.contains("Explain Dark"))
            assertFalse(restored.contains("unfinished episode"))
        }
        assertEquals(4,lines.size)
    }
    @Test fun controlOnlyTurnsContinueOnceWithoutDuplicatingAnAnswerOrRacingEvidence() {
        val names=listOf("conversation_language","conversation_topic")
        assertEquals("SILENT",talkControlScheduling(names,0,false,false))
        assertEquals("WHEN_IDLE",talkControlScheduling(names,1,false,false))
        assertEquals("SILENT",talkControlScheduling(names,1,true,false))
        assertEquals("SILENT",talkControlScheduling(names,1,false,true))
        assertEquals("SILENT",talkControlScheduling(listOf("conversation_topic","film_knowledge"),0,false,false))
        assertEquals("SILENT",talkControlScheduling(listOf("remember_recommendations"),0,false,false))
    }
    @Test fun reconnectRestoresBoundedDialogueWithoutRequestingAnAnswer() {
        val lines=(1..20).map { TalkLine(if(it%2==0)"assistant" else "user","Film $it "+"x".repeat(900)) }
        val fresh=talkContinuityTurns(lines,false)
        assertEquals(12,fresh.length())
        assertEquals(800,fresh.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text").length)
        val resumed=talkContinuityTurns(lines,true)
        assertEquals(1,resumed.length())
        val text=resumed.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.contains("Film 9 "))
        assertFalse(text.contains("Film 8 "))
        assertTrue(text.contains("do not answer this context note"))
    }
    @Test fun schedulingUsesTheProtocolEnvelopeWithoutPollutingEvidence() {
        for(mode in listOf("SILENT","WHEN_IDLE","INTERRUPT")) {
            val facts=JSONObject().put("year",2053)
            val wire=talkFunctionResponse("call-1","film_knowledge",facts,mode)
            assertEquals(mode,wire.getString("scheduling"))
            assertEquals("call-1",wire.getString("id"))
            assertEquals(2053,wire.getJSONObject("response").getInt("year"))
            assertFalse(wire.getJSONObject("response").has("scheduling"))
            assertEquals(1,facts.length())
        }
    }
}
