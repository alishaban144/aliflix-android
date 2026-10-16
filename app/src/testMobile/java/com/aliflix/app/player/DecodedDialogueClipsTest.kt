package com.aliflix.app.player
import org.junit.Assert.*
import org.junit.Test
class DecodedDialogueClipsTest {
    @Test fun segmentGapsRemainSeparateAndEarlierCompleteClipsSurvive() {
        val clips=DecodedDialogueClips()
        for (i in 0 until 300) clips.append(120+i*.02,ShortArray(320),1)
        for (i in 0 until 300) clips.append(126.04+i*.02,ShortArray(320),2)
        val saved=clips.snapshot()
        assertEquals(2,saved.size);assertEquals(120.0,saved[0].start,0.0)
        assertEquals(126.04,saved[1].start,0.0);assertEquals(300,saved[0].frames.size)
    }
    @Test fun partialAndDuplicateOutputCannotBecomeACompleteClipOrInventedSilence() {
        val clips=DecodedDialogueClips()
        for (i in 0 until 200) { clips.append(i*.02,ShortArray(320),1);clips.append(i*.02,ShortArray(320),1) }
        clips.append(Double.NaN,ShortArray(320),1)
        clips.append(4.0,ShortArray(160),1)
        assertEquals(200,clips.snapshot().single().frames.size)
    }
}
