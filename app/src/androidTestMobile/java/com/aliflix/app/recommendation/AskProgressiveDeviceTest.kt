package com.aliflix.app.recommendation

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Opt-in real HTTPS/auth/client check using already researched requests.
 * Does not replace live model evaluation with fixtures or mutate viewer data. */
class AskProgressiveDeviceTest {
 @Test fun exactSeriesSimilarExamplesReturnTheirExpectedMatchesOnThePhone() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveSimilar179")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val client=RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL,research=AskResearchClient(context))
  val report=JSONArray()
  runBlocking{withTimeout(120000){
   for((id,title,expected) in listOf(Triple(1396,"Breaking Bad","Better Call Saul"),Triple(32368,"The Killing","The Chestnut Man"),Triple(34415,"The Killing","The Chestnut Man"))) {
    val start=SystemClock.elapsedRealtime()
    val result=client.getRecommendations(V3RecommendationRequest(UUID.randomUUID().toString(),mode="similar",query="",mediaType="tv",anchors=listOf(V3RecommendationAnchor(id,title,"tv"))))
    assertTrue("$title must include $expected: ${result.results.map{it.title}}",result.results.any{it.title==expected})
    assertTrue(result.results.all{it.mediaType=="tv"&&it.tmdbId!=id})
    report.put(JSONObject().put("anchor",id).put("expected",expected).put("titles",JSONArray(result.results.map{it.title})).put("elapsedMs",SystemClock.elapsedRealtime()-start))
   }
  }}
  File(context.getExternalFilesDir(null),"similar-179.json").writeText(report.toString(2))
 }
 @Test fun authenticatedMobileClientReceivesVerifiedDescribeAndSimilarSnapshots() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProgressive")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val research=AskResearchClient(context)
  val report=JSONArray()
  runBlocking { withTimeout(90000) {
   for(mode in listOf("describe","similar")) {
    val request=V3RecommendationRequest(UUID.randomUUID().toString(),mode=mode,
     query=if(mode=="describe")"Excellent science fiction movies exploring humanity" else "",mediaType="movie",
     anchors=if(mode=="similar")listOf(V3RecommendationAnchor(27205,"Inception","movie")) else emptyList())
    val started=SystemClock.elapsedRealtime();var first=0L;var snapshots=0;var result:V3RecommendationResponse?=null
    val client=RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL,research=research)
    result=client.getRecommendations(request){value->
     if(first==0L)first=SystemClock.elapsedRealtime()-started
     assertEquals(request.requestId,value.requestId)
     assertTrue(value.results.isNotEmpty())
     assertTrue(value.results.all{it.tmdbId>0&&it.title.isNotBlank()&&it.retrievalSources.isNotEmpty()})
     snapshots++
    }
    assertFalse(result.inProgress);assertFalse("A valid reordered stream must complete",result.incomplete)
    assertTrue("Broad requests should return a useful selection",result.results.size>=12);assertTrue(snapshots>0)
    report.put(JSONObject().put("mode",mode).put("firstResultsMs",first).put("completeMs",SystemClock.elapsedRealtime()-started)
     .put("count",result.results.size).put("snapshots",snapshots).put("incomplete",result.incomplete))
   }
  }}
  File(context.getExternalFilesDir(null),"ask-progressive-176.json").writeText(report.toString(2))
 }
}
