package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkSceneContextTest {
    @Test fun frameSelectionExcludesFutureStaleAndEmptyEvidenceButRetainsTemporalOrder() {
        val frames = listOf(-1.0, 2.0, 50.0, 70.0, 84.0, 94.0, 100.0, 101.0).map { TalkSceneFrame(it, byteArrayOf(1)) } + TalkSceneFrame(99.0, byteArrayOf())
        val selected = sceneFrameSelection(frames, 100.0)
        assertEquals(listOf(70.0, 84.0, 94.0, 100.0), selected.map { it.seconds })
        assertTrue(selected.all { it.seconds <= 100 && it.jpeg.isNotEmpty() })
    }
    @Test fun aSingleFrameIsUsefulAndNeverDuplicatedIntoAnInventedSequence() {
        assertEquals(1, sceneFrameSelection(listOf(TalkSceneFrame(5.0, byteArrayOf(1))), 5.0).size)
        assertTrue(sceneFrameSelection(emptyList(), 5.0).isEmpty())
    }
    @Test fun replayUsesTheRecentExchangeInsteadOfAnUnrelatedEarlierCaption() {
        assertEquals(91.4, sceneReplayStart(100.0, listOf(3.0 to 5.0, 80.0 to 83.0, 92.0 to 97.0)), .001)
        assertEquals(87.4, sceneReplayStart(100.0, emptyList()), .001)
        assertEquals(0.0, sceneReplayStart(0.0, emptyList()), .001)
    }
    @Test fun replayNeverSelectsFutureMalformedOrDistantDialogue() {
        assertEquals(87.4, sceneReplayStart(100.0, listOf(Double.NaN to 90.0, 115.0 to 117.0, 2.0 to 4.0)), .001)
    }
    @Test fun replayIncludesBothSidesOfAConnectedExchangeWithoutCrossingThePreviousScene() {
        assertEquals(79.4, sceneReplayStart(100.0, listOf(70.0 to 74.0, 80.0 to 83.0, 84.0 to 90.0, 92.0 to 97.0)), .001)
        assertEquals(91.4, sceneReplayStart(100.0, listOf(92.0 to 97.0, 90.0 to 85.0, 98.0 to 101.0)), .001)
    }
    @Test fun sceneReadIsAllowedForQuestionsWhileReplayStillRespectsNegationAndHypotheticals() {
        assertTrue(talkActionRequested("scene_context", "Why did she do that?"))
        assertTrue(talkActionRequested("replay_scene", "Let me hear that exchange again."))
        assertFalse(talkActionRequested("replay_scene", "Don't replay it, just explain."))
        assertFalse(talkActionRequested("replay_scene", "What if I asked you to replay this?"))
        assertFalse(talkActionRequested("pause", "I don't understand what just happened. Explain this scene."))
        assertTrue(talkActionRequested("replay_scene", "I don't understand what just happened. Then replay that exchange."))
    }
    @Test fun immediatePlayerCommandsDoNotWaitForScenePerceptionWhileSceneQuestionsDo() {
        assertTrue(talkSceneQuestion("I don't understand what just happened"))
        assertTrue(talkSceneQuestion("Was ist gerade passiert?"))
        assertTrue(talkSceneQuestion("ماذا حدث للتو؟"))
        assertFalse(talkSceneQuestion("Lower the volume"))
        assertFalse(talkSceneQuestion("Seek to three minutes and resume"))
    }
    @Test fun sceneAudioIsARealBoundedWaveAndExcludesDecodeAhead() {
        val frames = (0 until 800).map { index -> ShortArray(320) { index.toShort() } }
        val clip = playedSceneAudio(10.0, frames, 24.0)!!
        assertEquals(12.0, clip.startSeconds, .001)
        assertTrue(clip.endSeconds<=24.0)
        val wav=java.nio.ByteBuffer.wrap(clip.wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",String(clip.wav.copyOfRange(0,4),Charsets.US_ASCII))
        assertEquals(16000,wav.getInt(24));assertEquals(32000,wav.getInt(28))
        assertEquals(384000,wav.getInt(40));assertEquals(100.toShort(),wav.getShort(44))
        assertEquals(699.toShort(),wav.getShort(clip.wav.size-2))
        assertEquals(384044,clip.wav.size)
    }
    @Test fun insufficientOrMalformedPlayedAudioNeverBecomesInventedEvidence() {
        assertNull(playedSceneAudio(10.0,List(100){ShortArray(320)},12.0))
        assertNull(playedSceneAudio(10.0,List(400){ShortArray(319)},18.0))
        assertNull(playedSceneAudio(10.0,List(400){ShortArray(160)},18.0))
        assertNull(playedSceneAudio(Double.NaN,List(400){ShortArray(320)},18.0))
    }
}
