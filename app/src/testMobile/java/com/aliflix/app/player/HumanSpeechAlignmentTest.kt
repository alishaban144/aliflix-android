package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HumanSpeechAlignmentTest {
    @Test fun realSpeechEditBoundariesRequireUniqueEvidence() {
        val data = JSONObject(javaClass.getResource("/audio-sync-human-speech.json")!!.readText()).getJSONArray("clips")
        val truth = mutableListOf<SubtitleCue>(); val bits = mutableListOf<Double>()
        for (i in 0 until data.length()) {
            val clip = data.getJSONObject(i); val start = bits.size.toDouble() / SPEECH_HZ
            val spans = clip.getJSONArray("spans")
            for (j in 0 until spans.length()) spans.getJSONArray(j).let {
                if (it.getInt(2) == 1) truth.add(SubtitleCue(start + it.getDouble(0), start + it.getDouble(1), "هذا حوار عربي."))
            }
            bits.addAll(clip.getString("silero8").map { if (it == '1') 1.0 else 0.0 }); repeat(100) { bits.add(0.0) }
        }
        val windows = bits.chunked(1000).filter { it.size == 1000 }.mapIndexed { i, values -> SpeechWindow(i * 20.0, values.toDoubleArray()) }
        val reference = AdaptiveSubtitleSynchronizer.completeAudioReference(windows)!!
        var verified = 0
        for (boundary in listOf(120.0, 160.0, 200.0)) for (edit in listOf(18.0, 60.0, 100.0)) {
            val target = truth.map { cue -> val delay = 47 + if (cue.startSeconds >= boundary) edit else 0.0
                cue.copy(startSeconds = cue.startSeconds + delay, endSeconds = cue.endSeconds + delay) }
            val result = AdaptiveSubtitleSynchronizer.match(target, windows, reference)
            println("human_edit:$boundary/$edit:${result.reason}")
            result.correction?.let { correction ->
                verified++
                val aligned = correction.apply(target)
                assertEquals(truth.size, aligned.size)
                truth.indices.forEach { assertEquals("$boundary/$edit cue $it", truth[it].startSeconds, aligned[it].startSeconds, .6) }
            }
        }
        assertTrue("At least one verifiable real-speech edit must align", verified > 0)
    }
    @Test fun recordedHumanSpeechVerifiesArabicCuesWithoutUsingTheirText() {
        val data = JSONObject(javaClass.getResource("/audio-sync-human-speech.json")!!.readText())
        val clips = data.getJSONArray("clips")
        val cues = mutableListOf<SubtitleCue>()
        val speech = mutableListOf<Double>()
        for (i in 0 until clips.length()) {
            val clip = clips.getJSONObject(i)
            val start = speech.size.toDouble() / SPEECH_HZ
            val spans = clip.getJSONArray("spans")
            for (j in 0 until spans.length()) {
                val span = spans.getJSONArray(j)
                if (span.getInt(2) == 1) cues.add(SubtitleCue(start + span.getDouble(0), start + span.getDouble(1), "هذا حوار عربي."))
            }
            speech.addAll(clip.getString("silero8").map { if (it == '1') 1.0 else 0.0 })
            repeat(100) { speech.add(0.0) }
        }
        val windows = speech.chunked(40 * SPEECH_HZ).filter { it.size == 40 * SPEECH_HZ }
            .mapIndexed { i, bits -> SpeechWindow(i * 40.0, bits.toDoubleArray()) }
        // The subtitles are deliberately 47 seconds late. The acoustic evidence
        // comes from real neural decisions; subtitle spans are human annotations.
        val late = cues.map { it.copy(startSeconds = it.startSeconds + 47, endSeconds = it.endSeconds + 47) }
        val result = AdaptiveSubtitleSynchronizer.match(late, windows)
        assertNotNull(result.toString(), result.correction)
        assertEquals(-47.0, result.correction!!.offset, .35)
        assertEquals(1.0, result.correction.rate, .0001)
        assertTrue(result.score > .6)
        assertEquals(cues.first().text, result.correction.apply(late).first().text)
    }
}
