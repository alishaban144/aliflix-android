package com.aliflix.app.talk

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*

/** A continuously animated voice screen intentionally never becomes Espresso
 * idle. Observe its lifecycle and dispatch main-thread work directly. */
internal fun launchFreshTalkActivity(localAudio:Boolean=false):TalkVoiceActivity {
 val i=InstrumentationRegistry.getInstrumentation()
 i.targetContext.startActivity(Intent(i.targetContext,TalkVoiceActivity::class.java)
  .putExtra("validationFreshHistory",true).putExtra("validationLocalAudio",localAudio).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
 var result:TalkVoiceActivity?=null
 runBlocking{withTimeout(15000){while(result==null){
  i.runOnMainSync{result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TalkVoiceActivity>().firstOrNull()}
  delay(50)
 }}}
 return requireNotNull(result)
}
