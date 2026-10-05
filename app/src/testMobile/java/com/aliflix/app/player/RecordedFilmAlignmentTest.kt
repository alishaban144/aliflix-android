package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Replay of fingerprints captured from actual Android film playback,
 * with that film's published captions. Neither signal is generated from cues.
 */
class RecordedFilmAlignmentTest {
    @Test fun capturedFilmAudioVerifiesPublishedSubtitleTiming() {
        val path = System.getenv("ALIFLIX_REAL_FILM_EVIDENCE")
        val data = JSONObject(if (!path.isNullOrBlank()) File(path).readText() else
            requireNotNull(javaClass.getResourceAsStream("/audio-sync-tears-of-steel.json"))
                .bufferedReader().use { it.readText() })
        val target = parseTimedTextSubtitleCues(nativeSubtitlesVtt(data.getString("target"), 0.0))
        val audio = data.getJSONArray("windows").let { array -> (0 until array.length()).map {
            val window = array.getJSONObject(it)
            SpeechWindow(window.getDouble("start"), window.getString("bits").map { bit -> if (bit == '1') 1.0 else 0.0 }.toDoubleArray())
        } }
        println("real_film_runs:${audio.map { listOf(it.start, it.speech.size / 50.0, it.speech.average()) }}")
        val result = AdaptiveSubtitleSynchronizer.matchQuick(target, audio)
        println("real_film_result:$result")
        assertNotNull(result.toString(), result.correction)
        assertEquals(-7.25, result.correction!!.offset, .65)
        assertEquals(1.0, result.correction.rate, .0001)
        val truth = parseTimedTextSubtitleCues(nativeSubtitlesVtt(data.getString("truth"), 0.0))
        assertTrue("The published reference must independently agree with selected decoded audio",
            AdaptiveSubtitleSynchronizer.verifyReferenceQuick(truth, audio))
        val rate = 25.0 / 24.0
        val drifting = truth.map { it.copy(startSeconds = it.startSeconds / rate + 7.25, endSeconds = it.endSeconds / rate + 7.25) }
        val fromReference = AdaptiveSubtitleSynchronizer.match(drifting,
            AdaptiveSubtitleSynchronizer.referenceWindows(truth), truth)
        assertNotNull("Complete verified reference can establish a full-film FPS clock: $fromReference", fromReference.correction)
        assertEquals(rate, fromReference.correction!!.rate, .0004)
        assertTrue(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(fromReference.correction.apply(drifting), audio))
    }
}
