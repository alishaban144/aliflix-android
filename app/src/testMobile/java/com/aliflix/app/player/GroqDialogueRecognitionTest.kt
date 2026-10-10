package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GroqDialogueRecognitionTest {
    private val sample = PlayedDialoguePcm(120.0, List(1_000) { ShortArray(320) { 512 } })
    @Test fun firstTwoRequestsCoverSeparateConversationsBeforeRepeatingTheBestOne() {
        val retained = sample.copy(frames = List(6_000) { ShortArray(320) })
        val bits = DoubleArray(6_000)
        for (start in listOf(400, 600, 800, 4_400, 4_580, 4_760, 4_930))
            for (i in start until start + 100) bits[i] = 1.0
        val clips = dialogueRecoverySamples(retained, listOf(SpeechWindow(120.0, bits)))
        assertTrue(clips.first().start > 190)
        assertTrue(clips[1].start < 135)
        assertTrue(clips[1].start + clips[1].frames.size * .02 <= clips.first().start)
        assertTrue(clips.size <= 6 && clips.sumOf { it.frames.size } <= 6_000)
    }
    @Test fun quietFocusedRecognitionCopyUsesBoundedGainWithoutChangingPlaybackPcm() {
        val quiet=sample.copy(frames=sample.frames.take(500))
        val normalized=ByteBuffer.wrap(dialogueWav(quiet,normalizeQuietAudio=true)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2048,normalized.getShort(44).toInt())
        assertEquals(512,quiet.frames.first().first().toInt())
        val loud=quiet.copy(frames=List(500) { ShortArray(320) { 30_000 } })
        assertEquals(30_000,ByteBuffer.wrap(dialogueWav(loud,true)).order(ByteOrder.LITTLE_ENDIAN).getShort(44).toInt())
    }
    @Test fun cropEdgeWordsRemainLexicalContextWithoutCertifyingInventedBoundaries() {
        val words=parseGroqDialogue(JSONObject("""{"words":[
          {"text":"Hey,","start":0,"end":2.72},
          {"text":"you","start":2.72,"end":2.74},
          {"text":"got","start":2.74,"end":2.86},
          {"text":"phone?","start":3.1,"end":3.56}]}"""),sample)
        assertEquals(listOf("Hey,","you","got","phone?"),words.map { it.text })
        assertFalse(words.first().startReliable)
        assertFalse(words.first().endReliable)
        assertFalse(words.first().measuredEnd)
        assertTrue(words.last().startReliable && words.last().measuredEnd)
    }
    @Test fun earlierShortSpeechGetsFocusedClipsInsteadOfSpendingEverySlotOnMusic() {
        val retained=sample.copy(frames=List(6_000) { ShortArray(320) })
        val bits=DoubleArray(6_000)
        for(start in listOf(310,420)) for(i in start until start+60) bits[i]=1.0
        for(start in listOf(2_010,2_210,2_450)) for(i in start until start+120) bits[i]=1.0
        for(i in 5_000 until 6_000) bits[i]=1.0
        val clips=dialogueRecoverySamples(retained,listOf(SpeechWindow(120.0,bits)))
        val focused=clips.filter { it.start < 130 && it.frames.size < 1_000 }
        assertTrue(focused.size >= 2)
        assertTrue(focused.all { it.start <= 126.2 && it.start+it.frames.size*.02 >= 129.6 })
        assertTrue(clips.size <= 6 && clips.sumOf { it.frames.size } <= 6_000)
    }
    @Test fun focusedSpeechAtThePlayedBoundaryStillUsesFourSecondsOfRealPcm() {
        val retained=sample.copy(frames=List(6_000) { ShortArray(320) })
        val bits=DoubleArray(6_000)
        for(start in listOf(2_010,2_210,2_450)) for(i in start until start+120) bits[i]=1.0
        for(i in 5_890 until 5_980) bits[i]=1.0
        val clips=dialogueRecoverySamples(retained,listOf(SpeechWindow(120.0,bits)))
        assertTrue(clips.all { it.frames.size in 200..1_000 && it.start+it.frames.size*.02 <= 240.0 })
        assertTrue(clips.any { it.start > 230 && it.frames.size < 1_000 })
    }
    @Test fun boundedWavPreservesTheDecodedSampleRateAndContainsOnlyAudio() {
        val bytes = dialogueWav(sample)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(640_044, bytes.size)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(16_000, header.getInt(24)); assertEquals(32_000, header.getInt(28))
        assertEquals(512, header.getShort(44).toInt())
        for (frames in listOf(199, 1_001)) {
            try { dialogueWav(sample.copy(frames = List(frames) { ShortArray(320) })); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun responseUsesActualMediaTimestampsAndRejectsInvalidOrUnplayedWords() {
        val result = parseGroqDialogue(JSONObject("""{"words":[
          {"text":"hello","start":2.1,"end":2.5},
          {"text":"ahead","start":19.9,"end":20.1},
          {"text":"invalid","start":3,"end":2},
          {"text":"negative","start":-1,"end":0.2},
          {"text":"missing"}]}"""), sample)
        assertEquals(listOf(HeardWord("hello",122.1,122.5,measuredEnd=true)),result)
        assertTrue(playedRecognitionWords(result,122.4).isEmpty())
        assertEquals(result,playedRecognitionWords(result,122.5))
    }
    @Test fun paddedWordEndIsBoundedByTheNextMeasuredOnsetWithinTheSameTranscript() {
        val words=parseGroqDialogue(JSONObject("""{"words":[
          {"text":"number","start":3.92,"end":4.88},
          {"text":"All","start":4.5,"end":5.06}]}"""),sample)
        assertEquals(124.5,words.first().end,.00001)
        assertEquals(124.5,words.first().playedThrough,.00001)
        assertEquals(125.06,words.last().end,.00001)
    }
    @Test fun regressiveSegmentBoundaryOnsetsPreserveWordsButCannotSupplyAnInventedOnset() {
        val words=parseGroqDialogue(JSONObject("""{"words":[
          {"text":"number","start":9.88,"end":10.48},
          {"text":"All","start":9.5,"end":10.98},
          {"text":"lines","start":10.98,"end":11.48}]}"""),sample)
        assertEquals(listOf("number","All","lines"),words.map { it.text })
        assertFalse(words[1].startReliable)
        assertTrue(words[1].endReliable)
        assertEquals(130.48,words[0].end,.00001)
        assertEquals(130.48,words[1].start,.00001)
    }
    @Test fun oneUploadContainsAtMostTwentySecondsEvenWhenEarlierAudioIsRetained() {
        assertNull(dialogueRecoverySample(sample.copy(frames=List(199) { ShortArray(320) }),emptyList()))
        val retained=sample.copy(frames=List(6_000) { ShortArray(320) })
        val latest=requireNotNull(dialogueRecoverySample(retained,emptyList()))
        assertEquals(220.0,latest.start,.00001)
        assertEquals(1_000,latest.frames.size)
        assertEquals(640_044,dialogueWav(latest).size)
    }
    @Test fun completedEarlierDialogueOutranksSilenceAndContinuousMusicWithoutCaptionAnswers() {
        val retained=sample.copy(frames=List(6_000) { ShortArray(320) })
        val bits=DoubleArray(6_000)
        for (start in listOf(2_010,2_210,2_450)) for(i in start until start+120) bits[i]=1.0
        for(i in 5_000 until 6_000) bits[i]=1.0
        val chosen=requireNotNull(dialogueRecoverySample(retained,listOf(SpeechWindow(120.0,bits))))
        assertTrue(chosen.start <= 160.2 && chosen.start+20 >= 171.4)
        assertEquals(1_000,chosen.frames.size)
        val clips=dialogueRecoverySamples(retained,listOf(SpeechWindow(120.0,bits)))
        assertTrue(clips.any { it.start < chosen.start && it.start+20 > chosen.start })
        assertTrue(clips.any { it.start > chosen.start && it.start < chosen.start+20 })
    }
    @Test fun boundedRecoveryIncludesShiftedPhrasesAndNeverUploadsMoreThanTwoMinutesIncludingOverlap() {
        val retained=sample.copy(frames=List(6_000) { ShortArray(320) })
        val clips=dialogueRecoverySamples(retained,emptyList())
        assertTrue(clips.size <= 6)
        assertTrue(clips.sumOf { it.frames.size } <= 6_000)
        assertTrue(clips.all { it.frames.size in 200..1_000 && it.start >= retained.start && it.start+it.frames.size*.02 <= 240.0 })
        assertEquals(clips.size,clips.map { it.start }.distinct().size)
        assertEquals(1,dialogueRecoverySamples(sample,emptyList()).size)
    }
}
