package com.aliflix.app.talk

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.MainActivity
import com.aliflix.app.ui.theme.*
import java.io.File
import org.junit.*
import kotlin.math.sin

/** Opt-in rendering fixture: real device frames, synthetic energy, no microphone
 * or AI request. It exercises the exact production orb and dotted glyphs. */
class TalkRenderDeviceTest {
 @Test fun fineDotsAndContinuousGlowRenderWithLatinAndArabic() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("renderTalk")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  var main:MainActivity?=null
  val until=android.os.SystemClock.elapsedRealtime()+20000
  while(main==null&&android.os.SystemClock.elapsedRealtime()<until) {
   i.runOnMainSync {main=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()}
   Thread.sleep(50)
  }
  val activity=requireNotNull(main)
  var energy by mutableFloatStateOf(.2f)
  var words by mutableStateOf("THE SHINING")
  i.runOnMainSync {activity.setContent {AliflixMobileTheme {
   Box(Modifier.fillMaxSize().background(AliflixBackgroundBase)) {
    TalkPixelField("","Speaking",{energy},true,Modifier.fillMaxSize().graphicsLayer{alpha=.52f})
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal=22.dp)) {
     Spacer(Modifier.height(64.dp))
     Box(Modifier.fillMaxWidth().weight(.56f),contentAlignment=Alignment.Center) {
      TalkLiveOrb("Speaking",{energy},true,Modifier.fillMaxWidth(.86f).widthIn(max=320.dp).aspectRatio(1f))
     }
     Box(Modifier.fillMaxWidth().weight(.32f),contentAlignment=Alignment.Center){TalkDottedTopic(words,{energy},true)}
     Spacer(Modifier.height(84.dp))
    }
   }
  }}}
  fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
  try {
   Thread.sleep(2200)
   shell("dumpsys gfxinfo ${context.packageName} reset")
   repeat(150) {step->
    i.runOnMainSync {energy=((sin(step*.24)+1)*.3).toFloat();if(step==75)words="السفر عبر الزمن"}
    Thread.sleep(70)
   }
   File(context.getExternalFilesDir(null),"talk-render-172.txt").writeText(shell("dumpsys gfxinfo ${context.packageName}"))
  } finally {i.runOnMainSync {activity.recreate()}}
 }
}
