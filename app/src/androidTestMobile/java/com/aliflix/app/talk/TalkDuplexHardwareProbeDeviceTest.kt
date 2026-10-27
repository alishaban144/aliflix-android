package com.aliflix.app.talk

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.*
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled far-end material; only effect/state statistics persist, never microphone PCM. */
class TalkDuplexHardwareProbeDeviceTest {
 @SuppressLint("MissingPermission")
 @Test fun compareActualSpeakerProcessingPaths(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  val manager=context.getSystemService(AudioManager::class.java)
  Assume.assumeTrue("Do not disturb a call",manager.mode==AudioManager.MODE_NORMAL)
  val rows=JSONArray();val receipt=File(context.getExternalFilesDir(null),"talk-184-duplex-profiles.json")
  val original=talkValidationFixture("question-en.pcm").readBytes()
  val samples=ShortArray(original.size/2){at->((original[at*2].toInt() and 255) or (original[at*2+1].toInt() shl 8)).toShort()}
  val speech=ByteArray(samples.size*3)
  for(n in 0 until speech.size/2){val p=n*2.0/3;val a=p.toInt().coerceAtMost(samples.lastIndex);val b=(a+1).coerceAtMost(samples.lastIndex);val v=(samples[a]+(samples[b]-samples[a])*(p-a)).toInt();speech[n*2]=v.toByte();speech[n*2+1]=(v shr 8).toByte()}
  val activity=launchFreshTalkActivity(localAudio=true)
  val oldMode=manager.mode
  try{runBlocking{
   val profiles=listOf(Triple("normal-assistant",AudioManager.MODE_NORMAL,AudioAttributes.USAGE_ASSISTANT),Triple("communication-assistant",AudioManager.MODE_IN_COMMUNICATION,AudioAttributes.USAGE_ASSISTANT),Triple("communication-voice",AudioManager.MODE_IN_COMMUNICATION,AudioAttributes.USAGE_VOICE_COMMUNICATION),Triple("communication-raw",AudioManager.MODE_IN_COMMUNICATION,AudioAttributes.USAGE_VOICE_COMMUNICATION),Triple("communication-raw-apm",AudioManager.MODE_IN_COMMUNICATION,AudioAttributes.USAGE_VOICE_COMMUNICATION),Triple("communication-apm",AudioManager.MODE_IN_COMMUNICATION,AudioAttributes.USAGE_VOICE_COMMUNICATION))
   for((name,mode,usage)in profiles.filter{InstrumentationRegistry.getArguments().getString("apmOnly")!="true"||it.first.endsWith("-apm")}){
    val row=JSONObject().put("name",name);rows.put(row)
    val attributes=AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{}.build()
    val processor=if(name.endsWith("-apm"))requireNotNull(TalkEchoProcessor.create()) else null
    var submitted=0L
    val delays=JSONArray();val stats=JSONArray();val latency=TalkAudioDelay()
    val gate=TalkInputTurnGate();val duplex=TalkDuplexGate();val events=JSONArray();val far=AtomicBoolean(false)
    var frames=0;var maximum=0f;var voiced=0;var echoFrames=0;var maximumRms=0.0
    val monitor=TalkSpeechMonitor(context){frame,probability,_,_->
     var power=0.0;for(at in frame.indices step 2){val v=((frame[at].toInt() and 255) or (frame[at+1].toInt() shl 8)).toShort().toDouble()/32768;power+=v*v}
     val rms=kotlin.math.sqrt(power/(frame.size/2));val echo=if(processor==null)duplex.isEcho(frame)else false;val decision=gate.accept(frame,probability,echo)
     frames++;maximum=maxOf(maximum,probability);maximumRms=maxOf(maximumRms,rms)
     if(far.get()&&probability>=.65f)voiced++;if(echo)echoFrames++
     if(decision.start)synchronized(events){events.put(JSONObject().put("at",SystemClock.elapsedRealtime()).put("farPlaying",far.get()).put("probability",probability).put("rms",rms))}
    }
    var record:AudioRecord?=null;var track:AudioTrack?=null;var aec:AcousticEchoCanceler?=null;var ns:NoiseSuppressor?=null;var capture:Job?=null
    try{
     withContext(Dispatchers.Main){
      row.put("focus",manager.requestAudioFocus(focus));manager.mode=mode
      if(Build.VERSION.SDK_INT>=31&&mode==AudioManager.MODE_IN_COMMUNICATION){
       val speaker=manager.availableCommunicationDevices.firstOrNull{it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
       row.put("speakerAccepted",speaker!=null&&manager.setCommunicationDevice(speaker))
      }
     }
     val input=AudioRecord.Builder().setAudioSource(if(name.startsWith("communication-raw"))MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(12800).build();record=input
     assertEquals(AudioRecord.STATE_INITIALIZED,input.state)
     val aecResult=runCatching{if(!name.startsWith("communication-raw")&&processor==null&&AcousticEchoCanceler.isAvailable())AcousticEchoCanceler.create(input.audioSessionId)else null}
     aec=aecResult.getOrNull();row.put("aecIssue",aecResult.exceptionOrNull()?.javaClass?.simpleName)
     ns=runCatching{if(!name.startsWith("communication-raw")&&processor==null&&NoiseSuppressor.isAvailable())NoiseSuppressor.create(input.audioSessionId)else null}.getOrNull()
     row.put("aecAvailable",AcousticEchoCanceler.isAvailable()).put("aecEnableStatus",aec?.setEnabled(true)).put("aecEnabled",aec?.enabled).put("aecControl",aec?.hasControl()).put("aecName",aec?.descriptor?.name).put("aecVendor",aec?.descriptor?.implementor)
     ns?.setEnabled(true)
     val output=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(5760).build();track=output
     if(Build.VERSION.SDK_INT>=31)output.setStartThresholdInFrames(1)
     input.startRecording();output.play()
     val activeRecord=input
     capture=launch(Dispatchers.IO){val input=ShortArray(160);var received=0L;var lastStats=0L;while(isActive){val count=activeRecord.read(input,0,input.size,AudioRecord.READ_BLOCKING);check(count>0){"Capture read: $count"};received+=count;val delay=latency.milliseconds(output,activeRecord,submitted,received,count);if(received-lastStats>=1600){lastStats=received;delays.put(delay);processor?.statistics()?.let{stats.put(JSONArray(it.toList()))}};val bytes=processor?.capture(input,count,delay,1)?:ByteArray(count*2).also{for(n in 0 until count){it[n*2]=input[n].toByte();it[n*2+1]=(input[n].toInt() shr 8).toByte()}};monitor.offer(bytes,1)}}
     far.set(true);var written=0
     repeat(3){
      withContext(Dispatchers.IO){var at=0;while(at<speech.size){val count=output.write(speech,at,minOf(480,speech.size-at),AudioTrack.WRITE_BLOCKING);check(count>0);processor?.render(speech,at,count);duplex.reference(speech,at,count);written+=count/2;submitted=written.toLong();at+=count}}
      withTimeout(10000){while(output.playbackHeadPosition.toLong().and(0xffffffffL)<written)delay(20)}
      delay(250)
     }
     delay(900);far.set(false)
     row.put("mode",manager.mode).put("usage",usage).put("outputRoute",output.routedDevice?.type).put("inputRoute",input.routedDevice?.type).put("rendered",output.playbackHeadPosition.toLong().and(0xffffffffL))
     assertTrue("Actual playback is required",output.playbackHeadPosition.toLong().and(0xffffffffL)>=speech.size/2*3)
    }finally{
     far.set(false);capture?.cancelAndJoin();runCatching{track?.pause()};runCatching{track?.release()};runCatching{record?.stop()};aec?.release();ns?.release();record?.release();monitor.close();processor?.close()
     row.put("delays",delays).put("apmStats",stats).put("frames",frames).put("maxProbability",maximum).put("voicedFramesDuringFarEnd",voiced).put("echoFrames",echoFrames).put("maxRms",maximumRms).put("activities",events)
     receipt.writeText(JSONObject().put("device",Build.MODEL).put("profiles",rows).toString(2))
     withContext(Dispatchers.Main){if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();manager.mode=oldMode;manager.abandonAudioFocusRequest(focus)}
    }
    assertTrue("Capture must really run",frames>100)
    if(name.endsWith("-apm"))assertEquals("Software AEC must reject real speaker echo: $name",0,events.length())
   }
  }}finally{
   i.runOnMainSync{if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();manager.mode=oldMode;activity.voice.suspendSession();activity.finish()}
  }
 }
}
