package com.aliflix.app.talk

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.model.MediaType
import com.aliflix.app.player.NativePlayerActivity
import com.aliflix.app.player.NativePlaybackService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in public-provider journey. It starts from Talk, without touching a Play button. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Talk186EpisodeJourneyDeviceTest {
    @Test fun talkReallyStartsAnEpisodeThenAppliesGermanAudioArabicSubtitlesSyncAndFinalSeek() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk186")=="true")
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val arguments=InstrumentationRegistry.getArguments()
        val case=arguments.getString("talk186Title")?:"big-bang"
        val activity=launchFreshTalkActivity(localAudio=true)
        val card=if(case=="dark")TalkTitleCard(70523,MediaType.TV,"Dark","2017")else TalkTitleCard(1418,MediaType.TV,"The Big Bang Theory","2007")
        val work=TalkTaskWork.get(context)
        var taskId=""
        try { runBlocking {
            val previous=work.tasks.value.map{it.optString("taskId")}.toSet()
            withContext(Dispatchers.Main){
                activity.voice.seedTitle(card.json())
                activity.voice.debugInjectMicrophonePcm(talkValidationFixture(arguments.getString("spokenFixture")?:"talk186-compound-play.pcm").readBytes())
            }
            taskId=withTimeout(65_000){
                while(true){
                    val task=work.tasks.value.lastOrNull{it.optString("taskId") !in previous}
                    if(task!=null)return@withTimeout task.getString("taskId")
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE") error("The model did not execute the spoken task")
            }
            val done=withTimeout(240_000){work.await(taskId)}
            val rows=done.getJSONArray("results")
            val commands=done.getJSONArray("commands")
            assertEquals(done.toString(),commands.length(),rows.length())
            val actions=(0 until commands.length()).map{commands.getJSONObject(it).getString("action")}
            assertTrue(done.toString(),actions.containsAll(listOf("play","skip_intro","audio_language","subtitle_language","sync_subtitles","seek","resume")))
            fun result(action:String)=rows.getJSONObject(actions.indexOf(action)).getJSONObject("result")
            assertEquals(done.toString(),"prepared",result("play").getString("status"))
            assertEquals(done.toString(),"completed",result("audio_language").getString("status"))
            assertEquals(done.toString(),"completed",result("subtitle_language").getString("status"))
            assertEquals(done.toString(),"completed",result("resume").getString("status"))
            val player=withContext(Dispatchers.Main){ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NativePlayerActivity>().single()}
            val state=withContext(Dispatchers.Main){player.talkSnapshot()}
            assertTrue(state.toString(),state.getBoolean("playing"));assertEquals("de",state.getString("audioLanguage"))
            assertEquals("AR",state.getString("subtitles").uppercase())
            assertEquals(180.0,state.getDouble("positionSeconds"),3.0)
            withContext(Dispatchers.Main){
                assertEquals(activity.voice.state.value.conversationId,player.talkVoice.state.value.conversationId)
                assertEquals(card.key,player.talkVoice.state.value.cards.visible.single().key)
                player.talkVoice.setTextMode(true);player.openTalk()
            }
            delay(700)
            i.uiAutomation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"talk186-live-player-orb.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            withContext(Dispatchers.Main){player.closeTalk();player.finish()}
        } } finally {
            File(context.getExternalFilesDir(null),"talk186-live-episode-$case.json").writeText(work.state(taskId)
                .put("voice",activity.voice.debugAudioSnapshot()).put("tools",JSONObject(activity.voice.debugToolCalls.toMap()))
                .put("lines",JSONArray(activity.voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))
            context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP))
            i.runOnMainSync{activity.finish()}
        }
    }
}
