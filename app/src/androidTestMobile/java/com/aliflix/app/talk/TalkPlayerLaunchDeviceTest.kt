package com.aliflix.app.talk

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.MainActivity
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import com.aliflix.app.player.NativePlayerActivity
import com.aliflix.app.player.launchNativeSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Opt-in hardware check against real playback with the live microphone and reply path. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TalkPlayerLaunchDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun coldOpenReplyAndRapidReopenFromMovie() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkLaunch") == "true")
        val evidence = JSONObject()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val frames = ConcurrentLinkedQueue<Pair<Long, Double>>()
        val worker = HandlerThread("orb-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) to
                metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking {
                    val media = Media(1275779, MediaType.MOVIE, "Disclosure Day", year = "2026")
                    scenario.onActivity {
                        launchNativeSelection(it, PlaybackSelection(media, source = PlaybackSource(PlaybackProviderId.CINEJOY)), "EN", true)
                    }
                    val player = withTimeout(100_000) {
                        var candidate = playingActivity()
                        while (candidate == null) { delay(100); candidate = playingActivity() }
                        candidate
                    }
                    withContext(Dispatchers.Main) {
                        player.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
                    }
                    try {
                        player.talkCommand(JSONObject().put("action", "seek").put("numberValue", 6210.0))
                        withTimeout(25_000) {
                            while (withContext(Dispatchers.Main) { (player.playbackController?.currentPosition ?: 0L) < 6216_000L }) delay(40)
                        }
                        val voice = player.talkVoice
                        if (node("Talk") == null) {
                            val display = instrumentation.targetContext.resources.displayMetrics
                            tap(display.widthPixels / 3, display.heightPixels / 2)
                        }
                        val button = withTimeout(7_000) {
                            var bounds = node("Talk")
                            while (bounds == null) { delay(90); bounds = node("Talk") }
                            bounds
                        }
                        val cold = System.nanoTime()
                        android.util.Log.i("TalkLaunch207", "cold_open ${System.currentTimeMillis()}")
                        tap(button.centerX(), button.centerY())
                        awaitListening(voice)
                        evidence.put("coldReadyMs", (System.nanoTime() - cold) / 1_000_000)
                        delay(520)
                        evidence.put("coldFrames", openingFrames(frames, cold))

                        val question = talkValidationFixture("scene-206.pcm").readBytes()
                        val asked = SystemClock.elapsedRealtime()
                        android.util.Log.i("TalkLaunch207", "question ${System.currentTimeMillis()}")
                        withContext(Dispatchers.Main) { voice.debugInjectMicrophonePcm(question) }
                        withTimeout(60_000) {
                            while (voice.debugFirstAudioAt < asked || voice.state.value.sending ||
                                voice.debugAudioSnapshot().optBoolean("speaking")) delay(55)
                        }
                        evidence.put("targets", voice.debugToolCalls["scene_attention_planned_targets"] ?: 0)
                        check(voice.state.value.lines.lastOrNull { it.role == "assistant" }?.text?.isNotBlank() == true)
                        android.util.Log.i("TalkLaunch207", "answer_complete ${System.currentTimeMillis()}")
                        withContext(Dispatchers.Main) { player.closeTalk() }
                        delay(110)
                        val reopen = System.nanoTime()
                        withContext(Dispatchers.Main) { player.openTalk() }
                        awaitListening(voice)
                        evidence.put("reopenReadyMs", (System.nanoTime() - reopen) / 1_000_000)
                        delay(520)
                        evidence.put("reopenFrames", openingFrames(frames, reopen))
                        withContext(Dispatchers.Main) { player.closeTalk() }
                        check(player.talkCommand(JSONObject().put("action", "await_playback")).optBoolean("playing"))
                        evidence.put("playbackResumed", true)
                    } finally {
                        withContext(Dispatchers.Main) {
                            player.window.removeOnFrameMetricsAvailableListener(listener)
                            player.pauseAndLeavePlayer()
                        }
                    }
                }
            }
        } finally {
            worker.quitSafely()
            File(directory, "talk-launch-207.json").writeText(evidence.toString(2))
        }
    }

    private fun openingFrames(frames: Collection<Pair<Long, Double>>, start: Long) =
        JSONArray(frames.filter { (time, _) -> (time - start) / 1_000_000 in 0L..450L }
            .map { (time, duration) -> JSONObject().put("afterTapMs", (time - start) / 1_000_000).put("durationMs", duration) })

    private suspend fun playingActivity() = withContext(Dispatchers.Main) {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<NativePlayerActivity>().firstOrNull { it.playbackController?.isPlaying == true }
    }

    private suspend fun awaitListening(voice: TalkVoiceSession) = withTimeout(50_000) {
        while (!withContext(Dispatchers.Main) { voice.playerVoiceReady() && voice.state.value.phase == "Listening" }) delay(45)
    }

    private fun node(label: String): Rect? {
        fun find(current: AccessibilityNodeInfo?): Rect? {
            if (current == null) return null
            if (current.isVisibleToUser && (current.text?.toString() == label || current.contentDescription?.toString() == label))
                return Rect().also(current::getBoundsInScreen).takeUnless(Rect::isEmpty)
            for (index in 0 until current.childCount) find(current.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun tap(x: Int, y: Int) {
        val at = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
    }
}
