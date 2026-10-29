package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Talk186BridgeDeviceTest {
    @Test fun aSpecificConstraintBridgeStreamsBeforeItsSentenceCompletesThenDeliversBothCanonicalChoices() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
        try { runBlocking {
            withContext(Dispatchers.Main){voice.sendText("Recommend exactly two tightly paced chamber comedies where an attempt to be polite keeps making the situation worse. Avoid English-language films.")}
            withTimeout(45_000){while(voice.debugAudioSnapshot().optString("bridgeBoundary")!="played"||voice.debugFirstAudioAt==0L)delay(30)}
            val audio=voice.debugAudioSnapshot()
            assertTrue(audio.toString(),audio.getLong("bridgeStreamingAt")>audio.getLong("bridgeAcceptedAt"))
            assertTrue("Bridge audio should be released before the full-sentence boundary: $audio",audio.getLong("bridgeStreamingAt")<audio.getLong("bridgeCompletedAt"))
            assertFalse(Regex("(?i)international comedies often|charming chaos|cinema (?:often|can)|please wait|looking for").containsMatchIn(audio.getString("bridgeCandidate")))
            withTimeout(100_000){while(voice.state.value.sending||voice.state.value.cards.visible.size!=2||voice.state.value.phase=="Speaking")delay(80)}
            assertEquals(2,voice.state.value.cards.visible.map{it.key}.distinct().size)
        } } finally {
            File(i.targetContext.getExternalFilesDir(null),"talk186-streamed-bridge.json").writeText(JSONObject().put("audio",voice.debugAudioSnapshot())
                .put("firstAudioAt",voice.debugFirstAudioAt).put("cards",voice.state.value.cards.snapshot()).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))
            i.runOnMainSync{voice.suspendSession();activity.finish()}
        }
    }
}
