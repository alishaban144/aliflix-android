package com.aliflix.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.talk.launchFreshTalkActivity
import com.aliflix.app.ui.discover.AskDiscoveryLoader
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses the Activity's real frame clock, outside Compose's infinite-animation policy. */
class MorphLoadingDeviceTest {
 @Test fun authoredMorphPathsActuallyAnimateOnThePhysicalRenderer(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true)
  try{runBlocking{
   i.runOnMainSync{activity.setContent{AliflixMobileTheme{
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){AskDiscoveryLoader(Modifier.size(280.dp),motion=true)}
   }}}
   delay(850)
   val first=requireNotNull(i.uiAutomation.takeScreenshot())
   delay(350)
   val second=requireNotNull(i.uiAutomation.takeScreenshot())
   val side=minOf(first.width,first.height)/2;val x=(first.width-side)/2;val y=(first.height-side)/2
   val a=IntArray(side*side);val b=IntArray(side*side)
   first.getPixels(a,0,side,x,y,side,side);second.getPixels(b,0,side,x,y,side,side)
   val changed=a.indices.count{a[it]!=b[it]}
   for((name,bitmap)in listOf("morph-183-runtime-a.png" to first,"morph-183-runtime-b.png" to second)){
    File(i.targetContext.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
   }
   assertTrue("The actual loading shapes must move, not just the status bar: $changed changed pixels",changed>1000)
  }}finally{i.runOnMainSync{activity.voice.suspendSession();activity.finish()}}
 }
}
