package com.aliflix.app.talk

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Dedicated scene and palette acceptance. Live films are opt-in and never run in generic CI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Talk187SceneDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun command(action:String, seconds:Double?=null) = JSONObject().put("action",action).apply { seconds?.let { put("numberValue",it) } }
    private fun visibleText(node:android.view.accessibility.AccessibilityNodeInfo?):String {
        if(node==null)return ""
        return node.text.toString()+" "+node.contentDescription.toString()+" "+(0 until node.childCount).joinToString(" "){visibleText(node.getChild(it))}
    }
    private fun currentVisibleText(): String {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return visibleText(i.uiAutomation.rootInActiveWindow)
    }
    private fun fixture(body:suspend (NativePlayerActivity)->Unit) {
        i.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
        grantNativeFixtureNetworkPermission()
        val bytes=i.context.assets.open("cast-test.mp4").use { it.readBytes() }
        NativeBackgroundPlaybackTest.FixtureServer(bytes).use { server ->
            val request=NativePlaybackRequest(server.url,"video/mp4","https://fixture.aliflix.test/","Aliflix scene fixture","","Scene acceptance",0,true)
            val file=File(context.cacheDir,"native-request-${UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
            try {
                ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("requestFile",file.name).putExtra("validationFreshHistory",true),nativePhoneLaunchOptions()).use { scenario ->
                    lateinit var player:NativePlayerActivity
                    scenario.onActivity { player=it;player.talkVoice.debugOfflineConnection=!(InstrumentationRegistry.getArguments().getString("liveTalk187")=="true") }
                    runBlocking {
                        withTimeout(25000) { while(withContext(Dispatchers.Main) { player.playbackController?.isPlaying!=true }) delay(60) }
                        try { body(player) } finally { withContext(Dispatchers.Main) { player.closeTalk() } }
                    }
                }
            } finally { context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP)); file.delete() }
        }
    }
    private suspend fun bound(player:NativePlayerActivity,key:String):androidx.compose.ui.geometry.Rect = try { withTimeout(6000) {
        while(true) { withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds[key] }?.let { return@withTimeout it };delay(30) }
        @Suppress("UNREACHABLE_CODE") error(key)
    } } catch(error:TimeoutCancellationException) { throw AssertionError("Missing $key phase=${player.talkVoice.state.value.phase} bounds=${player.talkVoice.debugCardBounds.keys}",error) }
    private fun tap(rect:androidx.compose.ui.geometry.Rect) {
        val time=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,rect.center.x,rect.center.y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(event);event.recycle()
        }
    }
    private fun screenshot(name:String) { i.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    } }
    @Test fun playerFramesStayLocalUntilRequestedAndSeekDiscardsThePreviousScene()=fixture { player ->
        delay(3000)
        val before=withContext(Dispatchers.Main) { player.prepareTalkScene()!! }
        assertTrue(before.metadata.toString(),before.frames.isNotEmpty())
        assertTrue(before.frames.all { it.jpeg.size in 200..160000 && it.seconds<=before.anchorSeconds })
        assertEquals(0,withContext(Dispatchers.Main) { player.talkVoice.debugSceneFrames })
        assertFalse(before.metadata.toString().contains("http"))
        player.talkCommand(command("seek",40.0));delay(700)
        val after=withContext(Dispatchers.Main) { player.prepareTalkScene()!! }
        assertTrue(after.revision>before.revision)
        assertTrue(after.frames.isNotEmpty())
        assertTrue(after.frames.all { it.seconds>=39 && it.seconds<=after.anchorSeconds })
        player.talkCommand(command("seek",4.0));delay(700)
        val rewind=withContext(Dispatchers.Main) { player.prepareTalkScene()!! }
        assertTrue(rewind.frames.all { it.seconds<8 })
        assertFalse(rewind.key==after.key)
    }
    @Test fun modeToggleRestoresTheSameVoiceConversationAndMicrophone()=fixture { player ->
        val live=InstrumentationRegistry.getArguments().getString("liveTalk187")=="true"
        val receipts=JSONArray()
        withContext(Dispatchers.Main) { player.openTalk() }
        val id=player.talkVoice.state.value.conversationId
        delay(700);tap(bound(player,"player-orb"))
        repeat(2) {
            tap(bound(player,"player-type"));delay(500)
            assertTrue(player.talkVoice.state.value.textMode)
            assertTrue(player.talkVoice.state.value.muted)
            tap(bound(player,"player-type"))
            withTimeout(25000) { while(withContext(Dispatchers.Main) {
                player.talkVoice.state.value.phase!="Listening" || live&&player.talkVoice.debugAudioSnapshot().optInt("recordSource",-1)<0
            })delay(40) }
            assertFalse(player.talkVoice.state.value.textMode);assertFalse(player.talkVoice.state.value.muted)
            assertEquals(id,player.talkVoice.state.value.conversationId)
            assertEquals("en",player.talkVoice.debugResponseLanguage)
            assertFalse(withContext(Dispatchers.Main){player.talkSnapshot().optBoolean("playing")})
            receipts.put(withContext(Dispatchers.Main){player.talkVoice.debugAudioSnapshot()})
        }
        File(context.getExternalFilesDir(null),"talk191-mode-microphone.json").writeText(receipts.toString(2))
        screenshot("talk191-single-mode-control.png")
    }
    @Test fun underlyingCaptionsCannotStealTalkBubbleGestures()=fixture { player ->
        withContext(Dispatchers.Main) {
            player.playbackController!!.pause()
            NativePlaybackService.updateSubtitles("WEBVTT\n\n00:00:00.000 --> 00:01:20.000\nCAPTIONS UNDER THE TALK BUBBLE\n\n", "en", "English")
        }
        withTimeout(10000) { while(withContext(Dispatchers.Main) { player.captionBounds()==null }) delay(40) }
        withContext(Dispatchers.Main) {
            assertNotNull("Caption dragging works before Talk",player.captionBounds())
            player.openTalk()
            assertFalse("The caption fixture must remain present",NativePlaybackService.currentCaptionCues().isEmpty())
            assertNull("Underlying captions must not intercept Talk touches",player.captionBounds())
            player.closeTalk()
            assertNotNull("Caption dragging is restored after Talk closes",player.captionBounds())
        }
    }
    @Test fun reopeningStartsAsSpeechAndKeepsEarlierHistoryOutOfTheNewExchange()=fixture { player ->
        withContext(Dispatchers.Main) { player.openTalk() }
        assertFalse(player.talkVoice.state.value.textMode)
        delay(800);tap(bound(player,"player-orb"));delay(250);tap(bound(player,"player-type"));delay(350)
        assertTrue(player.talkVoice.state.value.textMode)
        withContext(Dispatchers.Main) {
            player.talkVoice.debugPreviewUserQuestion("Earlier question retained in this chat")
            player.closeTalk()
        }
        delay(450)
        val id=player.talkVoice.state.value.conversationId
        withContext(Dispatchers.Main) { player.openTalk() }
        delay(800)
        assertFalse(player.talkVoice.state.value.textMode)
        assertEquals(id,player.talkVoice.state.value.conversationId)
        assertFalse(withContext(Dispatchers.Main){player.talkSnapshot().optBoolean("playing")})
        assertFalse(visibleText(i.uiAutomation.rootInActiveWindow).contains("Earlier question retained in this chat"))
        assertFalse(visibleText(i.uiAutomation.rootInActiveWindow).contains("Stop reply"))
        screenshot("talk191-reopened-voice.png")
    }
    @Test fun speechKeepsTextHiddenUntilTheOrbIsTapped()=fixture { player ->
        val question = "Who is the woman near the window?"
        val answer = "The woman is standing beside the window."
        withContext(Dispatchers.Main) { player.openTalk() }
        delay(450)
        withContext(Dispatchers.Main) {
            player.talkVoice.debugPreviewUserQuestion(question)
            player.talkVoice.debugPreviewOrbContext("Speaking",answer)
        }
        delay(350)
        assertFalse(currentVisibleText().contains(answer))
        tap(bound(player,"player-orb"));delay(450)
        assertTrue(currentVisibleText().contains(answer))
        tap(bound(player,"player-orb"));delay(450)
        assertFalse(currentVisibleText().contains(answer))
    }

    @Test fun liveMicrophoneStartsAndReconnectsAfterAnImmediateClose()=fixture { player ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk187")=="true")
        withContext(Dispatchers.Main) { player.openTalk();player.closeTalk() }
        repeat(3) { attempt ->
            val start = android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { player.openTalk() }
            withTimeout(2500) { player.talkVoice.state.first { it.microphoneReady } }
            val captured = android.os.SystemClock.elapsedRealtime()-start
            withTimeout(20_000) {
                while(!withContext(Dispatchers.Main) { player.talkVoice.playerVoiceReady() })delay(20)
            }
            val connected = android.os.SystemClock.elapsedRealtime()-start
            android.util.Log.i("TalkPlayerTiming","opening=$attempt captureMs=$captured connectedMs=$connected")
            withContext(Dispatchers.Main) { player.closeTalk() }
            assertFalse(player.talkVoice.state.value.microphoneReady)
        }
    }

    @Test fun livePausedFrameCanDirectTheOrbToSuccessiveVisibleDetails()=fixture { player ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk187")=="true")
        val voice=player.talkVoice
        withContext(Dispatchers.Main) { player.openTalk() }
        withTimeout(20_000) {
            while(!withContext(Dispatchers.Main) { voice.playerVoiceReady() && player.talkAttentionFrame()!=null })delay(20)
        }
        for((fixture, visual) in listOf("visible" to true, "unrelated" to false)) {
            val cues = linkedSetOf<TalkFrameTarget>()
            coroutineScope {
                val monitor = launch {
                    voice.state.collect { value -> value.frameTarget?.let(cues::add) }
                }
                try {
                    val baseline=voice.state.value.lines.count { it.role=="assistant" }
                    withContext(Dispatchers.Main) {
                        voice.debugInjectMicrophonePcm(talkValidationFixture("talk198-$fixture.pcm").readBytes())
                    }
                    withTimeout(45_000) {
                        while (voice.state.value.let {
                            !it.answerComplete || it.lines.count { line -> line.role=="assistant" }<=baseline ||
                                it.phase !in setOf("Listening","Muted")
                        }) delay(40)
                    }
                } finally { monitor.cancelAndJoin() }
            }
            android.util.Log.i("TalkPlayerTiming","fixture=$fixture cues=${cues.size} calls=${voice.debugToolCalls["scene_attention"] ?: 0}")
            if(visual)assertTrue("Expected multiple visible details: $cues",cues.size>=2)
            else assertTrue("An unrelated answer moved the orb: $cues",cues.isEmpty())
            assertFalse(visibleText(i.uiAutomation.rootInActiveWindow).contains(
                voice.state.value.lines.last { it.role=="assistant" }.text.take(40)))
            screenshot("talk198-$fixture.png")
        }
    }

    @Test fun pausedFrameCuesFollowTheSpokenOrderAndRejectOtherFrames() {
        fun cue(mention:String, vararg box:Int) = JSONObject()
            .put("mention",mention).put("box",JSONArray(box.toList()))
        val args = JSONObject().put("frameId","pause")
            .put("targets",JSONArray(listOf(
                cue("blonde woman",110,120,360,310),
                cue("red car",440,540,820,910),
                cue("window",370,20,700,220),
                cue("off-screen person",0,0,1300,900),
            )))
        assertFalse(TalkFrameAttention(args,"previous-pause").available)
        val plan = TalkFrameAttention(args,"pause")
        plan.update("The blonde woman turns around. Then the red car passes the window.")
        plan.receive(240_000, 0)
        plan.finish()
        assertNull(plan.at(-1))
        assertEquals(.12f,plan.at(30_000)?.left ?: -1f,0.001f)
        assertEquals(.54f,plan.at(160_000)?.left ?: -1f,0.001f)
        assertEquals(.02f,plan.at(220_000)?.left ?: -1f,0.001f)
        assertNull(plan.at(240_000))
    }
    @Test fun paletteRetainsTheOrbAnchorAndRealTargetsAcrossBothOrientations()=fixture { player ->
        withContext(Dispatchers.Main) { player.openTalk() }
        for((orientation,name) in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
            withContext(Dispatchers.Main) { player.requestedOrientation=orientation };delay(900)
            val orb=bound(player,"player-orb");tap(orb);delay(450)
            val anchored=bound(player,"player-orb")
            assertEquals(orb.center.x.toDouble(),anchored.center.x.toDouble(),2.0)
            assertEquals(orb.center.y.toDouble(),anchored.center.y.toDouble(),2.0)
            val palette=bound(player,"player-palette")
            assertFalse("Palette overlaps orb: $palette $anchored",palette.overlaps(anchored))
            val targets=listOf("player-type","player-close").map { bound(player,it) }
            val size=withContext(Dispatchers.Main) { 48*player.resources.displayMetrics.density }
            for(target in targets) { assertTrue(target.toString(),target.width>=size-2 && target.height>=size-2);assertTrue(palette.contains(target.center)) }
            for(a in targets.indices)for(b in a+1 until targets.size)assertFalse(targets[a].overlaps(targets[b]))
            for(removed in listOf("player-explain","player-replay","player-dialogue","player-microphone","player-expand"))
                assertNull("Removed upper-row control is still present: $removed",withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds[removed] })
            screenshot("talk187-palette-$name.png")
            tap(bound(player,"player-type"));delay(900)
            val input=withTimeout(6000) {
                while(true) {
                    val rectangle=withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds["player-full-input"]?:player.talkVoice.debugCardBounds["player-input"] }
                    if(rectangle!=null&&rectangle.height>=size-2)return@withTimeout rectangle
                    delay(40)
                }
                @Suppress("UNREACHABLE_CODE") error("No settled input")
            }
            screenshot("talk187-composer-$name.png")
            assertNotNull(input);assertTrue("$name input=$input minimum=$size",input!!.height>=size-2)
            i.sendStringSync("$name draft")
            delay(350)
            assertTrue("$name composer lost focus during keyboard resize",visibleText(i.uiAutomation.rootInActiveWindow).contains("$name draft"))
            i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);delay(250)
            i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);delay(250)
            if(withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds["player-type"]!=null }) { tap(bound(player,"player-orb"));delay(250) }
        }
    }
    @Test fun typedDraftSurvivesExpansionAndSendingMakesTheConversationVisible()=fixture { player ->
        withContext(Dispatchers.Main) { player.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;player.openTalk() }
        delay(900);tap(bound(player,"player-orb"));delay(300);tap(bound(player,"player-type"));delay(700)
        val question="Why did she leave?"
        i.sendStringSync(question);delay(150)
        screenshot("talk187-draft-small.png")
        assertNull(withContext(Dispatchers.Main) { player.talkVoice.debugCardBounds["player-expand"] });delay(400)
        screenshot("talk187-draft-expanded.png")
        fun visibleText(node:android.view.accessibility.AccessibilityNodeInfo?):String = node?.let { root ->
            listOf(root.text?.toString().orEmpty(),*(0 until root.childCount).map { visibleText(root.getChild(it)) }.toTypedArray()).joinToString(" ")
        }.orEmpty()
        val expandedText=visibleText(i.uiAutomation.rootInActiveWindow)
        assertTrue("Draft lost on expansion: $expandedText",expandedText.contains(question,ignoreCase=true))
        tap(bound(player,"player-orb"));delay(500)
        tap(bound(player,"player-orb"));delay(300)
        tap(bound(player,"player-type"));bound(player,"player-input");delay(500)
        val returnedText=visibleText(i.uiAutomation.rootInActiveWindow)
        screenshot("talk187-draft-returned.png")
        assertTrue("Draft lost on return: $returnedText",returnedText.contains(question,ignoreCase=true))
        tap(bound(player,"player-send"));bound(player,"player-exchange")
        withTimeout(3000) { while(withContext(Dispatchers.Main) { player.talkVoice.state.value.lines.none { it.role=="user"&&it.text==question } })delay(30) }
        withContext(Dispatchers.Main) { player.talkVoice.stopAnswer() }
        assertFalse(withContext(Dispatchers.Main) { player.playbackController!!.isPlaying })
        screenshot("talk187-text-reply-visible.png")
    }
    @Test fun liveObserverAcceptsTheRealDecoderClipAndReturnsTitleBlindEvidence()=fixture { player ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk187")=="true")
        withTimeout(30000) { while(withContext(Dispatchers.Main) { player.playbackController!!.currentPosition < 14000 }) delay(80) }
        player.talkCommand(command("pause"))
        withContext(Dispatchers.Main) { player.openTalk();player.talkVoice.setTextMode(true);player.openTalk() }
        withTimeout(25000) { while(withContext(Dispatchers.Main) { player.talkVoice.state.value.phase !in setOf("Listening","Muted") })delay(40) }
        val began=android.os.SystemClock.elapsedRealtime()
        val scene=withContext(Dispatchers.Main) { player.talkVoice.debugInspectScene() }
        val receipt=JSONObject().put("scene",scene).put("issue",player.talkVoice.debugSceneObservationIssue)
            .put("elapsedMs",android.os.SystemClock.elapsedRealtime()-began)
        File(context.getExternalFilesDir(null),"talk187-observer-transport.json").writeText(receipt.toString(2))
        assertTrue("Observer failed: ${player.talkVoice.debugSceneObservationIssue}; see talk187-observer-transport.json",scene?.optJSONObject("groundedObservation")?.optBoolean("titleBlind")==true)
        assertTrue("Missing actual decoder audio: ${scene?.optString("captureDiagnostic")}",scene?.has("playedAudio")==true)
    }
    @Test fun liveSceneExplanationsUseActualFramesAndDialogueAcrossFreshSceneChanges() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveTalk187")=="true")
        val titleCase=args.getString("sceneTitle")?:"dark"
        val title=when(titleCase) { "big-bang"->Triple(1418,"tv","The Big Bang Theory");"inception"->Triple(27205,"movie","Inception");"arrival"->Triple(329865,"movie","Arrival");else->Triple(70523,"tv","Dark") }
        val positions=(args.getString("sceneSeconds")?:"180,900").split(',').map { it.toDouble() }
        val activity=launchFreshTalkActivity()
        val receipts=JSONArray();var nativePlayer:NativePlayerActivity?=null
        try { runBlocking {
            val native=TalkNativeActions(activity,TalkCatalogue { TalkCardContext() }) {}
            val request=command("play").put("title",title.third).put("tmdbId",title.first).put("kind",title.second)
            if(title.second=="tv")request.put("season",1).put("episode",1)
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
                withContext(Dispatchers.Main) { player.talkVoice.debugTraceInput=true;player.talkVoice.setTextMode(args.getString("sceneVoice")!="true");player.openTalk() }
                val scene=withContext(Dispatchers.Main) { player.prepareTalkScene()!! }
                val receipt=JSONObject().put("title",title.third).put("scene",scene.metadata);receipts.put(receipt)
                assertTrue(scene.metadata.toString(),scene.frames.isNotEmpty())
                scene.frames.forEachIndexed { n,frame -> File(context.getExternalFilesDir(null),"talk187-$titleCase-$index-$n.jpg").writeBytes(frame.jpeg) }
                scene.audio?.let { File(context.getExternalFilesDir(null),"talk187-$titleCase-$index.wav").writeBytes(it.wav) }
                try {
                    withTimeout(25000) { while(withContext(Dispatchers.Main) { player.talkVoice.state.value.phase !in setOf("Listening","Muted") })delay(50) }
                } finally {
                    receipt.put("connection",player.talkVoice.debugConnectionIssue).put("readyState",player.talkVoice.debugAudioSnapshot())
                }
                val baseline=withContext(Dispatchers.Main) { player.talkVoice.state.value.lines.size }
                val before=android.os.SystemClock.elapsedRealtime()
                if(args.getString("sceneInput")=="speech")withContext(Dispatchers.Main) {
                    player.talkVoice.debugInjectMicrophonePcm(talkValidationFixture("talk187-explain.pcm").readBytes())
                } else withContext(Dispatchers.Main) { player.talkVoice.sendText("I don't understand what just happened. Explain this scene in English, without spoilers beyond where I paused.") }
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
                    .put("framesSent",player.talkVoice.debugSceneFrames).put("tools",JSONObject(player.talkVoice.debugToolCalls.toMap()))
                    .put("deliveredScene",player.talkVoice.debugSceneContext)
                assertTrue("Missing independent scene observation; see talk187-live-$titleCase.json",player.talkVoice.debugSceneContext?.optJSONObject("groundedObservation")?.optBoolean("titleBlind")==true)
                assertTrue(answer,answer.length>100)
                assertFalse(answer,Regex("(?i)trouble.{0,45}retriev|unable to (?:access|see)|cannot (?:access|see)|can't (?:access|see)|don't have.{0,20}(?:scene|details)").containsMatchIn(answer))
                assertTrue("Scene answer took $first ms: $answer",first in 1..12000)
                assertTrue(player.talkVoice.debugSceneFrames>0)
                if(args.getString("sceneVoice")=="true")assertTrue("No real rendered response audio",player.talkVoice.debugAudioSnapshot().optLong("rendered")>10000)
                if(args.getString("sceneSubtitles")=="off") {
                    assertEquals(0,scene.metadata.getJSONArray("dialogue").length())
                    assertTrue(scene.metadata.has("playedAudio"))
                    assertTrue(player.talkVoice.debugSceneContext!!.getJSONObject("groundedObservation").getString("spokenDialogue").isNotBlank())
                }
                val expected = args.getString("sceneExpected")?.split(';')?.getOrNull(index)
                    ?: if(titleCase=="dark") if(index==0) "letter|date|November" else "Erik|missing|son" else null
                if(expected!=null)assertTrue(answer,Regex("(?i)(?:$expected)").containsMatchIn(answer))
                if(titleCase=="dark"&&position in 179.0..181.0) {
                    assertTrue(answer,Regex("(?i)wak|wok|bed|distress|nightmare").containsMatchIn(answer))
                    assertFalse(answer,Regex("(?i)after (?:finding|reading|opening).{0,25}(?:letter|envelope|note)|(?:processing|reacting to).{0,20}contents").containsMatchIn(answer))
                }
                if(titleCase=="dark"&&index==1)assertFalse(answer,Regex("(?i)\\bMikkel\\b").containsMatchIn(answer))
                assertFalse(withContext(Dispatchers.Main) { player.playbackController!!.isPlaying })
                screenshot("talk187-live-$titleCase-$index.png")
            }
        } } finally {
            if(receipts.length()>0&&nativePlayer!=null)receipts.getJSONObject(receipts.length()-1)
                .put("finalAudio",nativePlayer!!.talkVoice.debugAudioSnapshot())
                .put("observationIssue",nativePlayer!!.talkVoice.debugSceneObservationIssue)
                .put("inputTrace",JSONArray(nativePlayer!!.talkVoice.debugInputTrace.toList()))
                .put("allLines",JSONArray(nativePlayer!!.talkVoice.state.value.lines.map { JSONObject().put("role",it.role).put("text",it.text) }))
            File(context.getExternalFilesDir(null),"talk187-live-$titleCase.json").writeText(receipts.toString(2))
            i.runOnMainSync { nativePlayer?.closeTalk();nativePlayer?.finish();activity.finish() }
            context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP))
        }
    }
}
