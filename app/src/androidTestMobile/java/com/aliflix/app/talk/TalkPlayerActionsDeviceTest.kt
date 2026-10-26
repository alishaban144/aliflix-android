package com.aliflix.app.talk

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises Talk's actual Media3 controller boundary against local video. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TalkPlayerActionsDeviceTest {
 @Test fun nativeTalkControlsPauseSeekAndResumeTheRealPlayer() {
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  grantNativeFixtureNetworkPermission()
  val bytes=i.context.assets.open("cast-test.mp4").use{it.readBytes()}
  NativeBackgroundPlaybackTest.FixtureServer(bytes).use{server->
   val request=NativePlaybackRequest(server.url,"video/mp4","https://fixture.aliflix.test/","Aliflix native Talk fixture","","Talk control fixture",0,true)
   val file=File(context.cacheDir,"native-request-${UUID.randomUUID()}.json").apply{writeText(request.toJson())}
   try {
    ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("requestFile",file.name),nativePhoneLaunchOptions()).use {
     runBlocking {
      suspend fun command(action:String,seconds:Double?=null)=talkPlayerAction(context,JSONObject().put("action",action).apply{seconds?.let{put("numberValue",it)}})
      withTimeout(20000){while(command("player_state").optDouble("durationSeconds",0.0)<15)delay(100)}
      val paused=command("pause")
      assertEquals("command_sent",paused.getString("status"));assertFalse(paused.getBoolean("playWhenReady"))
      val sought=command("seek",12.0)
      assertEquals(12.0,sought.getDouble("positionSeconds"),.5)
      val resumed=command("resume")
      assertTrue(resumed.getBoolean("playWhenReady"))
      withTimeout(10000){while(command("player_state").optDouble("positionSeconds")<12.5)delay(100)}
     }
    }
   } finally {
    context.startService(Intent(context,NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP))
    file.delete()
   }
  }
 }
}
