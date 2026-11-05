package com.aliflix.app.talk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkSceneWireTest {
    @Test fun removesDuplicateCopiesWithoutLosingAnEarlierOrCurrentDialogueLine() {
        val original=JSONObject().put("anchorSeconds",60).put("captionEdition","selected-cut")
            .put("timelineIndex",JSONArray().put(JSONObject().put("hint","x".repeat(1000))))
            .put("storyThroughAnchor",JSONObject().put("timedTranscript","10.00\tEarlier cause.\n60.00\tCurrent line.\n").put("complete",false))
            .put("scenes",JSONArray().put(JSONObject().put("startSeconds",10).put("endSeconds",60)
                .put("timedTranscript","10.00\tEarlier cause.\n20.00\tAnother relevant line.\n60.00\tCurrent line.\n")))
        val wire=compactTalkRecall(original)
        assertEquals("10.00\tEarlier cause.\n20.00\tAnother relevant line.\n60.00\tCurrent line.\n",wire.getJSONObject("storyThroughAnchor").getString("timedTranscript"))
        assertEquals("selected-cut",wire.getString("captionEdition"));assertEquals(60,wire.getInt("anchorSeconds"))
        assertFalse(wire.has("timelineIndex"));assertFalse(wire.getJSONArray("scenes").getJSONObject(0).has("timedTranscript"))
        assertTrue(original.has("timelineIndex"));assertTrue(original.getJSONArray("scenes").getJSONObject(0).has("timedTranscript"))
        assertTrue(wire.toString().length<original.toString().length)
    }
    @Test fun indexOnlyRecallRemainsAvailableWhenNoTimedPrefixExists() {
        val original=JSONObject().put("timelineIndex",JSONArray().put("earlier scene"))
        assertEquals(original.toString(),compactTalkRecall(original).toString())
    }
}
