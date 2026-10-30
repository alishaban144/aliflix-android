package com.aliflix.app.talk

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** New acceptance journeys against the physical Media3 player, including partial failure and cancellation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Talk186PlayerJourneyDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun step(action:String,number:Double?=null,text:String?=null)=JSONObject().put("action",action).apply { number?.let{put("numberValue",it)};text?.let{put("stringValue",it)} }
    private suspend fun bound(activity:NativePlayerActivity,key:String)=withTimeout(8000){
        while(true){withContext(Dispatchers.Main){activity.talkVoice.debugCardBounds[key]}?.let{return@withTimeout it};delay(40)}
        @Suppress("UNREACHABLE_CODE") error("Missing $key")
    }
    private fun tap(rectangle:androidx.compose.ui.geometry.Rect) {
        val now=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,rectangle.center.x,rectangle.center.y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;instrumentation.sendPointerSync(event);event.recycle()
        }
    }
    private fun fixture(body:suspend (NativePlayerActivity)->Unit) {
        grantNativeFixtureNetworkPermission()
        val bytes=instrumentation.context.assets.open("cast-test.mp4").use{it.readBytes()}
        NativeBackgroundPlaybackTest.FixtureServer(bytes).use { server ->
            val request=NativePlaybackRequest(server.url,"video/mp4","https://fixture.aliflix.test/","Aliflix acceptance fixture","","Talk 186 native acceptance",0,true)
            val file=File(context.cacheDir,"native-request-${UUID.randomUUID()}.json").apply{writeText(request.toJson())}
            try {
                ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("requestFile",file.name).putExtra("validationFreshHistory",true),nativePhoneLaunchOptions()).use { scenario ->
                    lateinit var activity:NativePlayerActivity
                    scenario.onActivity{activity=it}
                    runBlocking {
                        withTimeout(25_000) { while(withContext(Dispatchers.Main){activity.playbackController?.isPlaying!=true})delay(80) }
                        try { body(activity) } finally { withContext(Dispatchers.Main){activity.closeTalk()} }
                    }
                }
            } finally { context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP)); file.delete() }
        }
    }
    @Test fun unavailableGermanAndIntroAreReportedWhileEveryIndependentStepStillRuns()=fixture { activity ->
        val plan=talkTaskPlan(JSONObject().put("commands",JSONArray(listOf(step("pause"),step("audio_language",text="de"),step("sync_subtitles"),step("skip_intro"),step("subtitle_language",text="off"),step("seek",12.0),step("playback_speed",1.25),step("seek_relative",3.0)))))
        val work=TalkTaskWork.get(context)
        val result=withContext(Dispatchers.Main){work.await(work.start(activity,plan,emptyList()))}
        assertEquals(result.toString(),"partial",result.getString("status"))
        val rows=result.getJSONArray("results")
        assertEquals(8,rows.length())
        assertEquals("audio_unavailable",rows.getJSONObject(1).getJSONObject("result").getString("status"))
        assertEquals("skipped_audio_unavailable",rows.getJSONObject(2).getJSONObject("result").getString("status"))
        assertEquals("intro_unavailable",rows.getJSONObject(3).getJSONObject("result").getString("status"))
        withContext(Dispatchers.Main){
            assertFalse(activity.playbackController!!.playWhenReady)
            assertEquals(15_000.0,activity.playbackController!!.currentPosition.toDouble(),600.0)
            assertEquals(1.25f,activity.playbackController!!.playbackParameters.speed)
        }
        File(context.getExternalFilesDir(null),"talk186-compound-partial.json").writeText(result.toString(2))
    }
    @Test fun cancellingAnInflightTaskPreventsItsLaterSeek()=fixture { activity ->
        val entered=CompletableDeferred<Unit>()
        val wrapped=object:TalkPlayerHost {
            override fun talkSnapshot()=activity.talkSnapshot()
            override suspend fun talkCommand(command:JSONObject):JSONObject {
                if(command.optString("action")=="pause"){entered.complete(Unit);delay(10_000)}
                return activity.talkCommand(command)
            }
        }
        val work=TalkTaskWork.get(context)
        withContext(Dispatchers.Main){
            activity.talkVoice.setTextMode(true);activity.openTalk()
            TalkPlayerConnection.attach(wrapped)
            val id=work.start(activity,listOf(step("pause"),step("seek",30.0)),emptyList())
            withTimeout(3000){entered.await()}
            activity.talkVoice.sendText("Cancel that.");work.await(id)
            assertEquals("cancelled",work.state(id).getString("status"))
            assertEquals(0,work.state(id).getJSONArray("results").length())
            assertTrue(activity.playbackController!!.currentPosition<15_000)
            TalkPlayerConnection.attach(activity)
        }
    }
    @Test fun playerTalkPausesInPlaceAndResumeActuallyAdvancesFrames()=fixture { activity ->
        withContext(Dispatchers.Main){activity.talkVoice.setTextMode(true);activity.openTalk()}
        withTimeout(5000){while(withContext(Dispatchers.Main){activity.playbackController!!.playWhenReady})delay(50)}
        assertTrue(withContext(Dispatchers.Main){activity.talkVisible})
        delay(800)
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"talk186-player-bubble-portrait.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        val resumed=activity.talkCommand(step("resume"))
        assertEquals(resumed.toString(),"completed",resumed.getString("status"))
        assertTrue(resumed.getBoolean("playing"))
        assertFalse(withContext(Dispatchers.Main){activity.talkVisible})
        val before=withContext(Dispatchers.Main){activity.playbackController!!.currentPosition}
        delay(500)
        assertTrue(withContext(Dispatchers.Main){activity.playbackController!!.currentPosition>before+100})
    }
    @Test fun aStaleContentIdentityCannotSeekTheCurrentVideo()=fixture { activity ->
        val before=withContext(Dispatchers.Main){activity.playbackController!!.currentPosition}
        val result=activity.talkCommand(step("seek",30.0).put("expectedContentKey","movie:wrong"))
        assertEquals("playback_changed",result.getString("status"))
        assertTrue(withContext(Dispatchers.Main){activity.playbackController!!.currentPosition<before+2000})
    }
    @Test fun playAgainstTheAlreadyPreparedPausedVideoActuallyStartsIt()=fixture { activity ->
        activity.talkCommand(step("pause"))
        val result=activity.talkCommand(step("await_playback"))
        assertEquals(result.toString(),"playing",result.getString("status"))
        assertTrue(result.getBoolean("playing"))
    }
    @Test fun aReplayedNativeToolIdCannotRestartOrRetargetCompletedPlaybackWork()=fixture { activity ->
        val work=TalkTaskWork.get(context)
        withContext(Dispatchers.Main){
            val key="physical-replay-${UUID.randomUUID()}"
            val original=work.start(activity,listOf(step("pause"),step("seek",12.0)),emptyList(),key)
            work.await(original)
            val replay=work.start(activity,listOf(step("resume"),step("seek",30.0)),emptyList(),key)
            assertEquals(original,replay)
            assertEquals("completed",work.state(replay).getString("status"))
            assertFalse(activity.playbackController!!.playWhenReady)
            assertEquals(12_000.0,activity.playbackController!!.currentPosition.toDouble(),600.0)
        }
    }
    @Test fun theCompactOrbAndExpandedControlsRemainVisibleInBothOrientationsAndBackClosesOnlyTalk()=fixture { activity ->
        suspend fun find(key:String)=withTimeout(5000){
            var rectangle=activity.talkVoice.debugCardBounds[key]
            while(rectangle==null){delay(50);rectangle=activity.talkVoice.debugCardBounds[key]}
            requireNotNull(rectangle)
        }
        fun viewport()=android.graphics.Point().also{activity.windowManager.defaultDisplay.getRealSize(it)}
        withContext(Dispatchers.Main){activity.talkVoice.setTextMode(true);activity.talkVoice.debugCardBounds.clear();activity.openTalk()}
        val orb=find("player-orb")
        val density=activity.resources.displayMetrics.density
        assertTrue("The default bubble stays compact",orb.width<=96*density)
        val now=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,orb.center.x,orb.center.y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event);event.recycle()
        }
        repeat(10){delay(40);assertEquals("The orb must keep its anchor throughout expansion",orb.center.x.toDouble(),bound(activity,"player-orb").center.x.toDouble(),2.0)}
        suspend fun checkControls(name:String) {
            val rectangles=listOf("player-microphone","player-type","player-expand","player-close").map{key->
                val view=viewport()
                find(key).also { assertTrue("$key $it",it.left>=0&&it.top>=0&&it.right<=view.x&&it.bottom<=view.y) }
            }
            for(a in rectangles.indices)for(b in a+1 until rectangles.size)assertFalse("Controls overlap",rectangles[a].overlaps(rectangles[b]))
            instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"talk186-controls-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
        checkControls("portrait")
        activity.talkCommand(step("rotate",text="landscape"))
        withTimeout(6000){while(viewport().x<viewport().y)delay(80)}
        delay(400);checkControls("landscape")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);delay(250)
        assertTrue("Back first folds the controls",withContext(Dispatchers.Main){activity.talkVisible})
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);delay(250)
        assertFalse(withContext(Dispatchers.Main){activity.talkVisible})
        assertFalse(withContext(Dispatchers.Main){activity.isFinishing})
        assertFalse(withContext(Dispatchers.Main){activity.playbackController!!.playWhenReady})
        activity.talkCommand(step("rotate",text="portrait"))
    }
    @Test fun anUnsentDraftAndItsCursorSurviveTheFullConversationAndReturnToThePlayer()=fixture { activity ->
        withContext(Dispatchers.Main){activity.talkVoice.debugCardBounds.clear();activity.openTalk()}
        tap(bound(activity,"player-orb"));delay(500);tap(bound(activity,"player-type"));bound(activity,"player-input");delay(700)
        instrumentation.sendStringSync("Pause this video")
        tap(bound(activity,"player-expand"));bound(activity,"player-full-input");delay(500)
        assertFalse("Typing must retain the voice session",withContext(Dispatchers.Main){activity.talkVoice.state.value.textMode})
        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"talk186-player-full-composer.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        tap(bound(activity,"player-full-close"));delay(600)
        assertTrue(withContext(Dispatchers.Main){activity.talkVisible})
        tap(bound(activity,"player-type"));bound(activity,"player-input");delay(600)
        instrumentation.sendStringSync(" and set playback speed to 0.75.")
        tap(bound(activity,"player-send"))
        val question="Pause this video and set playback speed to 0.75."
        withTimeout(5000){while(activity.talkVoice.state.value.lines.none{it.role=="user"&&it.text==question})delay(50)}
        withTimeout(65_000){while(withContext(Dispatchers.Main){activity.playbackController!!.playbackParameters.speed!=.75f})delay(80)}
        assertFalse(withContext(Dispatchers.Main){activity.playbackController!!.playWhenReady})
    }
    @Test fun theTaskCancelControlBelongsOnlyToThisConversationAndStopsItsRemainingSeek()=fixture { activity ->
        var entered=CompletableDeferred<Unit>()
        val wrapped=object:TalkPlayerHost {
            override fun talkSnapshot()=activity.talkSnapshot()
            override suspend fun talkCommand(command:JSONObject):JSONObject {if(command.optString("action")=="pause"){entered.complete(Unit);delay(10_000)};return activity.talkCommand(command)}
        }
        val work=TalkTaskWork.get(context)
        withContext(Dispatchers.Main){activity.talkVoice.setTextMode(true);activity.talkVoice.debugCardBounds.clear();activity.openTalk();TalkPlayerConnection.attach(wrapped)}
        val foreign=withContext(Dispatchers.Main){work.start(context.applicationContext,listOf(step("pause"),step("seek",30.0)),emptyList())}
        withTimeout(3000){entered.await()};tap(bound(activity,"player-orb"));delay(500)
        assertNull("Another conversation must not own this bubble's cancel control",activity.talkVoice.debugCardBounds["player-cancel"])
        withContext(Dispatchers.Main){work.cancel(foreign);work.await(foreign);entered=CompletableDeferred()}
        val local=withContext(Dispatchers.Main){work.start(activity,listOf(step("pause"),step("seek",30.0)),emptyList())}
        withTimeout(3000){entered.await()};delay(500);tap(bound(activity,"player-cancel"))
        val cancelled=withContext(Dispatchers.Main){work.await(local)}
        assertEquals("cancelled",cancelled.getString("status"));assertFalse(cancelled.getBoolean("cancellable"))
        assertTrue(withContext(Dispatchers.Main){activity.playbackController!!.currentPosition<15_000})
        withContext(Dispatchers.Main){TalkPlayerConnection.attach(activity)}
    }
    @Test fun theManualPlayButtonReleasesTalkBeforePlaybackResumes()=fixture { activity ->
        withContext(Dispatchers.Main){activity.openTalk()}
        withTimeout(15_000){while(!activity.talkVoice.debugAudioSnapshot().optBoolean("leaseActive"))delay(60)}
        val size=android.graphics.Point().also{activity.windowManager.defaultDisplay.getRealSize(it)}
        tap(androidx.compose.ui.geometry.Rect(size.x/2f-2,size.y/2f-2,size.x/2f+2,size.y/2f+2))
        withTimeout(10_000){while(withContext(Dispatchers.Main){activity.talkVisible||activity.playbackController!!.isPlaying!=true})delay(60)}
        assertFalse(activity.talkVoice.debugAudioSnapshot().getBoolean("leaseActive"))
        val before=withContext(Dispatchers.Main){activity.playbackController!!.currentPosition};delay(400)
        assertTrue(withContext(Dispatchers.Main){activity.playbackController!!.currentPosition>before+100})
    }
    @Test fun landscapeTypingMovesIntoTheFullComposerWithoutChangingVoiceModeOrClippingTheKeyboard()=fixture { activity ->
        fun viewport()=android.graphics.Point().also{activity.windowManager.defaultDisplay.getRealSize(it)}
        try {
            activity.talkCommand(step("rotate",text="landscape"));withTimeout(6000){while(viewport().x<viewport().y)delay(60)}
            withContext(Dispatchers.Main){activity.talkVoice.debugCardBounds.clear();activity.openTalk()}
            tap(bound(activity,"player-orb"));delay(500);tap(bound(activity,"player-type"))
            bound(activity,"player-full-input");delay(800)
            val view=viewport()
            // The dialog, rather than the underlying player window, owns this IME.
            val ime=withTimeout(5000){
                while(true){
                    val bottom=withContext(Dispatchers.Main){android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull{it.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom}.maxOrNull()?:0}
                    if(bottom>0)return@withTimeout bottom
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("Keyboard unavailable")
            }
            delay(600)
            val field=bound(activity,"player-full-input");val minimum=48*activity.resources.displayMetrics.density
            instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"talk186-player-landscape-composer.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            assertTrue("The composer must retain a real touchable height: $field",field.height>=minimum&&field.width>=minimum*2)
            assertTrue("The full composer stays above the keyboard: $field, ime=$ime, viewport=$view",field.top>=0&&field.bottom<=view.y-ime+2&&field.right<=view.x)
            assertFalse(withContext(Dispatchers.Main){activity.talkVoice.state.value.textMode})
            tap(bound(activity,"player-full-close"));delay(500)
            assertTrue(withContext(Dispatchers.Main){activity.talkVisible})
            assertFalse(withContext(Dispatchers.Main){activity.playbackController!!.playWhenReady})
        } finally {activity.talkCommand(step("rotate",text="portrait"))}
    }
    @Test fun typingStillExecutesNativeCommandsWhenMicrophonePermissionIsDenied()=fixture { activity ->
        org.junit.Assume.assumeTrue("Opt-in permission-denied run",InstrumentationRegistry.getArguments().getString("deniedMicrophone")=="true")
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO))
        withContext(Dispatchers.Main){activity.talkVoice.debugCardBounds.clear();activity.openTalk()}
        withTimeout(5000){while(activity.talkVoice.state.value.phase!="Microphone permission")delay(40)}
        tap(bound(activity,"player-orb"));delay(500);tap(bound(activity,"player-type"));bound(activity,"player-input");delay(600)
        assertTrue(withContext(Dispatchers.Main){activity.talkVoice.state.value.textMode})
        instrumentation.sendStringSync("Pause this video and seek to exactly 8 seconds.")
        tap(bound(activity,"player-expand"));bound(activity,"player-full-input");delay(600)
        assertFalse(activity.talkVoice.debugAudioSnapshot().optBoolean("leaseActive"))
        tap(bound(activity,"player-full-send"))
        withTimeout(65_000){while(withContext(Dispatchers.Main){kotlin.math.abs(activity.playbackController!!.currentPosition-8000)>650||activity.playbackController!!.playWhenReady})delay(60)}
        assertFalse(activity.talkVoice.debugAudioSnapshot().optBoolean("leaseActive"))
    }
    @Test fun theLiveModelExecutesEnglishAndGermanCompoundRequestsThroughOneNativeTaskEach()=fixture { activity ->
        val voice=activity.talkVoice
        val work=TalkTaskWork.get(context)
        val receipt=JSONArray()
        try {
            withContext(Dispatchers.Main){voice.setTextMode(true)}
            suspend fun ask(question:String):JSONObject {
                val before=work.tasks.value.map{it.optString("taskId")}.toSet()
                withContext(Dispatchers.Main){voice.debugInjectText(question)}
                val task=withTimeout(65_000){
                    while(true){
                        val task=work.tasks.value.lastOrNull{it.optString("taskId") !in before && it.optString("status") in setOf("completed","partial","failed")}
                        if(task!=null)return@withTimeout task
                        delay(80)
                    }
                    @Suppress("UNREACHABLE_CODE") error("No task")
                }
                withTimeout(35_000){while(voice.state.value.sending)delay(80)}
                receipt.put(JSONObject().put("question",question).put("task",task).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})))
                return task
            }
            val first=ask("Pause this video, turn subtitles off, set playback speed to 1.25, and seek to exactly 12 seconds. Do every part together.")
            assertEquals(first.toString(),"completed",first.getString("status"))
            val firstActions=(0 until first.getJSONArray("commands").length()).map{first.getJSONArray("commands").getJSONObject(it).getString("action")}
            assertTrue(firstActions.containsAll(listOf("pause","subtitle_language","playback_speed","seek")))
            val second=ask("Bitte antworte auf Deutsch. Lass das Video pausiert, setze die Geschwindigkeit auf 0,75 und springe fünf Sekunden vor.")
            assertEquals(second.toString(),"completed",second.getString("status"))
            withContext(Dispatchers.Main){assertFalse(activity.playbackController!!.playWhenReady);assertEquals(.75f,activity.playbackController!!.playbackParameters.speed);assertEquals(17_000.0,activity.playbackController!!.currentPosition.toDouble(),650.0)}
        } finally {
            File(context.getExternalFilesDir(null),"talk186-live-model-player.json").writeText(receipt.toString(2))
            withContext(Dispatchers.Main){voice.close()}
        }
    }
}
