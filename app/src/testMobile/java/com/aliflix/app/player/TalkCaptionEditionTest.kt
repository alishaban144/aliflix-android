package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class TalkCaptionEditionTest {
    private fun cues(end: Double) = listOf(SubtitleCue(10.0, end, "Earlier dialogue"))

    @Test fun retrievedCaptionEditionRemainsDeliverableAcrossRepeatedRecall() = runBlocking {
        val snapshot = JSONObject().put("contentKey", "movie:1").put("requestId", "request")
            .put("positionSeconds", 100.0).put("durationSeconds", 9080.654).put("subtitleEdition", "displayed-cut")
        val memory = PlayerSceneMemory({ null }, { snapshot })
        val transcript = TalkTranscript(cues(8929.0), "retrieved-cut", "retrieved_timed_captions", 9_080_654)
        memory.transcript(transcript)
        val prepared = memory.capture()!!
        assertEquals("retrieved-cut", prepared.metadata.getString("subtitleEdition"))
        assertSame(prepared, memory.current())
        memory.transcript(transcript)
        assertSame(prepared, memory.current())
    }

    @Test fun cachedShorterCutStillTriggersIndependentTalkComparison() {
        assertTrue(talkShouldCompareCaptionEdition(cues(8346.0), 9_080_654, true))
        assertFalse(talkShouldCompareCaptionEdition(cues(8929.0), 9_080_654, true))
        assertFalse(talkShouldCompareCaptionEdition(cues(8346.0), 9_080_654, false))
        assertFalse(talkShouldCompareCaptionEdition(cues(8346.0), 0, true))
    }

    @Test fun closerRetrievedEditionCanReplaceStoryEvidenceWithoutChangingVisibleCaptions() {
        val retrieved = TalkTranscript(cues(8929.0), "extended", "retrieved_timed_captions", 9_080_654)
        assertTrue(talkPrefersRetrievedCaptionEdition(cues(8346.0), retrieved, 9080.654))
        assertFalse(talkPrefersRetrievedCaptionEdition(cues(8900.0), retrieved, 9080.654))
        assertFalse(talkPrefersRetrievedCaptionEdition(cues(8346.0), retrieved.copy(timingDurationMs = 0), 9080.654))
        assertFalse(talkPrefersRetrievedCaptionEdition(cues(8346.0), retrieved.copy(cues = cues(8320.0)), 9080.654))
    }
}
