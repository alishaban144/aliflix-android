package com.aliflix.app.talk

import android.media.AudioAttributes
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

/** Owned 997Hz probe through the production player. Only spectral magnitudes
 * are retained; raw microphone samples are never saved or transmitted. */
class TalkSpeakerAcousticDeviceTest {
 @Test fun actualSpeakerProducesSoundWithoutAnyMicrophoneWakeUp(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{runBlocking{
   i.runOnMainSync{voice.debugStartAcousticProbe()}
   delay(700)
   val baseline=voice.debugAcousticLevels.toList().takeLast(8).average()
   voice.debugAcousticLevels.clear()
   val tone=ByteArray(48000*2)
   for(n in 0 until 48000){val envelope=minOf(n/1200.0,(48000-n)/1200.0,1.0).coerceAtLeast(0.0);val v=(sin(n*2*Math.PI*997/24000)*6000*envelope).toInt();tone[n*2]=v.toByte();tone[n*2+1]=(v shr 8).toByte()}
   i.runOnMainSync{voice.debugQueueSpeech(tone)}
   withTimeout(6000){while(voice.debugRenderedSamples<48000)delay(20)}
   delay(200)
   val observed=voice.debugAcousticLevels.toList().sortedDescending().take(10).average()
   val audio=voice.debugAudioSnapshot()
   File(i.targetContext.getExternalFilesDir(null),"talk-182-acoustic.json").writeText(JSONObject().put("baseline997",baseline).put("speaker997",observed).put("audio",audio).toString(2))
   assertEquals(AudioAttributes.USAGE_ASSISTANT,audio.getInt("outputUsage"))
   assertEquals("Built-in speaker required for acoustic validation",2,audio.getInt("route"))
   assertTrue("The microphone must detect the actual speaker probe: baseline=$baseline observed=$observed",observed>maxOf(baseline*5,0.00002))
  }}finally{i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
