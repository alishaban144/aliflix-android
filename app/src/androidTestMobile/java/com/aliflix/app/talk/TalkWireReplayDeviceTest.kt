package com.aliflix.app.talk

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Replays the provider ordering captured in the physical Annihilation failure. */
class TalkWireReplayDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<TalkVoiceActivity>()
    @Test fun interruptedLookupAudioCannotCompleteTheUnansweredUserTurn() {
        val session=compose.activity.voice
        fun content(json:String)=session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject(json)))
        compose.runOnUiThread {
            session.debugPreviewUserQuestion("Tell me more about Annihilation without spoilers.")
            TalkVoiceSession::class.java.getDeclaredField("awaitingToolAnswer").apply{isAccessible=true}.setBoolean(session,true)
            TalkVoiceSession::class.java.getDeclaredField("pendingToolReply").apply{isAccessible=true}.set(session,JSONObject().put("evidence","A verified premise"))
            content("""{"outputTranscription":{"text":"It's a film about a team exploring"},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAAAA=="}}]}}""")
            content("""{"interrupted":true}""")
            content("""{"turnComplete":true}""")
            assertTrue("Still owes the real lookup answer",session.state.value.sending)
            assertEquals("Thinking",session.state.value.phase)
            assertEquals(0L,session.debugTurnCompleteAt)
            assertTrue(session.state.value.lines.last().stopped)
            content("""{"outputTranscription":{"text":"Annihilation follows scientists investigating a mysterious region, with a meditative, unsettling mood."}}""")
            content("""{"generationComplete":true,"turnComplete":true}""")
            assertFalse(session.state.value.sending)
            assertTrue(session.debugTurnCompleteAt>0)
            assertFalse(session.state.value.lines.last().stopped)
            assertTrue(session.state.value.lines.last().text.startsWith("Annihilation follows"))
        }
    }
    @Test fun latePresentationRetriesCannotChangeCardsOrAppendPhantomErrors() {
        val session=compose.activity.voice
        compose.runOnUiThread {
            session.debugPreviewUserQuestion("Recommend three movies")
            val cards=(1..3).map{TalkTitleCard(it,com.aliflix.app.model.MediaType.MOVIE,"Film $it","200$it")}
            val script=cards.joinToString(" "){it.title+": A distinct thoughtful choice."}
            session.debugPreviewTitles(cards,false)
            fun set(name:String,value:Any)=TalkVoiceSession::class.java.getDeclaredField(name).apply{isAccessible=true}.set(session,value)
            val reply=JSONObject().put("speechText",script)
            set("pendingTitles",cards);set("pendingAnswerNeedsTitle",true);set("awaitingToolAnswer",true);set("pendingToolReply",reply);set("recommendationReply",reply);set("recommendationLookup",false)
            fun content(value:JSONObject)=session.debugReplayServerMessage(JSONObject().put("serverContent",value))
            content(JSONObject().put("outputTranscription",JSONObject().put("text",script)))
            content(JSONObject().put("turnComplete",true))
            assertTrue("The approved answer must actually be delivered",session.state.value.lines.any{it.text==script})
            val delivered=session.state.value.lines
            session.debugReplayServerMessage(JSONObject("""{"toolCall":{"functionCalls":[{"id":"duplicate","name":"present_recommendations","args":{"picks":[]}}]}}"""))
            content(JSONObject().put("outputTranscription",JSONObject().put("text","The system encountered an error. Please try again later.")))
            content(JSONObject().put("turnComplete",true))
            assertEquals(delivered,session.state.value.lines)
            assertEquals(cards,session.state.value.cards.visible)
            assertEquals("",session.debugPresentationIssue)
        }
    }
}
