package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class MobileSkipMarkersTest {
    @Test fun aniSkipUsesExactDynamicTimesAndRejectsADifferentCut() {
        val raw = """{"found":true,"results":[{"skipType":"op","interval":{"startTime":1.125,"endTime":102.5},"episodeLength":1429.2},{"skipType":"ed","interval":{"startTime":1295.974,"endTime":1386.724},"episodeLength":1416.1904}]}"""
        val markers = parseAniSkipMarkers(raw, 1_429_200)
        assertEquals(2, markers.size)
        assertEquals(1_295_974, markers.last().startMs)
        assertTrue(parseAniSkipMarkers(raw, 1_600_000).isEmpty())
        assertTrue(parseAniSkipMarkers("""{"found":false}""").isEmpty())
    }
    @Test fun introHaterRequiresExactEpisodeIdentityAndValidIntervals() {
        val raw = """[{"videoId":"tt123:1:2","label":"Intro","start":10.25,"end":88.1},{"videoId":"tt123:1:3","label":"Outro","start":1200,"end":1300},{"videoId":"tt123:1:2","label":"Recap","start":0,"end":10},{"videoId":"tt123:1:2","label":"Outro","start":200,"end":100}]"""
        assertEquals(listOf(IntroSegment(IntroSegmentKind.INTRO, 10_250, 88_100)), parseIntroHaterMarkers(raw, "tt123:1:2"))
    }
    @Test fun sourcesFillMissingKindsWithoutCombiningConflictingEndpoints() {
        val intro = IntroSegment(IntroSegmentKind.INTRO, 10, 20)
        val outro = IntroSegment(IntroSegmentKind.OUTRO, 900, 1100)
        val conflicting = IntroSegment(IntroSegmentKind.OUTRO, 800, 950)
        assertEquals(listOf(intro, outro), selectSkipMarkers(listOf(listOf(intro), listOf(outro), listOf(conflicting)), 1000))
        assertEquals(listOf(intro, conflicting), selectSkipMarkers(listOf(listOf(intro), listOf(outro.copy(startMs = 1200, endMs = 1300)), listOf(conflicting)), 1000))
    }
}
