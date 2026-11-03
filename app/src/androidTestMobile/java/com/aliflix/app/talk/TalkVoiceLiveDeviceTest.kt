package com.aliflix.app.talk

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Opt-in: only legally generated questions are sent. The live microphone is
 * muted throughout; no ambient speech or playback soundtrack is uploaded. */
class TalkVoiceLiveDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<TalkVoiceActivity>()
    @Test fun realAsrReasoningStreamingVoiceAndInterruptionOnPixel() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val language=InstrumentationRegistry.getArguments().getString("talkLanguage")?:"en"
        val file=File(context.getExternalFilesDir(null),"talk-validation/question-$language.pcm")
        assertTrue("Synthetic fixture missing",file.exists())
        val session=compose.activity.voice
        val conversationId=session.state.value.conversationId
        val output=File(context.getExternalFilesDir(null),"talk-validation/receipt-$language.json")
        output.delete()
        val began=android.os.SystemClock.elapsedRealtime()
        try { runBlocking {
            withContext(Dispatchers.Main) { session.debugInjectUtterance(file.readBytes()) }
            val answered=withTimeout(40000) { session.state.first { it.lines.any { line->line.role=="assistant" } || it.phase in listOf("Try again","Try again later","Reconnect") } }
            assertTrue(answered.toString(),answered.lines.any { it.role=="assistant" })
            withTimeout(25000) { session.state.first { session.debugRenderedSamples>0 || it.phase=="Voice unavailable" } }
            assertTrue("Expected streamed PCM rendered on the physical AudioTrack",session.debugRenderedSamples>0)
            val receipt=JSONObject().put("language",language).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-began)
                .put("provider",session.state.value.provider).put("playedSamples",session.debugPlayedSamples).put("renderedSamples",session.debugRenderedSamples)
                .put("conversationId",conversationId).put("transcript",org.json.JSONArray(session.state.value.lines.map { JSONObject().put("role",it.role).put("text",it.text) }))
            output.writeText(receipt.toString())
            withContext(Dispatchers.Main) { session.stopAnswer() }
            delay(350);assertFalse(session.debugOutputPlaying);assertEquals(0L,session.debugRenderedSamples);assertEquals(0,session.debugAudioSnapshot().getInt("queued"))
            receipt.put("interrupted",true);output.writeText(receipt.toString())
            if (InstrumentationRegistry.getArguments().getString("verifyReconnect")=="true") {
                val count=session.state.value.lines.size
                withContext(Dispatchers.Main) { session.connect() }
                withTimeout(20000) { session.state.first { it.phase in setOf("Listening","Muted") } }
                assertEquals(count,session.state.value.lines.size)
                assertEquals(conversationId,session.state.value.conversationId)
                receipt.put("reconnected",true)
            }
            output.writeText(receipt.toString())
        } } finally { compose.runOnUiThread { session.suspendSession() } }
    }
}
