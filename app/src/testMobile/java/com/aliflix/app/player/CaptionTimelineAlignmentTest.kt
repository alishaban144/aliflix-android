package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class CaptionTimelineAlignmentTest {
    @Test fun fullTimelineEstablishesEditionRateUsingWithheldSentences() {
        val target = List(40) { i -> SubtitleCue(i * 18.0 + 10, i * 18.0 + 14, "Distinct sentence about subject number $i") }
        val rate = 25.0 / 23.976
        val reference = target.map { it.copy(startSeconds = it.startSeconds * rate + 8, endSeconds = it.endSeconds * rate + 8) }
        val fit = requireNotNull(matchCaptionTimelines(target, reference))
        assertEquals(rate, fit.rate, 1e-9)
        assertEquals(8.0, fit.offset, 1e-9)
        assertNull(matchCaptionTimelines(target.take(4), reference.take(4)))
        assertNull(matchCaptionTimelines(target, reference.mapIndexed { i, cue -> if (i % 3 == 2) cue.copy(endSeconds = cue.endSeconds + 2) else cue }))
    }
    @Test fun wrongDialogueAndRepeatedSentencesCannotEstablishATimeline() {
        val target = List(40) { i -> SubtitleCue(i * 18.0 + 10, i * 18.0 + 14, "Distinct sentence about subject number $i") }
        assertNull(matchCaptionTimelines(target, target.map { it.copy(text = "Entirely unrelated and different movie") }))
        assertNull(matchCaptionTimelines(target.map { it.copy(text = "Same repeated sentence every time") },
            target.map { it.copy(text = "Same repeated sentence every time") }))
    }
}
