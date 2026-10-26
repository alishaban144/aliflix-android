@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Fault injection is confined to this launch's primary origin. The alternate
 * provider, resolver, Media3 decoder and video are real, without a server tap. */
class PriorityFallbackDeviceTest {
 @Test fun unreachableFilmoOriginRecoversThroughAnotherRealSource(){
  assumeTrue(InstrumentationRegistry.getArguments().getString("liveFallback")=="true")
  grantNativeFixtureNetworkPermission()
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val selection=PlaybackSelection(Media(603,MediaType.MOVIE,"The Matrix",year="1999",originalLanguage="en"),
   source=PlaybackSource(MobilePlaybackProvider.FILMO,"https://filmo-outage.invalid"))
  context.stopService(Intent(context,NativePlaybackService::class.java));val began=SystemClock.elapsedRealtime()
  try {
   ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("selection",selection.nativeJson()).putExtra("autoSubtitles",false),nativePhoneLaunchOptions()).use{scenario->
    var ready=false
    while(!ready&&SystemClock.elapsedRealtime()-began<45000){
     scenario.onActivity{a->a.playbackUiState.error?.let{fail(it)};ready=a.playbackController?.isPlaying==true&&(a.playbackController?.currentPosition?:0)>3000}
     if(!ready)Thread.sleep(100)
    }
    assertTrue("Bounded automatic provider recovery",ready)
    scenario.onActivity{a->
     val actual=nativeSelection(requireNotNull(NativePlaybackService.activeRequest).selectionJson)
     assertNotEquals(MobilePlaybackProvider.FILMO,actual.source.identity)
     assertEquals(603,actual.media.id);assertTrue(a.playbackController!!.videoSize.width>0);assertNull(a.playbackController!!.playerError)
     File(context.getExternalFilesDir(null),"filmo-fallback-receipt.json").writeText(JSONObject().put("fault","unreachable primary DNS origin").put("source",actual.source.identity.displayName).put("readyMs",SystemClock.elapsedRealtime()-began).put("passed",true).toString())
    }
   }
  }finally{context.stopService(Intent(context,NativePlaybackService::class.java))}
 }
}
