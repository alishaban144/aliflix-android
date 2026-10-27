package com.aliflix.app.talk

import androidx.compose.ui.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Replays the provider ordering captured in the physical Annihilation failure. */
class TalkWireReplayDeviceTest {
    @get:Rule val compose=freshTalkValidationRule(localAudio=true)
    @Test fun idleScheduledControlReplyCannotRaceASyntheticUserTurn(){
        val session=compose.activity.voice
        compose.runOnUiThread{
            session.setTextMode(true);session.debugPreviewUserQuestion("Why is the sky blue?")
            session.debugReplayServerMessage(JSONObject("""{"toolCall":{"functionCalls":[{"id":"language","name":"conversation_language","args":{"language":"en","explicitRequest":false}}]}}"""))
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"turnComplete":true}}"""))
            assertEquals("A scheduled function continuation needs no competing user prompt",0,TalkVoiceSession::class.java.getDeclaredField("silentReplyNudges").apply{isAccessible=true}.getInt(session))
            val deadline=TalkVoiceSession::class.java.getDeclaredField("answerDeadline").apply{isAccessible=true}.get(session) as TalkResponseDeadline
            assertTrue(deadline.pending)
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"outputTranscription":{"text":"Air scatters blue light more strongly."},"generationComplete":true,"turnComplete":true}}"""))
            assertFalse(session.state.value.sending)
            assertEquals(0,session.debugInterruptions)
            deadline.clear()
        }
    }
    @Test fun lateInputFragmentsUpdateOneUtteranceWithoutRevivingFinishedWriting() {
        val session=compose.activity.voice
        compose.runOnUiThread {
            session.newChat();session.setTextMode(true)
            fun event(json:String){val message=JSONObject(json);val activity=message.optJSONObject("voiceActivity")?.optString("type");if(activity!=null)session.debugSetInputActivity(activity=="ACTIVITY_START")else session.debugReplayServerMessage(message)}
            event("""{"voiceActivity":{"type":"ACTIVITY_START"}}""")
            event("""{"serverContent":{"inputTranscription":{"text":"Should I watch Arrival"}}}""")
            event("""{"serverContent":{"interrupted":true}}""")
            event("""{"serverContent":{"turnComplete":true}}""")
            assertEquals(0L,session.debugTurnCompleteAt)
            assertEquals(0,TalkVoiceSession::class.java.getDeclaredField("silentReplyNudges").apply{isAccessible=true}.getInt(session))
            event("""{"serverContent":{"outputTranscription":{"text":"Stale narration during user speech."},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAAAA=="}}]}}}""")
            assertFalse(session.state.value.lines.any{it.role=="assistant"})
            event("""{"voiceActivity":{"type":"ACTIVITY_END"}}""")
            event("""{"serverContent":{"outputTranscription":{"text":"Choose Arrival for its warmth."}}}""")
            event("""{"serverContent":{"inputTranscription":{"text":" or Ex Machina?"}}}""")
            event("""{"serverContent":{"generationComplete":true,"turnComplete":true}}""")
            event("""{"serverContent":{"inputTranscription":{"text":" Pick one."}}}""")
            assertEquals(listOf("Should I watch Arrival or Ex Machina? Pick one."),session.state.value.lines.filter{it.role=="user"}.map{it.text})
            assertFalse(session.state.value.sending)
            assertFalse(talkShowsResponseProgress(session.state.value.sending,session.state.value.answerComplete))
            event("""{"voiceActivity":{"type":"ACTIVITY_START"}}""")
            event("""{"serverContent":{"inputTranscription":{"text":"Hello again."}}}""")
            event("""{"voiceActivity":{"type":"ACTIVITY_END"}}""")
            event("""{"serverContent":{"outputTranscription":{"text":"Hello."},"generationComplete":true,"turnComplete":true}}""")
            assertEquals(2,session.state.value.lines.count{it.role=="user"})
            assertEquals("Hello again.",session.state.value.lines.last{it.role=="user"}.text)
        }
    }
    @Test fun aShortIntraUtterancePauseKeepsBothClausesAndTheirChoices() {
        val session=compose.activity.voice
        compose.runOnUiThread {
            session.newChat();session.setTextMode(true)
            fun event(json:String){val message=JSONObject(json);val activity=message.optJSONObject("voiceActivity")?.optString("type");if(activity!=null)session.debugSetInputActivity(activity=="ACTIVITY_START")else session.debugReplayServerMessage(message)}
            event("""{"voiceActivity":{"type":"ACTIVITY_START"}}""")
            event("""{"serverContent":{"inputTranscription":{"text":"Choose between Arrival and Ex Machina."}}}""")
            event("""{"voiceActivity":{"type":"ACTIVITY_END"}}""")
            event("""{"voiceActivity":{"type":"ACTIVITY_START"}}""")
            event("""{"serverContent":{"inputTranscription":{"text":"Pick one for tonight."}}}""")
            event("""{"voiceActivity":{"type":"ACTIVITY_END"}}""")
            event("""{"serverContent":{"outputTranscription":{"text":"Arrival for its warmth."},"generationComplete":true,"turnComplete":true}}""")
            assertEquals(listOf("Choose between Arrival and Ex Machina. Pick one for tonight."),session.state.value.lines.filter{it.role=="user"}.map{it.text})
        }
    }
    @Test fun finishedTextHidesWritingEvenBeforeTheDelayedTurnComplete() {
        val session=compose.activity.voice
        compose.runOnUiThread {
            session.setTextMode(true)
            session.debugPreviewUserQuestion("Why is the sky blue?")
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"outputTranscription":{"text":"Air scatters blue light."}}}"""))
            assertTrue(talkShowsResponseProgress(session.state.value.sending,session.state.value.answerComplete))
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"generationComplete":true}}"""))
            assertFalse(talkShowsResponseProgress(session.state.value.sending,session.state.value.answerComplete))
        }
        compose.onAllNodesWithTag("talk-response-status").assertCountEquals(0)
    }
    @Test fun aProviderOnlyInterruptionCannotFlushACommittedRecommendation() {
        val session=compose.activity.voice
        compose.runOnUiThread {
            session.debugPreviewUserQuestion("Recommend three films")
            TalkVoiceSession::class.java.getDeclaredField("presentationDelivered").apply{isAccessible=true}.setBoolean(session,true)
            val before=session.debugInterruptions
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"interrupted":true}}"""))
            assertEquals(before,session.debugInterruptions)
            assertFalse(session.debugOutputPlaying)
        }
    }
    @Test fun withheldOutputCannotCancelTheUnansweredPresentationDeadline() {
        val session=compose.activity.voice
        compose.runOnUiThread {
            session.debugPreviewUserQuestion("Recommend three movies")
            val deadline=TalkVoiceSession::class.java.getDeclaredField("answerDeadline").apply{isAccessible=true}.get(session) as TalkResponseDeadline
            deadline.awaiting(60000)
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"outputTranscription":{"text":"Unverified suggestions that have not been committed."},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAAAA=="}}]}}}"""))
            assertTrue("Held audio is not a delivered answer",deadline.pending)
            session.debugReplayServerMessage(JSONObject("""{"serverContent":{"generationComplete":true}}"""))
            assertTrue("Unapproved generation completion must still leave a response deadline",deadline.pending)
            deadline.clear()
        }
    }
    @Test fun aGenuineBargeAbandonsTheOldLookupAndAnswersOnlyTheNewQuestion() {
        val session=compose.activity.voice
        fun content(json:String)=session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject(json)))
        compose.runOnUiThread {
            session.debugPreviewUserQuestion("Tell me more about Annihilation without spoilers.")
            TalkVoiceSession::class.java.getDeclaredField("awaitingToolAnswer").apply{isAccessible=true}.setBoolean(session,true)
            TalkVoiceSession::class.java.getDeclaredField("pendingToolReply").apply{isAccessible=true}.set(session,JSONObject().put("evidence","A verified premise"))
            content("""{"outputTranscription":{"text":"It's a film about a team exploring"},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAAAA=="}}]}}""")
            session.debugSetInputActivity(true)
            content("""{"interrupted":true}""")
            content("""{"inputTranscription":{"text":"Actually, explain why the sky is blue instead."}}""")
            session.debugSetInputActivity(false)
            content("""{"turnComplete":true}""")
            assertNull("An interrupted lookup cannot regain the floor",TalkVoiceSession::class.java.getDeclaredField("pendingToolReply").apply{isAccessible=true}.get(session))
            assertTrue(session.state.value.sending)
            assertEquals(0L,session.debugTurnCompleteAt)
            content("""{"outputTranscription":{"text":"Air molecules scatter shorter blue wavelengths more strongly."},"generationComplete":true,"turnComplete":true}""")
            assertFalse(session.state.value.sending)
            assertTrue(session.debugTurnCompleteAt>0)
            assertEquals("Actually, explain why the sky is blue instead.",session.state.value.lines.last{it.role=="user"}.text)
            assertTrue(session.state.value.lines.last().text.startsWith("Air molecules"))
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
