package com.aliflix.app.talk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkSceneWireTest {
    @Test fun worriedCharacterQuestionKeepsBeliefStatusNextToTheActualQuestion() {
        assertTrue(talkSceneQuestionFocus("Why is she nervous about her bag?").contains("BELIEVES"))
        assertTrue(talkSceneQuestionFocus("Who is visiting her?").isEmpty())
    }
    @Test fun deduplicatesResearchAndRecallWithoutLosingQuestionFocusedEvidence() {
        val original=JSONObject().put("anchorSeconds",60)
            .put("episodeRecall",JSONObject().put("storyThroughAnchor",JSONObject().put("timedTranscript","10.00\tEarlier cause.\n60.00\tCurrent line.\n")))
            .put("storyResearch",JSONObject().put("mode","direct_player_evidence")
                .put("mentionedCharacters",JSONArray().put(JSONObject().put("name","Jane").put("performer","Credited actor").put("scope","Repeated invariant")))
                .put("supportingDialogue",JSONArray().put(JSONArray(listOf(10.001,12.0,"Earlier cause."))).put(JSONArray(listOf(20.0,22.0,"Missing earlier setup.")))))
        val wire=compactTalkSceneMetadata(original)
        assertEquals("60.00\tCurrent line.\n",wire.getJSONObject("episodeRecall").getJSONObject("storyThroughAnchor").getString("timedTranscript"))
        assertEquals(2,wire.getJSONObject("storyResearch").getJSONArray("supportingDialogue").length())
        assertEquals("Credited actor",wire.getJSONObject("storyResearch").getJSONArray("mentionedCharacters").getJSONObject(0).getString("performer"))
        assertTrue(original.getJSONObject("storyResearch").has("supportingDialogue"))
    }
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
