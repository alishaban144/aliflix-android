package com.aliflix.app.talk

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TalkAudioLeaseDeviceTest {
 @Test fun rapidVoiceRestartsWaitForThisAppsOwnModeRelease(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  val manager=context.getSystemService(AudioManager::class.java)
  val leases=mutableListOf<TalkAudioLease>()
  try{runBlocking{
   repeat(5){
    withContext(Dispatchers.Main){TalkAudioLease.awaitOwnRelease(context)}
    val lease=TalkAudioLease(context,{_,_->},{_,_->},{})
    leases+=lease
    withContext(Dispatchers.Main){assertTrue(lease.reserve());lease.activate();lease.close()}
   }
   mode(manager,AudioManager.MODE_NORMAL)
  }}finally{i.runOnMainSync{leases.forEach{it.close()}}}
 }
 private suspend fun mode(manager:AudioManager,value:Int){withTimeout(2000){while(manager.mode!=value)delay(20)}}
 @Test fun repeatedVoiceTextAndSuspendTransitionsReleaseTheMode(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val manager=activity.getSystemService(AudioManager::class.java)
  try{runBlocking{
   repeat(4){
    i.runOnMainSync{voice.debugStartLocalAudio()}
    mode(manager,AudioManager.MODE_IN_COMMUNICATION)
    assertTrue(voice.debugAudioSnapshot().getBoolean("leaseActive"))
    i.runOnMainSync{voice.setTextMode(true)}
    mode(manager,AudioManager.MODE_NORMAL)
    assertFalse(voice.debugAudioSnapshot().getBoolean("leaseActive"))
    i.runOnMainSync{voice.setTextMode(false)}
   }
   i.runOnMainSync{voice.suspendSession();voice.suspendSession()}
   mode(manager,AudioManager.MODE_NORMAL)
  }}finally{i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
 @Test fun aRejectedSecondSessionCannotReleaseTheFirstSessionsAudio(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val first=activity.voice
  val manager=activity.getSystemService(AudioManager::class.java)
  val secondScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val second=TalkVoiceSession(activity,secondScope,restoreHistory=false)
  try{runBlocking{
   i.runOnMainSync{first.debugStartLocalAudio()}
   mode(manager,AudioManager.MODE_IN_COMMUNICATION)
   i.runOnMainSync{
    assertTrue(runCatching{second.debugStartLocalAudio()}.isFailure)
    second.suspendSession()
   }
   assertEquals("A non-owner cannot reset another session's mode",AudioManager.MODE_IN_COMMUNICATION,manager.mode)
   assertTrue(first.debugAudioSnapshot().getBoolean("leaseActive"))
  }}finally{i.runOnMainSync{second.close();first.suspendSession();activity.finish()};secondScope.cancel()}
 }
}
