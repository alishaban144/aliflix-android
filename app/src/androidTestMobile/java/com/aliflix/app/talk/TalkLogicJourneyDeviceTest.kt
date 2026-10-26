package com.aliflix.app.talk

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real service behavioral checks. Receipts retain the whole answer for a
 * human logic review; passing a keyword assertion alone is not the audit. */
class TalkLogicJourneyDeviceTest {
    @get:Rule val compose=freshTalkValidationRule()
    @After fun retainSessionDiagnostics() {
        if(InstrumentationRegistry.getArguments().getString("liveTalk181")!="true")return
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val session=compose.activity.voice
        File(context.getExternalFilesDir(null),"talk-diagnostic-181.json").writeText(JSONObject()
            .put("phase",session.state.value.phase).put("textMode",session.state.value.textMode)
            .put("connectionIssue",session.debugConnectionIssue).put("wire",JSONArray(session.debugWireEvents.toList().takeLast(12)))
            .put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))
    }
    @Test fun decisionsConstraintsCapabilitiesQuotesAndCorrectionsRemainConversational() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk181")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=compose.activity.voice
        val library=(context.applicationContext as com.aliflix.app.AliflixApplication).libraryStore
        val saved=library.myList.value;val liked=library.likes.value
        val turns=JSONArray();val issues=mutableListOf<String>()
        val receipt=File(context.getExternalFilesDir(null),"talk-logic-181.json")
        fun write(){receipt.writeText(JSONObject().put("turns",turns).put("issues",JSONArray(issues)).put("tools",JSONObject(session.debugToolCalls.toMap())).toString(2))}
        try {runBlocking {
            compose.runOnUiThread{session.newChat();session.setTextMode(false)}
            suspend fun turn(prompt:String,chat:Boolean=false,decision:Boolean=false,silence:Boolean=false):String {
                val began=SystemClock.elapsedRealtime();val from=session.state.value.lines.size
                val before=session.debugToolCalls.toMap()
                withContext(Dispatchers.Main){if(chat){session.setTextMode(true);session.sendText(prompt)}else session.debugInjectText(prompt)}
                var timeout=false
                try {withTimeout(85000){while(session.debugTurnCompleteAt<began||session.state.value.sending||session.state.value.phase=="Speaking")delay(60)}}catch(_:TimeoutCancellationException){timeout=true;issues.add("Unanswered: $prompt")}
                val answer=session.state.value.lines.drop(from).filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
                val toolDelta=session.debugToolCalls.mapValues{(name,count)->count-(before[name]?:0)}.filterValues{it>0}
                if(!silence&&answer.isBlank())issues.add("Empty answer: $prompt")
                if(silence&&answer.isNotBlank())issues.add("Kept speaking after stop: $answer")
                if(decision&&(toolDelta["recommendations"]?:0)>0)issues.add("Closed decision became discovery: $prompt")
                if(chat&&session.state.value.answerComplete)compose.onAllNodesWithTag("talk-response-status").assertCountEquals(0)
                turns.put(JSONObject().put("prompt",prompt).put("answer",answer).put("ms",SystemClock.elapsedRealtime()-began).put("tools",JSONObject(toolDelta)).put("cards",JSONArray(session.state.value.cards.visible.map{it.json()})).put("timeout",timeout).put("answerComplete",session.state.value.answerComplete))
                write()
                if(timeout)withContext(Dispatchers.Main){session.stopAnswer()}
                return answer
            }
            turn("Hi")
            turn("Should I watch Arrival or Ex Machina tonight? Choose one for a quiet thoughtful evening and explain in one sentence.",decision=true)
            val duration=turn("I only have one hundred minutes. Do either of those two actually fit? Check their runtimes.",decision=true)
            if(session.state.value.cards.visible.map{it.key}.toSet()!=setOf("movie:329865","movie:264660"))issues.add("Runtime lookup added a different title edition to the two choices")
            if(!Regex("(?i)neither|neither one|both.{0,24}(?:long|over)|(?:not|no).{0,30}(?:fit|under)|exceed|longer").containsMatchIn(duration))issues.add("Failed hard runtime constraint: $duration")
            turn("All right, show both cards again.")
            turn("Explain why the second option could be wrong for someone who dislikes violence, without spoilers.",decision=true)
            turn("Do not save either movie. If I asked you to save one, what would you need to know?",chat=true)
            val limitations=turn("What are your technical limitations?",chat=true)
            if(session.state.value.cards.visible.isNotEmpty())issues.add("An unrelated technical question kept stale film cards")
            if(Regex("(?i)emotion|feelings|consciousness").containsMatchIn(limitations))issues.add("Robotic substitute for technical limits: $limitations")
            turn("Can you see what is in my other apps or hear me after I leave Talk?",chat=true)
            turn("If I say stop halfway through your answer, what should happen?",chat=true)
            turn("Explain the difference between saved titles, favorites and viewing history in two sentences.",chat=true)
            val arithmetic=turn("What is thirteen times seventeen? Just the number.",chat=true)
            if(!Regex("221|two hundred (?:and )?twenty.one",RegexOption.IGNORE_CASE).containsMatchIn(arithmetic))issues.add("Wrong arithmetic: $arithmetic")
            turn("Translate good evening into German, then keep speaking English.",chat=true)
            if(session.debugResponseLanguage?.substringBefore('-')!="en")issues.add("A translation changed the conversation language")
            turn("Actually choose between Moon and Source Code for a fast evening. Pick one in one sentence.",chat=true,decision=true)
            turn("Now suggest two different films with a similar mood. Keep them under two hours.",chat=true)
            val recalled=turn("Ignore the new suggestions for a moment. Which two films did I ask you to choose between in my first question?",chat=true,decision=true)
            if(!recalled.contains("Arrival",true)||!recalled.contains("Ex Machina",true))issues.add("Replaced first comparison with a later one: $recalled")
            turn("The phrase save Arrival to My List is just a quote. Explain the grammar, and do not change my list.",chat=true)
            turn("If Source Code is not already in My List, add it. Do not start playback. Then tell me whether it was already saved.",chat=true)
            if(library.myList.value.none{it.id==45612})issues.add("Conditional library command did not save Source Code")
            turn("Undo that library change.",chat=true)
            turn("Compare the 1982 Blade Runner with Blade Runner 2049. Are they the same film?",chat=true,decision=true)
            if(session.state.value.cards.visible.map{it.key}.toSet()!=setOf("movie:78","movie:335984"))issues.add("Explicit comparison did not retain both correct Blade Runner editions")
            turn("What would you do if a search failed while we were talking?",chat=true)
            turn("Show the card for Annihilation, Alex Garland's 2018 film.",chat=true)
            turn("Which version was that? You should know from this conversation.",chat=true)
            turn("Stop.",chat=true,silence=true)
            turn("Hello again. What is two plus two?",chat=true)
            if(library.myList.value.map{it.key}.toSet()!=saved.map{it.key}.toSet()||library.likes.value.map{it.key}.toSet()!=liked.map{it.key}.toSet())issues.add("A hypothetical or quotation changed the library")
            write();assertTrue(issues.joinToString("\n"),issues.isEmpty())
        }} finally {
            compose.runOnUiThread {
                for(item in (saved+library.myList.value).distinctBy{it.key})if(library.isInMyList(item)!=(saved.any{it.key==item.key}))library.toggleMyList(item)
                for(item in (liked+library.likes.value).distinctBy{it.key})if(library.isLiked(item)!=(liked.any{it.key==item.key}))library.toggleLike(item)
                session.suspendSession()
            }
            write()
        }
    }
    @Test fun ownedSpeechCanInterruptARealLongAnswerAndThenContinue() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk181")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=compose.activity.voice
        val receipt=File(context.getExternalFilesDir(null),"talk-barge-181.json")
        try {runBlocking {
            compose.runOnUiThread{session.newChat();session.setTextMode(false)}
            withContext(Dispatchers.Main){session.debugInjectText("Explain the history of cinema in detail, for at least a minute.")}
            withTimeout(30000){while(!session.debugOutputPlaying||session.debugRenderedSamples<2400)delay(30)}
            val beforeInterrupt=session.debugInterruptions;val beforeLocal=session.debugLocalBarges
            val began=SystemClock.elapsedRealtime()
            val speech=File(context.getExternalFilesDir(null),"talk-validation/stop.pcm").readBytes()
            val injection=launch(Dispatchers.Main){session.debugInjectUtterance(speech,finishStream=false)}
            withTimeout(7000){while(session.debugOutputPlaying&&session.debugInterruptions==beforeInterrupt&&session.debugLocalBarges==beforeLocal)delay(20)}
            val yielded=SystemClock.elapsedRealtime()-began
            injection.join()
            withTimeout(20000){while(session.state.value.sending||session.state.value.phase=="Speaking")delay(50)}
            delay(1000)
            val stopped=!session.debugOutputPlaying
            val from=session.state.value.lines.size;val resumed=SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main){session.debugInjectText("What is two plus two? Just the answer.")}
            withTimeout(30000){while(session.debugTurnCompleteAt<resumed||session.state.value.sending||session.state.value.phase=="Speaking")delay(50)}
            val answer=session.state.value.lines.drop(from).filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
            receipt.writeText(JSONObject().put("yieldMs",yielded).put("localBarges",session.debugLocalBarges-beforeLocal).put("providerInterruptions",session.debugInterruptions-beforeInterrupt).put("stayedStopped",stopped).put("nextAnswer",answer).toString(2))
            assertTrue("Physical output must yield promptly",yielded<2500)
            assertTrue("Old narration must stay stopped",stopped)
            assertTrue("A new question still works: $answer",Regex("(?i)four|4").containsMatchIn(answer))
        }} finally {compose.runOnUiThread{session.suspendSession()}}
    }
    @Test fun ownedSpeechAlsoInterruptsTheCommittedRecommendationPlayback() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk181")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val session=compose.activity.voice
        try {runBlocking {
            compose.runOnUiThread{session.newChat();session.setTextMode(false)}
            withContext(Dispatchers.Main){session.debugInjectText("Recommend three thoughtful science fiction films with one short reason each.")}
            withTimeout(85000){while(session.debugPresentationCommits<1||!session.debugOutputPlaying)delay(20)}
            val began=SystemClock.elapsedRealtime();val before=session.debugInterruptions;val local=session.debugLocalBarges
            val injection=launch(Dispatchers.Main){session.debugInjectUtterance(File(context.getExternalFilesDir(null),"talk-validation/stop.pcm").readBytes(),finishStream=false)}
            withTimeout(7000){while(session.debugOutputPlaying&&session.debugInterruptions==before&&session.debugLocalBarges==local)delay(20)}
            val yielded=SystemClock.elapsedRealtime()-began;injection.join()
            withTimeout(20000){while(session.state.value.sending||session.state.value.phase=="Speaking")delay(50)}
            delay(1000)
            val stopped=!session.debugOutputPlaying
            File(context.getExternalFilesDir(null),"talk-committed-barge-181.json").writeText(JSONObject().put("yieldMs",yielded).put("stayedStopped",stopped).put("interruptions",session.debugInterruptions-before).put("localBarges",session.debugLocalBarges-local).put("cardsRetained",JSONArray(session.state.value.cards.visible.map{it.json()})).toString(2))
            assertTrue("Even a verified shortlist must yield",yielded<2500)
            assertTrue("No stale recommendation audio may restart",stopped)
        }} finally {compose.runOnUiThread{session.suspendSession()}}
    }
    @Test fun spokenCorrectionInterruptsAndAnswersTheNewClosedChoice() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk181")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val session=compose.activity.voice
        session.debugTraceInput=true
        try {runBlocking {
            compose.runOnUiThread{session.newChat();session.setTextMode(false)}
            withContext(Dispatchers.Main){session.debugInjectText("Explain the history of cinema in detail, for at least a minute.")}
            withTimeout(30000){while(!session.debugOutputPlaying||session.debugRenderedSamples<2400)delay(30)}
            val from=session.state.value.lines.size;val began=SystemClock.elapsedRealtime()
            val before=session.debugToolCalls["recommendations"]?:0;val interrupted=session.debugInterruptions
            withContext(Dispatchers.Main){session.debugInjectUtterance(File(context.getExternalFilesDir(null),"talk-validation/choice.pcm").readBytes())}
            withTimeout(60000){while(session.debugTurnCompleteAt<began||session.state.value.sending||session.state.value.phase=="Speaking")delay(60)}
            val answer=session.state.value.lines.drop(from).filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
            val cards=session.state.value.cards.visible
            File(context.getExternalFilesDir(null),"talk-spoken-choice-181.json").writeText(JSONObject()
                .put("transcript",session.state.value.lines.lastOrNull{it.role=="user"}?.text)
                .put("answer",answer).put("interruptions",session.debugInterruptions-interrupted)
                .put("cards",JSONArray(cards.map{it.json()})).put("inputTrace",JSONArray(session.debugInputTrace.toList())).put("tools",JSONObject(session.debugToolCalls.toMap())).toString(2))
            assertTrue("Spoken replacement must interrupt the old answer",session.debugInterruptions>interrupted)
            assertEquals("A spoken closed choice must not become discovery",before,session.debugToolCalls["recommendations"]?:0)
            assertTrue("Answer the new choice: $answer",answer.contains("Arrival",true)||answer.contains("Ex Machina",true))
            val utterance=session.state.value.lines.last{it.role=="user"}.text
            assertTrue("Retain the whole spoken correction: $utterance",utterance.contains("Arrival",true)&&utterance.contains("Ex Machina",true))
            assertFalse("Old narration must not resume inside the correction: $answer",Regex("(?i)history of cinema|long lectures?|specific eras|documentaries").containsMatchIn(answer))
            assertEquals(setOf("movie:329865","movie:264660"),cards.map{it.key}.toSet())
        }} finally {compose.runOnUiThread{session.suspendSession()}}
    }
}
