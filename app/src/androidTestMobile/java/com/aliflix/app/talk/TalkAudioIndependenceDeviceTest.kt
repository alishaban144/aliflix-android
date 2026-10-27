package com.aliflix.app.talk

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/** Physical AudioTrack evidence for the silence-until-hi regression. No network,
 * model response, emulator or queued-byte surrogate is needed for this test. */
class TalkAudioIndependenceDeviceTest {
 @Test fun microReplyStartsAndDrainsWithNoMicrophoneRead(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  val activity=launchFreshTalkActivity(localAudio=true)
  val session=activity.voice
  try {
   instrumentation.runOnMainSync{session.debugStartLocalAudio();session.debugStallMicrophone()}
   // 80 ms is deliberately shorter than the old 120ms app priming and 500ms
   // platform threshold, yet a complete spoken word still must be audible.
   val tone=ByteArray(1920*2)
   for(i in 0 until 1920){val v=(sin(i*2*Math.PI*523.25/24000)*4000).toInt();tone[i*2]=v.toByte();tone[i*2+1]=(v shr 8).toByte()}
   instrumentation.runOnMainSync{session.debugQueueSpeech(tone)}
   runBlocking{withTimeout(4000){while(session.debugRenderedSamples<1920)delay(20)}}
   assertTrue("Actual playback head must advance with capture stopped",session.debugRenderedSamples>=1920)
   runBlocking{withTimeout(2000){while(session.state.value.phase=="Speaking")delay(20)}}
   assertEquals("Listening",session.state.value.phase)
  } finally {instrumentation.runOnMainSync{session.suspendSession();activity.finish()}}
 }
}
