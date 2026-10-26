package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioSystem

class SileroSpeechSessionTest {
    @Test fun nativeOnnxRecurrentSessionMatchesLabelledSpeechBenchmark() {
        assumeTrue("Requires actual host ONNX JNI; no emulation", System.getenv("ALIFLIX_SYNC_HOST_VAD") == "1")
        val clip = JSONObject(javaClass.getResource("/audio-sync-human-speech.json")!!.readText()).getJSONArray("clips").getJSONObject(0)
        val wav = File(requireNotNull(System.getenv("ALIFLIX_SYNC_TESTSET")), clip.getString("name"))
        val bytes = AudioSystem.getAudioInputStream(wav).use { it.readAllBytes() }
        val pcm = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val downsampled = FloatArray(pcm.remaining() / 2) { (pcm.get().toInt() + pcm.get().toInt()) / 65536f }
        val oracle = clip.getString("silero8")
        SileroSpeechSession(File("src/mobile/assets/silero-vad-8k.onnx").readBytes()).use { session ->
            val probabilities = (0 until downsampled.size / 256).map { index ->
                session.probability(downsampled.copyOfRange(index * 256, (index + 1) * 256))
            }
            var agree = 0; var total = 0
            for (i in oracle.indices) {
                val frame = ((i + .5) * .02 / .032).toInt()
                if (frame >= probabilities.size) break
                if ((probabilities[frame] >= .5) == (oracle[i] == '1')) agree++
                total++
            }
            assertTrue("Native/model contract disagreement: $agree/$total", agree.toDouble() / total > .995)
            session.reset()
            assertEquals(probabilities.first(), session.probability(downsampled.copyOfRange(0, 256)), .00001f)
        }
    }
}
