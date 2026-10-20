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
 @Test fun authenticatedMobileClientReceivesVerifiedDescribeAndSimilarSnapshots() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProgressive")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val research=AskResearchClient(context)
  val report=JSONArray()
  runBlocking { withTimeout(90000) {
   for(mode in listOf("describe","similar").filter{InstrumentationRegistry.getArguments().getString("researchMode")?.let{selected->selected==it}?:true}) {
    val request=V3RecommendationRequest(UUID.randomUUID().toString(),mode=mode,
     query=if(mode=="describe")"Excellent science fiction movies exploring humanity" else "",mediaType="movie",
     anchors=if(mode=="similar")listOf(V3RecommendationAnchor(27205,"Inception","movie")) else emptyList())
    val started=SystemClock.elapsedRealtime();var first=0L;var snapshots=0;var result:V3RecommendationResponse?=null
    val client=RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL,research=research)
    result=client.getRecommendations(request){value->
     if(first==0L)first=SystemClock.elapsedRealtime()-started
     assertEquals(request.requestId,value.requestId)
     assertTrue(value.results.isNotEmpty())
     assertTrue(value.results.all{it.tmdbId>0&&it.finalScore>0&&it.retrievalSources.isNotEmpty()})
     snapshots++
    }
    assertFalse(result.inProgress);assertTrue(result.results.isNotEmpty());assertTrue(snapshots>0)
    report.put(JSONObject().put("mode",mode).put("firstResultsMs",first).put("completeMs",SystemClock.elapsedRealtime()-started)
     .put("count",result.results.size).put("snapshots",snapshots).put("incomplete",result.incomplete))
    File(context.getExternalFilesDir(null),"ask-progressive-176.json").writeText(report.toString(2))
   }
  }}
  File(context.getExternalFilesDir(null),"ask-progressive-176.json").writeText(report.toString(2))
 }
}
