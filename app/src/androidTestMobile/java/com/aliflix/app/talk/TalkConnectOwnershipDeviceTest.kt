package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.account.AccountLocalSnapshotStore
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class TalkConnectOwnershipDeviceTest {
 @Test fun endingWhileAccountScopeIsRestoringCannotWakeTheOldConnection(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  Assume.assumeTrue(FirebaseAuth.getInstance().currentUser==null)
  val scopes=AccountLocalSnapshotStore(context);val original=scopes.activeScope
  val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{runBlocking{
   scopes.setActiveScope("user:owned-pending-scope-fixture")
   val connection=launch(Dispatchers.Main){voice.connect()}
   withTimeout(1000){while(voice.state.value.phase!="Connecting")delay(10)}
   withContext(Dispatchers.Main){voice.suspendSession()}
   scopes.setActiveScope(original)
   withTimeout(2000){connection.join()}
   delay(100)
   assertFalse(voice.debugAudioSnapshot().getBoolean("leaseActive"))
   assertEquals("Resume",voice.state.value.phase)
  }}finally{scopes.setActiveScope(original);i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
