@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Development evidence only. Replay the optimized app's selected route without
 * timing answers, capture played PCM, and independently check its saved correction
 * outside the app. Private audio/requests are never packaged or committed. */
class PlaybackSourceCaptureDeviceTest {
    @Test fun captureTheExactSelectedSourceForIndependentTiming() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("captureMinifiedSource") == "true")
        val language = args.getString("captionLanguage") ?: "ar"
        require(language in listOf("ar","en"))
        grantNativeFixtureNetworkPermission()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val saved = JSONObject(File(root,"automatic-terminator-$language-private/minified-native-request.json").readText())
        val url = saved.getString("url")
        val start = args.getString("capturePositionMs")?.toLong() ?: 3_196_058L
        val duration = args.getString("captureDurationMs")?.toLong() ?: 48_000L
        require(start >= 0)
        require(duration in 48_000L..128_000L)
        saved.put("positionMs",start).put("playing",true)
        val output = File(root,"exact-minified-$language-private").apply { mkdirs() }
        val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<Double,ShortArray>>())
        try {
            ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
                .putExtra("request",saved.toString()),nativePhoneLaunchOptions()).use { scenario ->
                var position = 0.0
                fun current(): Double {
                    scenario.onActivity { position = (it.playbackController?.currentPosition ?: 0L) / 1000.0 }
                    return position
                }
                await(90_000) {
                    var ready = false
                    scenario.onActivity { ready = it.playbackUiState.ready }
                    ready && NativePlaybackService.activeRequest?.url == url && current() >= start/1000.0
                }
                NativePlaybackService.debugObserveSpeech { time,pcm -> frames.add(time to pcm) }
                await(duration + 25_000) { current() >= (start + duration)/1000.0 }
                NativePlaybackService.debugObserveSpeech(null)
                val end = current()
                val played = synchronized(frames) { frames.filter { it.first+.02 <= end }.toList() }
                assertTrue("Insufficient exact-source PCM",played.size >= 2_000)
                assertTrue("A seek gap must not be joined",played.zipWithNext().all { kotlin.math.abs(it.second.first-it.first.first-.02) < .003 })
                assertEquals(url,NativePlaybackService.activeRequest?.url)
                val wave = ByteBuffer.allocate(played.size*320+44).order(ByteOrder.LITTLE_ENDIAN)
                wave.put("RIFF".toByteArray()).putInt(played.size*320+36).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(played.size*320)
                played.forEach { (_,pcm) -> pcm.forEach(wave::putShort) }
                File(output,"scene-0.wav").writeBytes(wave.array())
                val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                File(output,"scene-0-clock.json").writeText(JSONObject().put("start",played.first().first)
                    .put("playedEnd",end).put("urlSha256",hash).toString())
                if (args.getString("captureGroqDialogue") == "true") {
                    val pcm = requireNotNull(NativePlaybackService.playedDialoguePcm(end))
                    val speech = NativePlaybackService.quickSpeechEvidence()
                    val sample = requireNotNull(dialogueRecoverySample(pcm, speech))
                    File(output,"recovery-sample.wav").writeBytes(dialogueWav(sample))
                    File(output,"recovery-clock.json").writeText(JSONObject().put("start",sample.start).put("urlSha256",hash).toString())
                    kotlinx.coroutines.runBlocking {
                        GroqDialogueRecognition.recognize(sample, speech) { transcripts ->
                            val rows = org.json.JSONArray()
                            transcripts.flatten().forEach { rows.put(JSONObject().put("text",it.text).put("start",it.start).put("end",it.end)) }
                            File(output,"recovery-words.json").writeText(rows.toString())
                        }
                    }
                }
            }
        } finally {
            NativePlaybackService.debugObserveSpeech(null)
            context.stopService(Intent(context,NativePlaybackService::class.java))
        }
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime()+timeout
        while (SystemClock.elapsedRealtime()<until) { if(condition())return;Thread.sleep(100) }
        fail("Exact source did not supply continuous played audio")
    }
}
