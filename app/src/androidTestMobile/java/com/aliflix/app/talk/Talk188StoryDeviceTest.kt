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
class Talk188StoryDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun command(action:String, seconds:Double?=null) = JSONObject().put("action",action).apply { seconds?.let { put("numberValue",it) } }
    private fun fixture(body:suspend (NativePlayerActivity)->Unit) {
        grantNativeFixtureNetworkPermission()
        val bytes=i.context.assets.open("cast-test.mp4").use { it.readBytes() }
        NativeBackgroundPlaybackTest.FixtureServer(bytes).use { server ->
            val request=NativePlaybackRequest(server.url,"video/mp4","https://fixture.aliflix.test/","Aliflix scene fixture","","Scene acceptance",0,true)
            val file=File(context.cacheDir,"native-request-${UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
            try {
                ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("requestFile",file.name).putExtra("validationFreshHistory",true),nativePhoneLaunchOptions()).use { scenario ->
                    lateinit var player:NativePlayerActivity
                    scenario.onActivity { player=it }
                    runBlocking {
                        withTimeout(25000) { while(withContext(Dispatchers.Main) { player.playbackController?.isPlaying!=true }) delay(60) }
                        try { body(player) } finally { withContext(Dispatchers.Main) { player.closeTalk() } }
                    }
                }
            } finally { context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP)); file.delete() }
        }
    }
    private fun screenshot(name:String) { i.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    } }
    @Test fun retrievesEarlierUnplayedVideoWithoutMovingTheViewerClock()=fixture { player ->
        player.talkCommand(command("seek",40.0));player.talkCommand(command("pause"));delay(600)
        val request=NativePlaybackService.activeRequest!!
        val before=withContext(Dispatchers.Main) { player.playbackController!!.currentPosition }
        val frames=PlayerTalkVideo(context).frames("fixture",request,listOf(5.0,12.0,20.0,60.0),40.0) { true }
        assertTrue("No independently decoded earlier footage",frames.size>=3)
        assertTrue(frames.all { it.seconds<=40 && it.jpeg.isNotEmpty() })
        assertTrue(kotlin.math.abs(before-withContext(Dispatchers.Main) { player.playbackController!!.currentPosition })<100)
        assertFalse(withContext(Dispatchers.Main) { player.playbackController!!.isPlaying })
    }
    @Test fun liveSceneExplanationsUseActualFramesAndDialogueAcrossFreshSceneChanges() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveTalk188")=="true")
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
                player.talkCommand(command("seek",(position-36).coerceAtLeast(0.0)))
                player.talkCommand(command("resume"))
                withTimeout(90000) { while(withContext(Dispatchers.Main) { player.playbackController!!.currentPosition/1000.0 < position })delay(80) }
                player.talkCommand(command("pause"))
                val inspectOnly=args.getString("sceneInspectOnly")=="true"
                if(!inspectOnly)withContext(Dispatchers.Main) { player.talkVoice.debugTraceInput=true;player.talkVoice.setTextMode(true);player.openTalk() }
                val scene=withContext(Dispatchers.Main) { player.prepareTalkScene()!! }
                val receipt=JSONObject().put("title",title.third).put("scene",scene.metadata)
                    .put("player",withContext(Dispatchers.Main) { player.talkSnapshot() });receipts.put(receipt)
                assertTrue(scene.metadata.toString(),scene.frames.isNotEmpty())
                scene.frames.forEachIndexed { n,frame -> File(context.getExternalFilesDir(null),"talk188-$titleCase-$index-$n.jpg").writeBytes(frame.jpeg) }
                scene.audio?.let { File(context.getExternalFilesDir(null),"talk188-$titleCase-$index.wav").writeBytes(it.wav) }
                if(inspectOnly) {
                    receipt.put("inspectionStory",withContext(Dispatchers.Main) { player.prepareTalkRecall("Recap the story from the beginning",fullStory=true) })
                    screenshot("talk188-inspect-$titleCase-$index.png")
                    continue
                }
                try {
                    withTimeout(25000) { while(withContext(Dispatchers.Main) { player.talkVoice.state.value.phase !in setOf("Listening","Muted") })delay(50) }
                } finally {
                    receipt.put("connection",player.talkVoice.debugConnectionIssue).put("readyState",player.talkVoice.debugAudioSnapshot())
                }
                val baseline=withContext(Dispatchers.Main) { player.talkVoice.state.value.lines.size }
                val before=android.os.SystemClock.elapsedRealtime()
                receipt.put("question", args.getString("sceneQuestion") ?: "What happened immediately before this scene, and how does it connect to what is happening now?")
                withContext(Dispatchers.Main) { player.talkVoice.sendText(receipt.getString("question")) }
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
                    .put("recallMs",player.talkVoice.debugSceneRecallMs).put("observationMs",player.talkVoice.debugSceneObservationMs)
                    .put("questionSentMs",player.talkVoice.debugQuestionSentAt-before)
                    .put("framesSent",player.talkVoice.debugSceneFrames).put("tools",JSONObject(player.talkVoice.debugToolCalls.toMap()))
                    .put("deliveredScene",player.talkVoice.debugSceneContext).put("responseLanguage",player.talkVoice.debugResponseLanguage)
                withContext(Dispatchers.Main) { player.talkRecallFrames(receipt.getString("question")) }.forEachIndexed { n, frame ->
                    File(context.getExternalFilesDir(null),"talk188-$titleCase-$index-recall-$n.jpg").writeBytes(frame.jpeg)
                }
                assertEquals("en",player.talkVoice.debugResponseLanguage)
                assertTrue("No transcript recall supplied",player.talkVoice.debugSceneContext?.optJSONObject("episodeRecall")?.optJSONObject("storyThroughAnchor")?.optInt("includedCues",0)?.let { it > 0 } == true)
                if(talkNeedsCurrentSceneObservation(receipt.getString("question")))
                    assertTrue("Missing independent scene observation; see talk188-live-$titleCase.json",player.talkVoice.debugSceneContext?.optJSONObject("groundedObservation")?.optBoolean("titleBlind")==true)
                assertTrue(answer,answer.length>100)
                assertFalse(answer,Regex("(?i)trouble.{0,45}retriev|unable to (?:access|see)|cannot (?:access|see)|can't (?:access|see)|don't have.{0,20}(?:scene|details)").containsMatchIn(answer))
                assertTrue("Scene answer took $first ms: $answer",first in 1..12000)
                assertTrue(player.talkVoice.debugSceneFrames>0)
                if(args.getString("sceneSubtitles")=="off") {
                    assertNull("Talk changed the viewer subtitle choice",withContext(Dispatchers.Main) { player.talkSnapshot().opt("subtitles") }.takeUnless { it == JSONObject.NULL })
                    assertEquals(0,scene.metadata.getJSONArray("dialogue").length())
                    assertTrue(scene.metadata.has("playedAudio"))
                    assertEquals("retrieved_timed_captions",player.talkVoice.debugSceneContext!!.getJSONObject("episodeRecall").getString("captionSource"))
                }
                val expected = args.getString("sceneExpected")?.split(';')?.getOrNull(index)
                if(expected!=null)assertTrue(answer,Regex("(?i)(?:$expected)").containsMatchIn(answer))
                assertFalse(withContext(Dispatchers.Main) { player.playbackController!!.isPlaying })
                screenshot("talk188-live-$titleCase-$index.png")
            }
        } } finally {
            if(receipts.length()>0&&nativePlayer!=null)receipts.getJSONObject(receipts.length()-1)
                .put("tools",JSONObject(nativePlayer!!.talkVoice.debugToolCalls.toMap()))
                .put("deliveredScene",nativePlayer!!.talkVoice.debugSceneContext)
                .put("wireEvents",JSONArray(nativePlayer!!.talkVoice.debugWireEvents.toList()))
                .put("recallMs",nativePlayer!!.talkVoice.debugSceneRecallMs).put("observationMs",nativePlayer!!.talkVoice.debugSceneObservationMs)
                .put("questionSentAt",nativePlayer!!.talkVoice.debugQuestionSentAt)
                .put("finalAudio",nativePlayer!!.talkVoice.debugAudioSnapshot())
                .put("observationIssue",nativePlayer!!.talkVoice.debugSceneObservationIssue)
                .put("inputTrace",JSONArray(nativePlayer!!.talkVoice.debugInputTrace.toList()))
                .put("allLines",JSONArray(nativePlayer!!.talkVoice.state.value.lines.map { JSONObject().put("role",it.role).put("text",it.text) }))
            File(context.getExternalFilesDir(null),"talk188-live-$titleCase.json").writeText(receipts.toString(2))
            i.runOnMainSync { nativePlayer?.closeTalk();nativePlayer?.finish();activity.finish() }
            context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP))
        }
    }
}
