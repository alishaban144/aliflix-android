package com.aliflix.app.talk

import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.V3CatalogMedia
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=AliflixApplication::class)
class TalkNativeActionsTest {
 private val card=TalkTitleCard(70523,MediaType.TV,"Dark","2017")
 private fun action():TalkNativeActions {
  var cards=TalkCardContext().show(listOf(card))
  return TalkNativeActions(RuntimeEnvironment.getApplication(),TalkCatalogue{cards}){cards=cards.show(it)}
 }
 private fun row(id:Int,title:String,type:String,year:String,original:String=title)=V3CatalogMedia.fromJson(JSONObject().put("tmdbId",id).put("title",title).put("originalTitle",original).put("mediaType",type).put("releaseDate","$year-01-01"))
 @Test fun mistakenYearsAndOriginalTitlesStillDisplayRealMatchingEditions() {
  val rows=listOf(row(34415,"The Killing","tv","2011"),row(32368,"The Killing","tv","2007","Forbrydelsen"),row(247,"The Killing","movie","1956"))
  assertEquals(listOf(34415,32368),talkCatalogueMatches(rows,"The Killing (2017)","tv",2017).map{it.id})
  assertEquals(listOf(32368),talkCatalogueMatches(rows,"Forbrydelsen","tv",0).map{it.id})
  assertEquals(listOf(247),talkCatalogueMatches(rows,"Killing","movie",1956).map{it.id})
  assertTrue(talkCatalogueMatches(rows,"Unknown unrelated title","tv",0).isEmpty())
  val feature=row(300668,"Annihilation","movie","2018").copy(tmdbVoteCount=10000)
  val short=row(883188,"Annihilation","movie","2018").copy(tmdbVoteCount=2)
  assertEquals(listOf(300668),talkCatalogueMatches(listOf(short,feature),"Annihilation","movie",2018).map{it.id})
 }
 @Test fun explicitListActionsAreIdempotentAndReportActualState()=runBlocking {
  val actions=action();val app=(RuntimeEnvironment.getApplication() as AliflixApplication)
  val input=JSONObject().put("action","save").put("title","Dark")
  repeat(2){assertEquals("completed",actions.execute(input).value.getString("status"))}
  assertEquals(1,app.libraryStore.myList.value.count{it.key==card.key})
  assertTrue(actions.execute(JSONObject().put("action","favorite").put("title","Dark")).value.getBoolean("favorite"))
  assertFalse(actions.execute(JSONObject().put("action","unsave").put("title","Dark")).value.getBoolean("saved"))
  assertFalse(actions.execute(JSONObject().put("action","unfavorite").put("title","Dark")).value.getBoolean("favorite"))
 }
 @Test fun settingsReportTheNativeStoresClampedValueAndRejectUnknownControls()=runBlocking {
  val actions=action();val app=(RuntimeEnvironment.getApplication() as AliflixApplication)
  val result=actions.execute(JSONObject().put("action","set_setting").put("setting","subtitle_size").put("numberValue",100)).value
  assertEquals(30f,app.playerSettingsStore.settings.value.subtitleFontSizeSp)
  assertEquals(30.0,result.getJSONObject("current").getDouble("subtitleSizeSp"),0.0)
  assertTrue(runCatching{actions.execute(JSONObject().put("action","set_setting").put("setting","invented"))}.isFailure)
 }
 @Test fun undoUsesTheExactEstablishedEditionAndRestoresOnlyTheLastChange()=runBlocking {
  val actions=action();val app=(RuntimeEnvironment.getApplication() as AliflixApplication)
  if(app.libraryStore.isInMyList(card.media()))app.libraryStore.toggleMyList(card.media())
  actions.execute(JSONObject().put("action","save").put("title","it"))
  assertTrue(app.libraryStore.isInMyList(card.media()))
  assertFalse(actions.execute(JSONObject().put("action","undo")).value.getBoolean("saved"))
  assertEquals("nothing_to_undo",actions.execute(JSONObject().put("action","undo")).value.getString("status"))
 }
 @Test fun openingNativeFeaturesIsDeferredAndPreservesExactTitleAndEpisode()=runBlocking {
  val app=(RuntimeEnvironment.getApplication() as AliflixApplication)
  val result=action().execute(JSONObject().put("action","play").put("title","Dark").put("season",1).put("episode",2))
  assertEquals("ready",result.value.getString("status"))
  assertNull(shadowOf(app).nextStartedActivity)
  requireNotNull(result.afterReply).invoke()
  val intent=shadowOf(app).nextStartedActivity
  assertEquals(TalkTitleActivity::class.java.name,intent.component!!.className)
  assertEquals(70523,JSONObject(intent.getStringExtra("title")!!).getInt("id"))
  assertEquals(2,JSONObject(intent.getStringExtra("assistantAction")!!).getInt("episode"))
 }
}
