package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class TranslatedSubtitleSyncTest {
    @Test fun independentClocksComposeWithoutMovingReferenceRegionIndexes() {
        val target = AudioSubtitleCorrection(-7.0, 24.0 / 25, .92, listOf(TimingRegion(20, -5.0)))
        val reference = AudioSubtitleCorrection(2.0, 25.0 / 24, .89)
        val composed = requireNotNull(composeSubtitleClocks(target, reference))
        assertEquals(1.0, composed.rate, 1e-9)
        assertEquals(-7 * 25.0 / 24 + 2, composed.offset, 1e-9)
        assertEquals(20, composed.regions.single().firstCue)
        assertEquals(-5 * 25.0 / 24 + 2, composed.regions.single().offset, 1e-9)
        assertNull(composeSubtitleClocks(target, reference.copy(regions = listOf(TimingRegion(7, 2.0)))))
    }
    @Test fun translatedTimingRequiresAnIndependentReferenceAndCannotCertifyUnheardSpeech() {
        val random = Random(435)
        var time = 0.0
        val reference = List(120) {
            time += .8 + random.nextDouble() * 4
            val start = time
            time += .8 + random.nextDouble() * 3
            SubtitleCue(start, time, "Distinct dialogue sentence")
        }
        val translated = reference.map { it.copy(startSeconds = it.startSeconds + 7.25, endSeconds = it.endSeconds + 7.25, text = "حوار باللغة العربية") }
        val prepared = requireNotNull(TranslatedSubtitleSync.prepare(translated, reference))
        assertEquals(-7.25, prepared.targetToReference.offset, .12)
        assertNull(TranslatedSubtitleSync.match(prepared, emptyList(), emptyList(), emptyList(), 100.0))
        val repeated = List(160) { SubtitleCue(it * 3.0, it * 3.0 + 1, "Repeated dialogue") }
        assertNull(TranslatedSubtitleSync.prepare(repeated, reference))
    }
}
