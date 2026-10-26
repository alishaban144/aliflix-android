package com.aliflix.app.player

import androidx.test.platform.app.InstrumentationRegistry
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeVadDeviceTest {
    @Test fun recordedSpeechMeasuresNativeAccuracyAndAndroidCpuCost() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalSync") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val truth = parseTimedTextSubtitleCues(nativeSubtitlesVtt(File(root, "audio-sync-validation.json").readText(), 0.0))
        val wave = File(root, "audio-sync-validation.wav").readBytes()
        val samples = ShortArray((wave.size - 44) / 4)
        val pcm = ByteBuffer.wrap(wave, 44, wave.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        samples.indices.forEach { samples[it] = ((pcm.short.toInt() + pcm.short.toInt()) / 2).toShort() }
        val probabilities = mutableListOf<Float>(); val neuralTimes = mutableListOf<Double>()
        SileroSpeechSession(context.assets.open("silero-vad-8k.onnx").use { it.readBytes() }).use { neural ->
            for (start in samples.indices step 256) {
                val chunk = FloatArray(256) { samples.getOrNull(start + it)?.div(32768f) ?: 0f }
                val began = System.nanoTime(); probabilities.add(neural.probability(chunk))
                if (start >= 2560) neuralTimes.add((System.nanoTime() - began) / 1e6)
            }
        }
        val webTimes = mutableListOf<Double>(); val webCounts = IntArray(4); val neuralCounts = IntArray(4)
        fun count(values: IntArray, predicted: Boolean, annotated: Boolean) { values[if (annotated) { if (predicted) 0 else 2 } else { if (predicted) 1 else 3 }]++ }
        VadWebRTC(SampleRate.SAMPLE_RATE_8K, FrameSize.FRAME_SIZE_160, Mode.AGGRESSIVE).use { web ->
            for (start in 0..samples.size - 160 step 160) {
                val time = (start + 80) / 8000.0
                val annotated = truth.any { time >= it.startSeconds && time < it.endSeconds }
                val began = System.nanoTime(); val predicted = web.isSpeech(samples.copyOfRange(start, start + 160))
                if (start >= 1600) webTimes.add((System.nanoTime() - began) / 1e6)
                count(webCounts, predicted, annotated)
                count(neuralCounts, probabilities[(time / .032).toInt().coerceAtMost(probabilities.lastIndex)] >= .5f, annotated)
            }
        }
        fun metric(values: IntArray, times: List<Double>) = JSONObject().put("tp", values[0]).put("fp", values[1]).put("fn", values[2]).put("tn", values[3])
            .put("f1", 2.0 * values[0] / (2 * values[0] + values[1] + values[2]))
            .put("meanMs", times.average()).put("p95Ms", times.sorted()[(times.size * .95).toInt()])
        val web = metric(webCounts, webTimes); val neural = metric(neuralCounts, neuralTimes)
        File(root, "native-vad-device.json").writeText(JSONObject().put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            .put("seconds", samples.size / 8000.0).put("webRtc8", web).put("silero8", neural).toString(2))
        assertTrue("Measured WebRTC=$web; Silero=$neural", neural.getDouble("f1") > web.getDouble("f1") + .02)
        assertTrue("Silero must retain accurate speech detection: $neural", neural.getDouble("f1") > .92)
        assertTrue("32ms decisions need Android CPU headroom: $neural", neural.getDouble("p95Ms") < 10)
    }

    @Test fun realAndroidLayoutPlacesArabicPunctuationAtTheSentenceEnd() {
        val line = mobileCaptionText("هذا حوار عربي.")
        val paint = android.text.TextPaint().apply { textSize = 48f }
        val layout = android.text.StaticLayout.Builder.obtain(line, 0, line.length, paint, 800)
            .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
        assertEquals(-1, layout.getParagraphDirection(0))
        assertTrue("The final period belongs left of the RTL sentence", layout.getPrimaryHorizontal(line.indexOf('.')) < layout.getPrimaryHorizontal(1))
    }
}
