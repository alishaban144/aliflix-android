package com.aliflix.app.recommendation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AskResearchStreamTest {
    private fun event(revision: Int, vararg ids: Int, running: Boolean = true) = JSONObject()
        .put("requestId", "request").put("revision", revision).put("type", "results")
        .put("inProgress", running).put("results", JSONArray(ids.map { JSONObject().put("tmdbId", it) }))

    @Test fun verifiedSnapshotsAppendWithoutWaitingForCompletion() {
        val stream=AskResearchStream("request")
        assertEquals(2,stream.accept(event(2,1,2))!!.getJSONArray("results").length())
        assertFalse(stream.finished)
        assertEquals(3,stream.accept(event(3,1,2,3,running=false))!!.getJSONArray("results").length())
        assertTrue(stream.finished)
    }
    @Test fun duplicateAndOutOfOrderEventsCannotOverwriteMatches() {
        val stream=AskResearchStream("request")
        stream.accept(event(3,1,2))
        assertNull(stream.accept(event(3,4)))
        assertNull(stream.accept(event(2,4)))
        assertEquals(1,stream.latest!!.getJSONArray("results").getJSONObject(0).getInt("tmdbId"))
    }
    @Test fun foreignRequestAndReorderedResultsAreRejected() {
        val stream=AskResearchStream("request")
        assertThrows(IllegalArgumentException::class.java){stream.accept(event(1,1).put("requestId","old"))}
        stream.accept(event(2,1,2))
        assertThrows(IllegalArgumentException::class.java){stream.accept(event(3,2,1))}
        assertThrows(IllegalArgumentException::class.java){stream.accept(event(4,1))}
    }
    @Test fun quotaAfterPartialResultsRetainsGenuineMatches() {
        val stream=AskResearchStream("request")
        stream.accept(event(1,1,2))
        val result=stream.accept(JSONObject().put("requestId","request").put("revision",2)
            .put("type","error").put("error",JSONObject().put("code","AI_QUOTA")))!!
        assertEquals(2,result.getJSONArray("results").length())
        assertFalse(result.getBoolean("inProgress"))
        assertTrue(result.getBoolean("incomplete"))
    }
    @Test fun quotaBeforeResultsIsAnHonestErrorRatherThanInventedMatches() {
        val stream=AskResearchStream("request")
        val error=assertThrows(RecommendationAiClientException::class.java){stream.accept(JSONObject()
            .put("requestId","request").put("revision",1).put("type","error")
            .put("error",JSONObject().put("code","AI_QUOTA")))}
        assertEquals("AI_QUOTA",error.code)
        assertNull(stream.retained())
    }
    @Test fun disconnectedStreamRetainsMatchesWithoutMutatingLastSnapshot() {
        val stream=AskResearchStream("request")
        stream.accept(event(1,1))
        assertFalse(stream.retained()!!.getBoolean("inProgress"))
        assertTrue(stream.latest!!.getBoolean("inProgress"))
    }
    @Test fun finalSnapshotRejectsLaterEventsAndEmptyFinalIsSupported() {
        val stream=AskResearchStream("request")
        assertNotNull(stream.accept(event(1,running=false)))
        assertTrue(stream.finished)
        assertNull(stream.accept(event(2,1)))
        assertNull(stream.retained())
    }
}
