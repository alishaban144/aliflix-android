package com.aliflix.app.player

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class CaptionGapAlignmentTest {
    private fun reference(seed: Long = 43): List<SubtitleCue> {
        val random = Random(seed)
        var time = 50.0
        return List(100) { i ->
            time += 6 + random.nextDouble() * 24
            val from = time
            time += 3 + random.nextDouble() * 7
            SubtitleCue(from,time,"Independent sentence number $i")
        }
    }
    @Test fun translationAndCueSplitsRetainAnIndependentlyVerifiedFullEditionClock() {
        val reference = reference()
        for (rate in listOf(1.0,25.0/23.976,23.976/25)) {
            val target = reference.flatMap { cue ->
                val from=(cue.startSeconds-11.3)/rate;val end=(cue.endSeconds-11.3)/rate;val middle=(from+end)/2
                listOf(SubtitleCue(from,middle,"كلمات مترجمة مستقلة"),SubtitleCue(middle,end,"تكملة كلمات مترجمة"))
            }
            val correction = requireNotNull(matchCaptionGaps(target,reference))
            assertEquals(rate,correction.rate,1e-9)
            assertEquals(11.3,correction.offset,.01)
        }
    }
    @Test fun languageSpecificCueHangoverDoesNotBiasTheWholeCaptionClockEarly() {
        val reference = reference()
        val target = reference.map { it.copy(startSeconds=it.startSeconds+7.45,endSeconds=it.endSeconds+6.85,text="كلمات مترجمة") }
        val correction = requireNotNull(matchCaptionGaps(target,reference))
        assertEquals(-7.25,correction.offset,.15)
        assertTrue(correction.apply(target).zip(reference).all { (a,b) ->
            kotlin.math.abs(a.startSeconds-b.startSeconds)<.35 && kotlin.math.abs(a.endSeconds-b.endSeconds)<.35
        })
    }
    @Test fun unrelatedAndAmbiguousRhythmsAndEditedTailsReject() {
        val reference = reference()
        assertNull(matchCaptionGaps(reference,reference(72)))
        val periodic = List(100) { SubtitleCue(it*20.0+10,it*20.0+14,"Same repeated dialogue") }
        assertNull(matchCaptionGaps(periodic,periodic))
        assertNull(matchCaptionGaps(reference,reference.mapIndexed { i,cue -> if (i>60) cue.copy(startSeconds=cue.startSeconds+20,endSeconds=cue.endSeconds+20) else cue }))
        assertNull(matchCaptionGaps(emptyList(),reference))
    }
}
