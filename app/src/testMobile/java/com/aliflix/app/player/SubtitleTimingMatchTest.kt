package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimingMatchTest {
    @Test fun rendererTimestampOffsetIsRemovedBeforeSpeechAlignment() {
        assertEquals(147.927, subtitleMediaSeconds(1000147927000L, 1000000000000L, 0, 48000), .000001)
        assertEquals(148.427, subtitleMediaSeconds(1000147927000L, 1000000000000L, 24000, 48000), .000001)
    }
    private val cues = listOf(
        SubtitleCue(100.0, 103.0, "Please come back home today"),
        SubtitleCue(108.0, 111.0, "There is someone outside now"),
        SubtitleCue(1000.0, 1003.0, "We should leave this place"),
        SubtitleCue(1008.0, 1011.0, "Nobody knows what happened here"),
    )
    private fun words(offset: Double, rate: Double = 1.0) = cues.flatMapIndexed { index, cue ->
        cue.text.split(" ").mapIndexed { word, text -> TimedSpeechWord(text, cue.startSeconds * rate + offset + word * .2, index / 2) }
    }
    @Test fun correctsOffsetUsingIndependentScenes() {
        val match = matchSubtitleTiming(cues, words(7.5))!!
        assertEquals(7.5, match.offset, .001); assertEquals(4, match.anchors)
        assertEquals(107.5, match.apply(cues).first().startSeconds, .001)
    }
    @Test fun smallSceneTimingVariationDoesNotIntroduceWholeFilmDrift() {
        val spoken = words(3.0).map { if (it.sample == 1) it.copy(seconds = it.seconds + .7) else it }
        assertEquals(1.0, matchSubtitleTiming(cues, spoken)!!.rate, .000001)
    }
    @Test fun correctsPalFrameRateWithoutAccumulatingDrift() {
        val match = matchSubtitleTiming(cues, words(-3.0, 25.0 / 24.0))!!
        assertEquals(25.0 / 24.0, match.rate, .00001)
        assertEquals(7297.0, 7008 * match.rate + match.offset, .001)
    }
    @Test fun refusesOneSceneAndRepeatedDialogue() {
        assertNull(matchSubtitleTiming(cues, words(3.0).filter { it.sample == 0 }))
        assertNull(matchSubtitleTiming(cues, words(3.0) + words(20.0)))
    }
    @Test fun refusesDifferentCutsAndTranslatedUnmatchedText() {
        val wrong = words(3.0).map { if (it.sample == 1) it.copy(seconds = it.seconds + 31) else it }
        assertNull(matchSubtitleTiming(cues, wrong))
        assertNull(matchSubtitleTiming(cues.map { it.copy(text = "Una frase completamente diferente aqui") }, words(3.0)))
    }
    @Test fun zeroOffsetRemainsZeroAndNegativeCuesAreClipped() {
        assertEquals(0.0, matchSubtitleTiming(cues, words(0.0))!!.offset, .001)
        val shifted = SubtitleTimingMatch(-2.0, 1.0, 4, 0.0).apply(listOf(SubtitleCue(0.0, 1.0, "Gone"), SubtitleCue(1.0, 3.0, "Visible")))
        assertEquals(1, shifted.size); assertEquals(0.0, shifted.single().startSeconds, .001)
    }
}
