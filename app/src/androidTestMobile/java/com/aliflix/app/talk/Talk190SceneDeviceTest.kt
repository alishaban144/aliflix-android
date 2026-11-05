package com.aliflix.app.talk

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Genuine typed scene/history questions on actual media. Live titles are opt-in. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Talk190SceneDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun command(action:String, seconds:Double?=null) = JSONObject().put("action",action).apply { seconds?.let { put("numberValue",it) } }
    private fun screenshot(name:String) { i.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    } }
    @Test fun freshSceneAnswersMustCoverEveryRequiredPlotPoint() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveTalk190")=="true")
        val titleCase=args.getString("sceneTitle")?:"dark"
        val title=args.getString("sceneTmdbId")?.toIntOrNull()?.let { id ->
            val kind=args.getString("sceneMediaType") ?: "movie"
            require(id>0 && kind in setOf("movie","tv"))
            Triple(id,kind,requireNotNull(args.getString("sceneTitleName")))
        } ?: when(titleCase) { "big-bang"->Triple(1418,"tv","The Big Bang Theory");"inception"->Triple(27205,"movie","Inception");"arrival"->Triple(329865,"movie","Arrival");else->Triple(70523,"tv","Dark") }
        val positions=(args.getString("sceneSeconds")?:"180,900").split(',').map { it.toDouble() }
        val activity=launchFreshTalkActivity()
        val receipts=JSONArray();var nativePlayer:NativePlayerActivity?=null
        try { runBlocking {
            val native=TalkNativeActions(activity,TalkCatalogue { TalkCardContext() }) {}
            val request=command("play").put("title",title.third).put("tmdbId",title.first).put("kind",title.second)
            if(title.second=="tv")request.put("season",(args.getString("sceneSeason")?:"1").toInt()).put("episode",(args.getString("sceneEpisode")?:"1").toInt())
            val started=withContext(Dispatchers.Main) { native.execute(request).value }
            val done=withTimeout(180000) { TalkTaskWork.get(context).await(started.getString("taskId")) }
            assertTrue(done.toString(),done.optString("status") in setOf("completed","partial"))
            val player=withContext(Dispatchers.Main) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NativePlayerActivity>().single() }
            nativePlayer=player
            if(args.getString("sceneSubtitles")=="off")player.talkCommand(command("subtitle_language").put("stringValue","off"))
            for((index,position) in positions.withIndex()) {
                withContext(Dispatchers.Main) { player.closeTalk() }
                val warmup=args.getString("sceneWarmupSeconds")?.toDoubleOrNull()?:36.0
                player.talkCommand(command("seek",(position-warmup.coerceIn(0.0,36.0)).coerceAtLeast(0.0)))
                player.talkCommand(command("resume"))
                withTimeout(90000) { while(withContext(Dispatchers.Main) { player.playbackController!!.currentPosition/1000.0 < position })delay(80) }
                player.talkCommand(command("pause"))
                val inspectOnly=args.getString("sceneInspectOnly")=="true"
                if(!inspectOnly){
                    withContext(Dispatchers.Main){player.talkVoice.debugTraceInput=true}
                    if(args.getString("sceneUseToolbar")=="true"){
                        val automation=i.uiAutomation
                        fun find(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                            if(node==null)return null
                            if(node.contentDescription?.toString()=="Talk")return node
                            for(n in 0 until node.childCount)find(node.getChild(n))?.let{return it}
                            return null
                        }
                        if(find(automation.rootInActiveWindow)==null){
                            val display=context.resources.displayMetrics;val began=android.os.SystemClock.uptimeMillis()
                            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                                val event=android.view.MotionEvent.obtain(began,android.os.SystemClock.uptimeMillis(),action,display.widthPixels/2f,display.heightPixels/2f,0)
                                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                                try{automation.injectInputEvent(event,true)}finally{event.recycle()}
                            }
                        }
                        val deadline=android.os.SystemClock.elapsedRealtime()+4000
                        var control=find(automation.rootInActiveWindow)
                        while(control==null&&android.os.SystemClock.elapsedRealtime()<deadline){delay(50);control=find(automation.rootInActiveWindow)}
                        assertNotNull("Talk must be available beside the player controls",control)
                        screenshot("talk192-player-toolbar-$titleCase.png")
                        val target=android.graphics.Rect();requireNotNull(control).getBoundsInScreen(target)
                        assertFalse("Talk must have a visible touch target",target.isEmpty)
                        val tapAt=android.os.SystemClock.uptimeMillis()
                        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                            val event=android.view.MotionEvent.obtain(tapAt,android.os.SystemClock.uptimeMillis(),action,target.exactCenterX(),target.exactCenterY(),0)
                            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                            try{automation.injectInputEvent(event,true)}finally{event.recycle()}
                        }
                        delay(100)
                        withContext(Dispatchers.Main){assertTrue("The real toolbar tap must open Talk",player.talkVisible)}
                    }else withContext(Dispatchers.Main){player.openTalk()}
                    withContext(Dispatchers.Main){assertFalse(player.talkVoice.state.value.textMode)}
                    if(args.getString("sceneSpeechFixture")==null){
                        if(args.getString("sceneUseToolbar")=="true"){
                            delay(750)
                            suspend fun tap(key:String){
                                val bounds=withTimeout(5000){while(true){withContext(Dispatchers.Main){player.talkVoice.debugCardBounds[key]}?.let{return@withTimeout it};delay(30)};@Suppress("UNREACHABLE_CODE")error("Missing $key")}
                                val at=android.os.SystemClock.uptimeMillis()
                                for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                                    val event=android.view.MotionEvent.obtain(at,android.os.SystemClock.uptimeMillis(),action,bounds.center.x,bounds.center.y,0)
                                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                                    try{i.uiAutomation.injectInputEvent(event,true)}finally{event.recycle()}
                                }
                            }
                            tap("player-orb");tap("player-type")
                            withTimeout(5000){while(!player.talkVoice.state.value.textMode)delay(30)}
                        }else withContext(Dispatchers.Main){player.talkVoice.setTextMode(true);player.openTalk()}
                    }
                }
                val scene=withContext(Dispatchers.Main) { player.prepareTalkScene()!! }
                val receipt=JSONObject().put("title",title.third).put("scene",scene.metadata)
                    .put("player",withContext(Dispatchers.Main) { player.talkSnapshot() });receipts.put(receipt)
                assertTrue(scene.metadata.toString(),scene.frames.isNotEmpty())
                scene.frames.forEachIndexed { n,frame -> File(context.getExternalFilesDir(null),"talk190-$titleCase-$index-$n.jpg").writeBytes(frame.jpeg) }
                scene.audio?.let { File(context.getExternalFilesDir(null),"talk190-$titleCase-$index.wav").writeBytes(it.wav) }
                if(inspectOnly) {
                    receipt.put("inspectionStory",withContext(Dispatchers.Main) { player.prepareTalkRecall("Recap the story from the beginning",fullStory=true) })
                    screenshot("talk190-inspect-$titleCase-$index.png")
                    continue
                }
                try {
                    withTimeout(25000) { while(withContext(Dispatchers.Main) { player.talkVoice.state.value.phase !in setOf("Listening","Muted") })delay(50) }
                } finally {
                    receipt.put("connection",player.talkVoice.debugConnectionIssue).put("readyState",player.talkVoice.debugAudioSnapshot())
                }
                args.getString("sceneNoiseFixture")?.let { noise ->
                    val identity=player.talkVoice.state.value.conversationId
                    withContext(Dispatchers.Main) { player.talkVoice.debugInjectMicrophonePcm(talkValidationFixture(noise).readBytes()) }
                    delay(1500)
                    assertEquals("Noise must not select another response language","en",player.talkVoice.debugResponseLanguage)
                    assertEquals("Noise must not start a new conversation",identity,player.talkVoice.state.value.conversationId)
                    receipt.put("noiseLanguage",player.talkVoice.debugResponseLanguage).put("noiseRetainedConversation",true)
                }
                val baseline=withContext(Dispatchers.Main) { player.talkVoice.state.value.lines.size }
                val conversationBefore=player.talkVoice.state.value.conversationId
                val before=android.os.SystemClock.elapsedRealtime()
                receipt.put("question", args.getString("sceneQuestion") ?: "Explain why this happened using the earlier setup.")
                val speech=args.getString("sceneSpeechFixture")
                if(speech==null)withContext(Dispatchers.Main) {
                    player.talkVoice.sendText(receipt.getString("question"))
                    player.currentFocus?.clearFocus()
                    // The real Send action dismisses the IME; direct session
                    // injection must reproduce that viewport for visual review.
                    androidx.core.view.WindowCompat.getInsetsController(player.window,player.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                else launch(Dispatchers.Main){ player.talkVoice.debugInjectMicrophonePcm(talkValidationFixture(speech).readBytes()) }
                var first=0L
                val answer=withTimeout(65000) {
                    while(true) {
                        val state=withContext(Dispatchers.Main) { player.talkVoice.state.value }
                        val answer=state.lines.drop(baseline).filter { it.role=="assistant" }.joinToString(" ") { it.text }
                        receipt.put("lastPhase",state.phase).put("lastAnswer",answer).put("connectionIssue",player.talkVoice.debugConnectionIssue)
                        if(first==0L&&answer.length>30)first=android.os.SystemClock.elapsedRealtime()-before
                        if(state.answerComplete&&answer.length>70)return@withTimeout answer
                        delay(40)
                    }
                    @Suppress("UNREACHABLE_CODE") error("No answer")
                }
                receipt.put("answer",answer).put("firstResponseMs",first).put("completedMs",android.os.SystemClock.elapsedRealtime()-before)
                    .put("sceneWireChars",withContext(Dispatchers.Main){player.talkScene()?.parts(false)?.optJSONObject(0)?.optString("text")?.length?:0})
                    .put("recallMs",player.talkVoice.debugSceneRecallMs).put("observationMs",player.talkVoice.debugSceneObservationMs)
                    .put("questionSentMs",player.talkVoice.debugQuestionSentAt-before)
                    .put("framesSent",player.talkVoice.debugSceneFrames).put("tools",JSONObject(player.talkVoice.debugToolCalls.toMap()))
                    .put("deliveredScene",player.talkVoice.debugSceneContext).put("responseLanguage",player.talkVoice.debugResponseLanguage)
                withContext(Dispatchers.Main) { player.talkRecallFrames(receipt.getString("question")) }.forEachIndexed { n, frame ->
                    File(context.getExternalFilesDir(null),"talk190-$titleCase-$index-recall-$n.jpg").writeBytes(frame.jpeg)
                }
                assertEquals("en",player.talkVoice.debugResponseLanguage)
                assertTrue("No transcript recall supplied",player.talkVoice.debugSceneContext?.optJSONObject("episodeRecall")?.optJSONObject("storyThroughAnchor")?.optInt("includedCues",0)?.let { it > 0 } == true)
                if(talkNeedsCurrentSceneObservation(receipt.getString("question"))){
                    val context=player.talkVoice.debugSceneContext
                    val frames=context?.optJSONArray("attachedCurrentFrameSeconds")?.length()?:0
                    val named=context?.optJSONObject("storyUnderstanding")
                    val groundedIdentity=talkCurrentIdentityQuestion(receipt.getString("question"))&&frames>0&&
                        named?.optString("status")=="grounded"&&(named.optJSONArray("characters")?.length()?:0)>0
                    assertTrue("Missing temporal or independently bound identity evidence; see talk190-live-$titleCase.json",
                        groundedIdentity||frames>=3||context?.optJSONObject("groundedObservation")?.optBoolean("titleBlind")==true)
                }
                assertTrue(answer,answer.length>100)
                assertFalse(answer,Regex("(?i)trouble.{0,45}retriev|unable to (?:access|see)|cannot (?:access|see)|can't (?:access|see)|don't have.{0,20}(?:scene|details)").containsMatchIn(answer))
                val timely=first in 1..if(speech==null)12000 else 25000
                receipt.put("latencyWithinBudget",timely)
                if(speech!=null){assertTrue("Missing rendered spoken scene answer",player.talkVoice.debugRenderedSamples>10000);receipt.put("speechInput",true).put("renderedSamples",player.talkVoice.debugRenderedSamples).put("recognizedQuestion",player.talkVoice.state.value.lines.lastOrNull{it.role=="user"}?.text)}
                assertTrue(player.talkVoice.debugSceneFrames>0)
                if(args.getString("sceneSubtitles")=="off") {
                    assertNull("Talk changed the viewer subtitle choice",withContext(Dispatchers.Main) { player.talkSnapshot().opt("subtitles") }.takeUnless { it == JSONObject.NULL })
                    assertEquals(0,scene.metadata.getJSONArray("dialogue").length())
                    assertTrue(scene.metadata.has("playedAudio"))
                    assertEquals("retrieved_timed_captions",player.talkVoice.debugSceneContext!!.getJSONObject("episodeRecall").getString("captionSource"))
                }
                if(speech!=null) {
                    withTimeout(30000){while(player.talkVoice.debugAudioSnapshot().let{it.optBoolean("speaking")||it.optBoolean("writing")||it.optInt("queued")>0})delay(40)}
                    val final=player.talkVoice.debugAudioSnapshot()
                    receipt.put("spokenOutputDrained",final).put("sameConversation",conversationBefore==player.talkVoice.state.value.conversationId)
                    assertEquals(conversationBefore,player.talkVoice.state.value.conversationId)
                    assertTrue("Speech must render beyond the first fragment",final.optLong("rendered")>50000)
                    assertFalse("Speaker echo interrupted the scene reply",player.talkVoice.debugActivityEvents.any{it.optBoolean("barge")})
                }
                val expected = args.getString("sceneExpected")?.split(';')?.getOrNull(index)
                val missing=expected?.split("~")?.filterNot { Regex("(?i)(?:$it)").containsMatchIn(answer) }.orEmpty()
                receipt.put("missingRequiredPoints",JSONArray(missing))
                assertNotNull(withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds["player-exchange"] })
                assertNull(withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds["player-full-input"] })
                assertFalse(withContext(Dispatchers.Main) { player.playbackController!!.isPlaying })
                delay(600)
                receipt.put("uiBounds",JSONObject().apply{player.talkVoice.debugCardBounds.forEach{(key,b)->put(key,JSONArray(listOf(b.left,b.top,b.right,b.bottom)))}})
                screenshot("talk190-live-$titleCase-$index.png")
                args.getString("sceneFollowUp")?.let { follow ->
                    val id=player.talkVoice.state.value.conversationId
                    if(args.getString("sceneForceQuotaRotate")=="true") {
                        withContext(Dispatchers.Main){player.talkVoice.debugQuotaRecovery()}
                        withTimeout(20000){while(player.talkVoice.state.value.phase !in setOf("Listening","Muted"))delay(40)}
                        assertEquals(id,player.talkVoice.state.value.conversationId)
                        assertEquals("en",player.talkVoice.debugResponseLanguage)
                        receipt.put("forcedQuotaRecovery",true)
                    }
                    val from=player.talkVoice.state.value.lines.size
                    val started=android.os.SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main){player.talkVoice.sendText(follow)}
                    val text=withTimeout(45000){
                        while(true){
                            val state=withContext(Dispatchers.Main){player.talkVoice.state.value}
                            val text=state.lines.drop(from).filter{it.role=="assistant"}.joinToString(" "){it.text}
                            if(state.answerComplete&&text.length>50)return@withTimeout text
                            delay(40)
                        }
                        @Suppress("UNREACHABLE_CODE") error("Missing follow-up")
                    }
                    receipt.put("followUp",JSONObject().put("question",follow).put("answer",text)
                        .put("completeMs",android.os.SystemClock.elapsedRealtime()-started).put("sameConversation",id==player.talkVoice.state.value.conversationId))
                    assertEquals(id,player.talkVoice.state.value.conversationId)
                    assertEquals("en",player.talkVoice.debugResponseLanguage)
                    args.getString("sceneFollowExpected")?.split("~")?.forEach { point -> assertTrue("Follow-up missing $point: $text",Regex("(?i)(?:$point)").containsMatchIn(text)) }
                    delay(600);screenshot("talk191-followup-$titleCase.png")
                }

                withContext(Dispatchers.Main){player.talkScene()}?.frames?.forEachIndexed{n,frame->
                    File(context.getExternalFilesDir(null),"talk192-final-$titleCase-$index-$n.jpg").writeBytes(frame.jpeg)
                }
                assertTrue("Scene answer took $first ms: $answer",timely)
                assertTrue("Missing required points $missing: $answer",missing.isEmpty())
            }
        } } finally {
            if(receipts.length()>0&&nativePlayer!=null)receipts.getJSONObject(receipts.length()-1)
                .put("tools",JSONObject(nativePlayer!!.talkVoice.debugToolCalls.toMap()))
                .put("deliveredScene",nativePlayer!!.talkVoice.debugSceneContext)
                .put("wireEvents",JSONArray(nativePlayer!!.talkVoice.debugWireEvents.toList()))
                .put("recallMs",nativePlayer!!.talkVoice.debugSceneRecallMs).put("observationMs",nativePlayer!!.talkVoice.debugSceneObservationMs)
                .put("questionSentAt",nativePlayer!!.talkVoice.debugQuestionSentAt)
                .put("finalAudio",nativePlayer!!.talkVoice.debugAudioSnapshot())
                .put("finalConnectionIssue",nativePlayer!!.talkVoice.debugConnectionIssue)
                .put("observationIssue",nativePlayer!!.talkVoice.debugSceneObservationIssue)
                .put("storyIssue",nativePlayer!!.talkVoice.javaClass.methods.firstOrNull{it.name.startsWith("getDebugSceneStoryIssue")}?.invoke(nativePlayer!!.talkVoice))
                .put("storyMs",nativePlayer!!.talkVoice.javaClass.methods.firstOrNull{it.name.startsWith("getDebugSceneStoryMs")}?.invoke(nativePlayer!!.talkVoice))
                .put("inputTrace",JSONArray(nativePlayer!!.talkVoice.debugInputTrace.toList()))
                .put("allLines",JSONArray(nativePlayer!!.talkVoice.state.value.lines.map { JSONObject().put("role",it.role).put("text",it.text) }))
            File(context.getExternalFilesDir(null),"talk190-live-$titleCase.json").writeText(receipts.toString(2))
            i.runOnMainSync { nativePlayer?.closeTalk();nativePlayer?.finish();activity.finish() }
            context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP))
        }
    }
}
