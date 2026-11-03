package com.aliflix.app.talk

import android.graphics.Bitmap
import android.view.FrameMetrics
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class Talk189OrbDeviceTest {
    @Test fun contextStatesStayDistinctAndMotionPreferenceIsRespected() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=launchFreshTalkActivity(localAudio=true)
        val voice=activity.voice
        val folder=File(i.targetContext.getExternalFilesDir(null),"talk189-orb").apply { mkdirs() }
        fun shell(command:String)=i.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val original=shell("settings get global animator_duration_scale")
        val frames=java.util.Collections.synchronizedList(mutableListOf<Double>())
        val listener=Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0 }
        fun capture(name:String) {
            i.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        try { runBlocking {
            i.runOnMainSync { activity.window.addOnFrameMetricsAvailableListener(listener,android.os.Handler(android.os.Looper.getMainLooper())) }
            delay(1200)
            val modes=listOf(Triple("listen","Listening",TalkEvidenceStage.NONE),Triple("recall","Thinking",TalkEvidenceStage.RECALL),
                Triple("observe","Thinking",TalkEvidenceStage.OBSERVE),Triple("think","Thinking",TalkEvidenceStage.NONE),
                Triple("speak","Speaking",TalkEvidenceStage.NONE),Triple("muted","Muted",TalkEvidenceStage.NONE),Triple("reconnect","Reconnect",TalkEvidenceStage.NONE))
            for((name,phase,stage) in modes) {
                i.runOnMainSync { voice.debugPreviewOrbContext(phase,"",stage) }
                delay(1300);capture(name)
                val expected=talkOrbAppearance(talkOrbActivity(voice.state.value)).label
                if(android.os.Build.VERSION.SDK_INT>=33)i.uiAutomation.clearCache()
                fun labels(node:android.view.accessibility.AccessibilityNodeInfo?):List<String> = node?.let { root ->
                    listOfNotNull(root.stateDescription?.toString())+(0 until root.childCount).flatMap { labels(root.getChild(it)) }
                }.orEmpty()
                assertTrue("Missing semantic activity $expected",labels(i.uiAutomation.rootInActiveWindow).contains(expected))
            }
            shell("settings put global animator_duration_scale 0")
            i.runOnMainSync { voice.debugPreviewOrbContext("Thinking","",TalkEvidenceStage.OBSERVE) }
            delay(800);capture("reduced-motion-observe")
            i.runOnMainSync { voice.setTextMode(true) }
            delay(800);capture("reading")
            assertEquals(TalkOrbActivity.READ,talkOrbActivity(voice.state.value))
        } } finally {
            val sorted=frames.toList().sorted()
            File(folder,"frames.json").writeText(JSONObject().put("sampleCount",sorted.size).put("p50Ms",sorted.getOrNull(sorted.size/2))
                .put("p95Ms",sorted.getOrNull((sorted.size*.95).toInt().coerceAtMost((sorted.size-1).coerceAtLeast(0))))
                .put("over33Ms",sorted.count { it>33.34 }).put("samplesMs",JSONArray(sorted)).toString(2))
            if(original.matches(Regex("[0-9.]+")))shell("settings put global animator_duration_scale $original") else shell("settings delete global animator_duration_scale")
            i.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener);voice.suspendSession();activity.finish() }
        }
    }
}
