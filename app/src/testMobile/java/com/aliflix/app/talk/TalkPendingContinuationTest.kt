package com.aliflix.app.talk

import org.json.*
import org.junit.Assert.*
import org.junit.Test

class TalkPendingContinuationTest {
 @Test fun reconnectReplaysTheCompletedLookupRatherThanStartingAnUnrelatedSearch() {
  val data=JSONObject().put("selectionId","owned-selection").put("requiresPresentation",true)
   .put("results",JSONArray().put(JSONObject().put("key","movie:123").put("title","Owned catalogue title")))
   .put("instruction","Call present_recommendations for these exact results")
  val continuation=talkPendingContinuation("One impossible rule, no space battles",data,JSONObject())
  assertTrue(continuation.contains("movie:123"));assertTrue(continuation.contains("owned-selection"))
  assertTrue(continuation.contains("no space battles"));assertTrue(continuation.contains("present_recommendations"))
  assertFalse(continuation.contains("perform that lookup again"))
 }
 @Test fun aGenuinelyUnfinishedLookupMayResumeFromItsEstablishedContext() {
  val text=talkPendingContinuation("Compare the original pair",null,JSONObject().put("originalPair",JSONArray().put("movie:321")))
  assertTrue(text.contains("movie:321"));assertFalse(text.contains("completed app lookup"))
 }
}
