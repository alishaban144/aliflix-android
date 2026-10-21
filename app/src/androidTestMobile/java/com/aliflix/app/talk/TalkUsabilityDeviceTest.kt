package com.aliflix.app.talk

import android.content.Intent
import android.os.SystemClock
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class TalkUsabilityDeviceTest {
    @Test fun rapidReplacementStopAndNextMessageRemainUsable() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkUsability")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        context.startActivity(Intent(context,TalkVoiceActivity::class.java).putExtra("textMode",true).putExtra("validationFreshHistory",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity:TalkVoiceActivity?=null
        runBlocking { withTimeout(20000) { while(activity==null) {
            instrumentation.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TalkVoiceActivity>().firstOrNull() }
            delay(30)
        } } }
        val talk=requireNotNull(activity);val session=talk.voice
        try {
            instrumentation.runOnMainSync {
                session.sendText("Explain the ending of Dark season 2 episode 1.")
                session.sendText("Instead, discuss the atmosphere of Arrival (2016).")
                session.stopAnswer()
                assertFalse(session.state.value.sending)
                assertEquals("Ready",session.state.value.phase)
            }
            runBlocking { delay(700) }
            assertFalse(session.state.value.sending)
            val began=SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { session.sendText("In one short sentence, tell me what you can help me discover.") }
            runBlocking { withTimeout(60000) { while(session.debugTurnCompleteAt<began||session.state.value.sending)delay(70) } }
            val reply=session.state.value.lines.lastOrNull { it.role=="assistant" }?.text.orEmpty()
            assertTrue(reply,reply.length>15)
            assertFalse(reply,Regex("Jonas|Claudia|Sic Mundus|Winden",RegexOption.IGNORE_CASE).containsMatchIn(reply))
            File(context.getExternalFilesDir(null),"talk-stop-replace-177.json").writeText(JSONObject().put("stopped",true).put("rapidReplacement",true).put("nextReply",reply).put("elapsedMs",SystemClock.elapsedRealtime()-began).toString())
        } finally { instrumentation.runOnMainSync { talk.finish() } }
    }
    @Test fun accountArchiveSavesRestoresAndDeletesOnlyTheTestConversation() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveAccountChats")=="true")
        Assume.assumeTrue(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val first=TalkVoiceSession(context,scope,restoreHistory=false)
        val second=TalkVoiceSession(context,scope,restoreHistory=false)
        val id=java.util.UUID.randomUUID().toString()
        val chat=TalkChat(id,"Validation conversation",System.currentTimeMillis(),listOf(TalkLine("user","Recent science fiction"),TalkLine("assistant","Saved dialogue",listOf(TalkReference("Source","https://example.org/film","Publisher")))))
        try {
            first.history.save(chat)
            runBlocking { withTimeout(20000) { while(!first.history.status.value.startsWith("Synced"))delay(100) } }
            second.history.refresh()
            runBlocking {
                delay(150)
                withTimeout(15000) { while(!second.history.status.value.startsWith("Synced"))delay(100) }
                val restored=second.history.read(id)
                assertEquals(chat.id,restored?.id)
                assertEquals(chat.title,restored?.title)
                assertEquals(chat.lines,restored?.lines)
            }
            File(context.getExternalFilesDir(null),"talk-account-177.json").writeText(JSONObject().put("saveAndRestore",true).put("sourcesPreserved",true).toString())
        } finally {
            first.history.delete(id)
            runBlocking { delay(150);withTimeoutOrNull(15000) { while(!first.history.status.value.startsWith("Synced"))delay(100) } }
            first.close();second.close();scope.cancel()
        }
    }
    @Test fun currentGenreRequestReplacesAnAbandonedEpisodeQuestion() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkUsability")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        context.startActivity(Intent(context,TalkVoiceActivity::class.java).putExtra("textMode",true).putExtra("validationFreshHistory",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var talk:TalkVoiceActivity?=null
        runBlocking { withTimeout(20000) { while(talk==null) {
            instrumentation.runOnMainSync { talk=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TalkVoiceActivity>().firstOrNull() }
            delay(50)
        } } }
        val activity=requireNotNull(talk);val session=activity.voice
        try {
            runBlocking { withTimeout(25000) { while(session.debugReadyGeneration==0L)delay(50) } }
            instrumentation.runOnMainSync { session.sendText("Briefly explain the ending of Dark season 2 episode 1.") }
            runBlocking { withTimeoutOrNull(5000) { while((session.debugToolCalls["film_knowledge"]?:0)==0)delay(50) } }
            val began=SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { session.sendText("Instead, suggest two recent sci-fi movies. Include their release years.") }
            runBlocking { withTimeout(100000) { while(session.debugTurnCompleteAt<began||session.state.value.sending)delay(100) } }
            val lines=session.state.value.lines
            val lastUser=lines.indexOfLast { it.role=="user" }
            val answer=lines.drop(lastUser+1).filter { it.role=="assistant" }.joinToString(" ") { it.text }
            val receipt=JSONObject().put("answer",answer).put("recommendations",session.debugRecommendationCount).put("years",session.debugRecommendationYears)
                .put("elapsedMs",SystemClock.elapsedRealtime()-began).put("tools",JSONObject(session.debugToolCalls.toMap())).put("recommendationIssue",session.debugRecommendationIssue)
            File(context.getExternalFilesDir(null),"talk-usability-177.json").writeText(receipt.toString(2))
            assertTrue("No researched recommendations: $receipt",session.debugRecommendationCount>0)
            assertTrue("No dated results: $receipt",session.debugRecommendationYears.isNotEmpty())
            assertTrue(receipt.toString(),session.debugRecommendationYears.all { it>=java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)-3 })
            assertFalse("The old episode hijacked the new request: $receipt",Regex("Jonas|Claudia|Sic Mundus|Adam|Winden",RegexOption.IGNORE_CASE).containsMatchIn(answer))
            assertTrue("No text answer: $receipt",answer.length>30)
            assertTrue(session.state.value.textMode)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
