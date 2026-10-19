package com.aliflix.app.talk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkFunctionResponseTest {
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
