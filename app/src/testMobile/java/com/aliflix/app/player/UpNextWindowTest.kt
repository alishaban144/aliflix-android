package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class UpNextWindowTest {
    @Test fun everyFiniteEpisodeHasAFinalWindowWithoutMetadata() {
        assertEquals(1_170_000L, upNextWindowStart(1_200_000, emptyList()))
        assertEquals(9_000L, upNextWindowStart(10_000, emptyList()))
        assertNull(upNextWindowStart(0, emptyList()))
    }
    @Test fun outroStartsEarlierAndOfferSurvivesMarkerEnd() {
        val outro = IntroSegment(IntroSegmentKind.OUTRO, 1_100_000, 1_160_000)
        val start = upNextWindowStart(1_200_000, listOf(outro))!!
        assertEquals(1_100_000L, start)
        assertTrue(1_190_000 >= start)
        assertTrue(1_200_000 >= start)
    }
    @Test fun validCreditStartIsRetainedWhenTheStreamsEndDiffersFromTheDatabase() {
        // Real IntroDB Breaking Bad S1E1: 3431..3500 seconds.
        val outro = IntroSegment(IntroSegmentKind.OUTRO, 3_431_000, 3_500_000)
        assertEquals(3_431_000L, upNextWindowStart(3_492_000, listOf(outro)))
        assertEquals(3_430_000L, upNextWindowStart(3_460_000, listOf(outro)))
    }
    @Test fun invalidOrIntroOnlyMetadataCannotHideTheOffer() {
        assertEquals(1_170_000L, upNextWindowStart(1_200_000, listOf(
            IntroSegment(IntroSegmentKind.INTRO, 0, 20_000),
            IntroSegment(IntroSegmentKind.OUTRO, -1, 5),
            IntroSegment(IntroSegmentKind.OUTRO, 1_300_000, 1_400_000))))
    }
}
