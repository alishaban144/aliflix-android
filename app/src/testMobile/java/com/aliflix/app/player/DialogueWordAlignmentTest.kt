package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class DialogueWordAlignmentTest {
    private val phrases = listOf("Someone painted purple windows", "Bright lanterns cover mountains", "Quiet rivers carry silver boats", "Several foxes crossed snowy fields")
    private fun cues(shift: Double = 0.0) = phrases.mapIndexed { i, text -> SubtitleCue(100.0 + i * 6 + shift, 104.0 + i * 6 + shift, text) }
    private fun heard(offsets: List<Double> = listOf(.1, -.1, .1, 0.0)) = phrases.flatMapIndexed { i, phrase ->
        phrase.split(" ").mapIndexed { j, word -> HeardWord(word, 100.0 + i * 6 + j * .5 + offsets[i], 100.4 + i * 6 + j * .5 + offsets[i]) }
    }
    @Test fun identifiesBothSignsWithoutBeingGivenAnExpectedOffset() {
        for (shift in listOf(7.25, -9.5, 31.0)) {
            val result = requireNotNull(DialogueWordAlignment.match(cues(shift), heard()))
            assertEquals(-shift, result.offset, .2)
            assertEquals(1.0, result.rate, 0.0)
            assertEquals(1.0, result.confidence, 0.0)
        }
    }
    @Test fun laterIndependentPhraseMustAgree() {
        assertNull(DialogueWordAlignment.match(cues(8.0).take(3) + SubtitleCue(140.0, 144.0, "Other words are unrelated"), heard(listOf(.1, -.1, 1.5, 3.0))))
    }
    @Test fun completeHeldOutPhraseCountsTowardTheMatchedDialogueSpan() {
        val texts = listOf("Someone painted purple windows beside gardens", "Bright lanterns cover mountains above quiet rivers", "Several foxes crossed snowy fields beside silver boats")
        val starts = listOf(100.0, 101.86, 105.52)
        val offsets = listOf(.52, .21, -.05)
        val originals = texts.mapIndexed { i, text -> SubtitleCue(starts[i], starts[i] + 4, text) } + SubtitleCue(140.0,144.0,"Other words are unrelated")
        fun observed(lastOffset: Double = offsets.last()) = texts.flatMapIndexed { i, text ->
            text.split(" ").mapIndexed { j, word ->
                val offset = if (i == 2) lastOffset else offsets[i]
                HeardWord(word,starts[i]+offset+j*.5,starts[i]+offset+j*.5+.4)
            }
        }
        val result = requireNotNull(DialogueWordAlignment.match(originals, observed()))
        assertEquals(.365,result.offset,.001)
        assertNull(DialogueWordAlignment.match(originals, observed(1.2)))
        // Unmatched trailing words cannot extend three insufficient phrases.
        val shortened = observed().filterIndexed { index, _ -> index < 16 }
        assertNull(DialogueWordAlignment.match(originals, shortened + HeardWord("Unrelated",120.0,120.4)))
    }
    @Test fun pairedTrainingPhrasesUseTheMidpointAndAnIndependentLaterPhrase() {
        val actual = cues().take(3) + SubtitleCue(140.0, 144.0, "Other words are unrelated")
        val result = requireNotNull(DialogueWordAlignment.match(actual, heard(listOf(-.13, .72, .23, 0.0))))
        assertEquals(.295, result.offset, .001)
    }
    @Test fun severalShortLeadingWordsAreNotMistakenForSilencePadding() {
        val originals = cues().map { it.copy(text = "And this " + it.text) }
        val observed = heard().flatMapIndexed { index, word ->
            if (index % 4 == 0) listOf(HeardWord("And", word.start, word.start + .2),
                HeardWord("this", word.start + .2, word.start + .4), word.copy(start = word.start + .8, end = word.end + .8))
            else listOf(word.copy(start = word.start + .8, end = word.end + .8))
        }
        val result = requireNotNull(DialogueWordAlignment.match(originals, observed))
        assertEquals(0.0, result.offset, .2)
    }
    @Test fun paddedFirstWordsUseCompletedDistinctPhraseEndings() {
        val originals = cues().take(3).mapIndexed { i, cue -> cue.copy(endSeconds = 100.4 + i * 6 + (phrases[i].split(" ").size - 1) * .5) } + SubtitleCue(140.0, 144.0, "Other words are unrelated")
        val observed = heard().map { word -> if (word.text in phrases.map { it.substringBefore(" ") }) word.copy(start = word.start - 2) else word }
        val result = requireNotNull(DialogueWordAlignment.match(originals.map { it.copy(startSeconds = it.startSeconds + 8, endSeconds = it.endSeconds + 8) }, observed))
        assertEquals(-8.0, result.offset, .2)
    }
    @Test fun aMissingPrefixCanVerifyButCannotSupplyTrainingAlone() {
        val texts = listOf("Someone painted purple windows", "Bright lanterns cover mountains", "I thought quiet rivers carry silver boats past gardens")
        val originals = texts.mapIndexed { i, text -> SubtitleCue(100.0+i*6, 101.9+i*6, text) } + SubtitleCue(140.0,144.0,"Other words are unrelated")
        val observed = heard().take(8) + texts.last().split(" ").drop(2).mapIndexed { i, word -> HeardWord(word, 112.0+i*.2,112.3+i*.2) }
        assertNotNull(DialogueWordAlignment.match(originals, observed))
        assertNull(DialogueWordAlignment.match(originals.map { it.copy(text = "I thought " + it.text) }, observed))
    }
    @Test fun mergedSpeakerCuesJoinedWordsAndPluralTranscriptionStillRequireThreePhrases() {
        val originals = listOf(
            SubtitleCue(100.0,102.4,"Bright lanterns cover mountains."),
            SubtitleCue(106.0,109.4,"Her full name, where she lived.\nTheyjust knew the city."),
            SubtitleCue(112.0,114.4,"I have answered your questions."),
            SubtitleCue(140.0,144.0,"Other words are unrelated")
        )
        val texts = listOf("Bright lanterns cover mountain", "Her full name where she lived they just knew the city", "I have answered your question")
        val observed = texts.flatMapIndexed { i,text -> text.split(" ").mapIndexed { j,word ->
            val step = if (i == 1) .3 else .5
            HeardWord(word,100.0+i*6+j*step,100.4+i*6+j*step)
        } }
        val result = requireNotNull(DialogueWordAlignment.match(originals.map { it.copy(startSeconds=it.startSeconds+14.5,endSeconds=it.endSeconds+14.5) },observed))
        assertEquals(-14.5,result.offset,.2)
        assertNull(DialogueWordAlignment.match(originals,observed.take(8)))
    }
    @Test fun rejectsRepeatedAmbiguousPatternsAndWrongCaptions() {
        assertNull(DialogueWordAlignment.match(cues() + cues(10.0), heard()))
        assertNull(DialogueWordAlignment.match(cues().map { it.copy(text = "Different words with no match") }, heard()))
        assertNull(DialogueWordAlignment.match(cues(), emptyList()))
        assertNull(DialogueWordAlignment.match(cues(), heard().take(8)))
    }
    @Test fun currentConstantOffsetCannotHideProgressiveDrift() {
        assertNull(DialogueWordAlignment.match(cues(), heard(listOf(0.0, .3, .6, .9))))
    }
    @Test fun independentlyHeardScenesResolveSupportedCaptionFrameratesWithoutTheExpectedAnswer() {
        val starts = listOf(100.0, 106.0, 3_220.0, 3_226.0)
        for (rate in listOf(25.0/24.0, 24.0/25.0)) {
            val target = phrases.mapIndexed { i, text -> SubtitleCue((starts[i] - 9.3)/rate, (starts[i] + 3 - 9.3)/rate, text) }
            val actual = phrases.flatMapIndexed { i, text -> text.split(" ").mapIndexed { j, word ->
                HeardWord(word,starts[i] + j*.5,starts[i] + j*.5+.4)
            } }
            val result = requireNotNull(DialogueWordAlignment.match(target, actual, recentAfter=3_210.0))
            assertEquals(rate,result.rate,.00001)
            assertEquals(9.3,result.offset,.01)
            assertNull(DialogueWordAlignment.match(target,actual,recentAfter=3_300.0))
        }
    }
    @Test fun independentPhrasesCannotBeFabricatedByJoiningWordsAcrossASeekGap() {
        val observed = heard().mapIndexed { i, word -> if (i % 4 >= 2) word.copy(start=word.start+45,end=word.end+45) else word }
        assertNull(DialogueWordAlignment.match(cues(),observed.sortedBy { it.start }))
    }
    @Test fun rejectsInvalidAudioTimesAndHonoursCancellation() {
        assertNull(DialogueWordAlignment.match(cues(), heard().map { it.copy(start = Double.NaN) }))
        var calls = 0
        try { DialogueWordAlignment.match(cues(), heard()) { calls++; throw java.util.concurrent.CancellationException() }; fail() }
        catch (_: java.util.concurrent.CancellationException) { assertEquals(1, calls) }
    }
}
