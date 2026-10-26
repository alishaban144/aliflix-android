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
        val began=android.os.SystemClock.elapsedRealtime()
        try { runBlocking {
            withContext(Dispatchers.Main) { session.debugInjectUtterance(file.readBytes()) }
            val answered=withTimeout(40000) { session.state.first { it.lines.any { line->line.role=="assistant" } || it.phase in listOf("Try again","Try again later","Reconnect") } }
            assertTrue(answered.toString(),answered.lines.any { it.role=="assistant" })
            withTimeout(25000) { session.state.first { session.debugPlayedSamples>0 || it.phase=="Voice unavailable" } }
            assertTrue("Expected streamed PCM rendered on the physical AudioTrack",session.debugPlayedSamples>0)
            val receipt=JSONObject().put("language",language).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-began)
                .put("provider",session.state.value.provider).put("playedSamples",session.debugPlayedSamples)
                .put("transcript",org.json.JSONArray(session.state.value.lines.map { JSONObject().put("role",it.role).put("text",it.text) }))
            withContext(Dispatchers.Main) { session.debugInterrupt() }
            delay(350);assertEquals(0,session.debugPlayedSamples)
            receipt.put("interrupted",true)
            if (InstrumentationRegistry.getArguments().getString("verifyReconnect")=="true") {
                val count=session.state.value.lines.size
                withContext(Dispatchers.Main) { session.debugReconnect() }
                withTimeout(10000) { session.state.first { it.phase=="Reconnecting" } }
                withTimeout(20000) { session.state.first { it.phase=="Listening" } }
                assertEquals(count,session.state.value.lines.size)
                receipt.put("reconnected",true)
            }
            val output=File(context.getExternalFilesDir(null),"talk-validation/receipt-$language.json");output.writeText(receipt.toString())
        } } finally { compose.runOnUiThread { session.suspendSession() } }
    }
}
