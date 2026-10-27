@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.talk

import android.net.Uri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.downloads.OfflineDownloads
import com.aliflix.app.model.*
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkNativeBoundaryDeviceTest {
 @Test fun exactDownloadEditionAndSpecialCannotChangeOtherSavedEpisodes()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val activity=launchFreshTalkActivity(localAudio=true)
  val store=OfflineDownloads.get(context)
  withTimeout(5000){while(!store.loaded.value)delay(20)}
  val a=TalkTitleCard(19001841,MediaType.TV,"Owned boundary fixture","2001",null)
  val b=a.copy(id=19001842,year="2011")
  val ids=mutableListOf<String>()
  suspend fun paused(card:TalkTitleCard,season:Int):String {
   val selection=PlaybackSelection(card.media(),seasonNumber=season,episodeNumber=1)
   val id=playbackProgressKey(selection);ids+=id
   val native=NativePlaybackRequest("https://fixture.aliflix.test/video.mp4","video/mp4","https://fixture.aliflix.test/","Aliflix validation","",card.title,0,false,selectionJson=selection.nativeJson())
   val request=DownloadRequest.Builder(id,Uri.parse(native.url)).setMimeType("video/mp4").setData(JSONObject().put("playback",native.toJson()).put("quality","Owned fixture").put("estimate",1).toString().toByteArray()).build()
   withContext(Dispatchers.Main){store.manager.addDownload(request,7)}
   withTimeout(5000){while(store.entries.value.none{it.id==id})delay(20)}
   return id
  }
  try{
   val regular=paused(a,1)
   val single=TalkNativeActions(context,TalkCatalogue{TalkCardContext().show(listOf(a))}){}
   val command=JSONObject().put("action","remove_download").put("title",a.title).put("kind","tv").put("tmdbId",a.id).put("season",0).put("episode",1)
   assertEquals("not_downloaded",single.execute(command).value.getString("status"))
   assertTrue(store.entries.value.any{it.id==regular})
   val special=paused(a,0);val alternate=paused(b,0)
   val ambiguous=TalkNativeActions(context,TalkCatalogue{TalkCardContext().show(listOf(a,b))}){}
   assertEquals("command_sent",ambiguous.execute(command).value.getString("status"))
   withTimeout(5000){while(store.entries.value.any{it.id==special})delay(20)}
   assertTrue(store.entries.value.any{it.id==regular});assertTrue(store.entries.value.any{it.id==alternate})
  }finally{withContext(Dispatchers.Main){ids.forEach(store.manager::removeDownload);activity.voice.suspendSession();activity.finish()}}
 }
 @Test fun invalidSettingCannotChangeThePlayerOrEraseLibraryUndo()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val app=context.applicationContext as AliflixApplication
  val card=TalkTitleCard(19001843,MediaType.MOVIE,"Owned undo fixture","2001",null)
  val actions=TalkNativeActions(context,TalkCatalogue{TalkCardContext().show(listOf(card))}){}
  val before=app.playerSettingsStore.settings.value
  try{
   actions.execute(JSONObject().put("action","save").put("tmdbId",card.id))
   try{actions.execute(JSONObject().put("action","set_setting").put("setting","subtitle_delay_seconds").put("numberValue",1e100));fail("Overflow was accepted")}catch(_:IllegalArgumentException){}
   assertEquals(before,app.playerSettingsStore.settings.value)
   assertEquals("completed",actions.execute(JSONObject().put("action","undo")).value.getString("status"))
   assertFalse(app.libraryStore.isInMyList(card.media()))
  }finally{if(app.libraryStore.isInMyList(card.media()))app.libraryStore.toggleMyList(card.media())}
 }
}
