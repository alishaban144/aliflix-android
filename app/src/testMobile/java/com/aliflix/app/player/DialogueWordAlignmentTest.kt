package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class DialogueWordAlignmentTest {
    @Test fun compactExchangeNeedsTwoIndependentMeasuredResponsesForBothPhrases() {
        val texts = listOf("Quiet purple lanterns cover snowy mountains", "Several silver boats cross bright rivers")
        val source = texts.mapIndexed { i, text -> SubtitleCue(100.0+i*2.6,102.1+i*2.6,text) } +
            listOf(SubtitleCue(300.0,304.0,"Other unrelated dialogue"),SubtitleCue(310.0,314.0,"Different unmatched words"))
        val one = texts.flatMapIndexed { i,text -> text.split(" ").mapIndexed { j,word ->
            HeardWord(word,100.0+i*2.6+j*.4,100.1+i*2.6+j*.4,measuredEnd=true)
        } }
        val two = one.map { it.copy(start=it.start+.1,end=it.end+.1) }
        assertNotNull(DialogueWordAlignment.matchClips(source,listOf(one,two)))
        assertNull(DialogueWordAlignment.matchClips(source,listOf(one)))
        assertNull(DialogueWordAlignment.matchClips(source,listOf(one,two.take(6))))
        assertNull(DialogueWordAlignment.matchClips(source,listOf(one,two.map { it.copy(start=it.start+1,end=it.end+1) })))
    }
    @Test fun repeatedMatchesWithinOneResponseCannotOutvoteIndependentMeasuredBoundaries() {
        val targets = cues(7.25)
        val one = heard(listOf(.1,.1,.1,.1)).map { it.copy(measuredEnd=true) }
        val two = heard(listOf(.2,.2,.2,.2)).map { it.copy(measuredEnd=true) }
        val bad = heard(listOf(-1.0,-1.0,-1.0,-1.0)).map { it.copy(measuredEnd=true) }
        var boundaries = emptyList<VerifiedDialogueBoundary>()
        val result = requireNotNull(DialogueWordAlignment.matchClips(targets,listOf(one,two,bad+bad),boundaries={boundaries=it}))
        assertEquals(-7.1,result.offset,.11)
        assertEquals(100.15,boundaries.first { it.cue==0 && !it.ending }.audioTime,.001)
        // Two conflicting independently repeated clocks must remain ambiguous.
        assertNull(DialogueWordAlignment.matchClips(targets,listOf(one,two,bad,bad)))
    }
    @Test fun omittedFunctionWordsOnlyCorroborateTwoRichPhrasesAndNeverTrainTheirClock() {
        val texts = listOf("Hey you got a phone it's in the back", "Quiet purple lanterns shine above snowy mountains",
            "Several silver boats crossed bright rivers beside gardens", "Other unrelated distant words")
        val starts = listOf(100.0, 155.0, 161.0, 300.0)
        val source = texts.mapIndexed { i,t -> SubtitleCue(starts[i],starts[i]+if(i==0)2.25 else 3.5,t) }
        val strong = texts.slice(1..2).flatMapIndexed { i,t -> t.split(" ").mapIndexed { j,w ->
            HeardWord(w,155.0+i*6+j*.4,155.3+i*6+j*.4,measuredEnd=true)
        } }
        fun weak(extra: Double = 0.0, final: String = "back.", measured: Boolean = true) =
            listOf("you","got","a","phone?","Give","it",final).mapIndexed { i,w ->
                HeardWord(w,100.2+i*.3+extra,100.5+i*.3+extra,measuredEnd=measured)
            }
        val result = requireNotNull(DialogueWordAlignment.matchClips(source,listOf(weak(),strong),requireWideClock=true,candidateRates=listOf(1.0,25.0/23.976)))
        assertEquals(0.0,result.offset,.001)
        assertEquals(1.0,result.rate,0.0)
        assertNull(DialogueWordAlignment.matchClips(source,listOf(weak(2.0),strong),requireWideClock=true,candidateRates=listOf(1.0)))
        assertNull(DialogueWordAlignment.matchClips(source,listOf(weak(final="bag."),strong),requireWideClock=true,candidateRates=listOf(1.0)))
        assertNull(DialogueWordAlignment.matchClips(source,listOf(weak(measured=false),strong),requireWideClock=true,candidateRates=listOf(1.0)))
        assertNull(DialogueWordAlignment.matchClips(source,listOf(weak(),strong.take(8)),requireWideClock=true,candidateRates=listOf(1.0)))
    }
    @Test fun independentClipsProveBothEditionRatesWithoutSplicingWords() {
        val texts = listOf("Quiet purple lanterns shine above snowy mountains", "Several silver boats crossed bright rivers beside gardens",
            "Someone painted golden windows around empty houses", "Bright sailing vessels carried fresh apples past islands")
        val target = texts.mapIndexed { i, text -> SubtitleCue(100.0 + i * 24, 103.1 + i * 24, text) }
        for (rate in listOf(1.0, 25.0 / 23.976, 23.976 / 25.0)) {
            val words = texts.mapIndexed { i, text -> text.split(" ").mapIndexed { j, word ->
                val start = (100.0 + i * 24 + j * .4) * rate - 7.25
                HeardWord(word, start, start + .3 * rate, measuredEnd = true)
            } }
            val result = requireNotNull(DialogueWordAlignment.matchClips(target, listOf(words.take(2).flatten(), words.drop(2).flatten()), requireWideClock = true,
                candidateRates = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0)))
            assertEquals(rate, result.rate, .00001)
            assertEquals(-7.25, result.offset, .001)
            assertNull(DialogueWordAlignment.matchClips(target, listOf(words.take(2).flatten()), requireWideClock = true))
            // A clipped sentence split over separate responses cannot become a phrase.
            assertNull(DialogueWordAlignment.matchClips(target, words.flatMap { listOf(it.take(4), it.drop(4)) }, requireWideClock = true))
        }
    }
    @Test fun clippedLeadingWordsRetainAMeasuredEndingAndIndependentLaterVerification() {
        val texts = listOf("I thought bright purple lanterns cover mountains beside gardens",
            "Several silver boats crossed quiet rivers above snowy fields")
        val originals = texts.mapIndexed { i, text -> SubtitleCue(100.0 + i * 4 + 7.25, 103.3 + i * 4 + 7.25, text) } +
            listOf(SubtitleCue(300.0, 304.0, "Other unrelated words"), SubtitleCue(310.0, 314.0, "More unrelated words"))
        fun observed(later: Double = 0.0, measured: Boolean = true) = texts.flatMapIndexed { i, text ->
            text.split(" ").mapIndexedNotNull { j, word ->
                if (i == 0 && j < 2) null else {
                    val start = 100.0 + i * 4 + j * .4 + if (i == 1) later else 0.0
                    HeardWord(word, start, start + .1, measuredEnd = measured)
                }
            }
        }
        val correction = requireNotNull(DialogueWordAlignment.match(originals, observed()))
        assertEquals(-7.25, correction.offset, .001)
        assertNull(DialogueWordAlignment.match(originals, observed(1.4)))
        assertNull(DialogueWordAlignment.match(originals, observed(measured = false)))
        assertNull(DialogueWordAlignment.match(originals + originals.take(2).map {
            it.copy(startSeconds = it.startSeconds + 100, endSeconds = it.endSeconds + 100)
        }, observed()))
    }
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
    @Test fun twoLongDistinctivePhrasesFitThenIndependentlyVerifyInsteadOfRequiringThreeCaptionRows() {
        val texts=listOf("Quiet purple lanterns shine above snowy mountains", "Several silver boats crossed bright rivers beside gardens")
        val target=texts.mapIndexed { i,text -> SubtitleCue(100.0+i*7+7.25,104.0+i*7+7.25,text) }
        // Retain other unrelated cues as in a complete caption file.
        val full=target+cues().map { it.copy(startSeconds=it.startSeconds+300,endSeconds=it.endSeconds+300) }
        fun observed(later: Double)=texts.flatMapIndexed { i,text -> text.split(" ").mapIndexed { j,word ->
            HeardWord(word,100.0+i*7+j*.4+if(i==1)later else 0.0,100.3+i*7+j*.4+if(i==1)later else 0.0)
        } }
        val correction=requireNotNull(DialogueWordAlignment.match(full,observed(.1)))
        assertEquals(-7.2,correction.offset,.01)
        assertNull(DialogueWordAlignment.match(full,observed(1.4)))
        val repeated=full+target.map { it.copy(startSeconds=it.startSeconds+100,endSeconds=it.endSeconds+100) }
        assertNull(DialogueWordAlignment.match(repeated,observed(.1)))
    }
    @Test fun laterIndependentPhraseMustAgree() {
        assertNull(DialogueWordAlignment.match(cues(8.0).take(3) + SubtitleCue(140.0, 144.0, "Other words are unrelated"), heard(listOf(.1, -.1, 1.5, 3.0))))
    }
    @Test fun twoRichCompletedPhrasesNeedNoArtificialEightSecondThresholdOrUncertainOnsets() {
        val texts=listOf("Quiet purple lanterns shine above snowy mountains", "Several silver boats crossed bright rivers beside gardens")
        val starts=listOf(100.0,104.7)
        val observed=texts.flatMapIndexed { i,text -> text.split(" ").mapIndexed { j,word ->
            HeardWord(word,starts[i]+j*.4,starts[i]+j*.4+.3,measuredEnd=true,startReliable=j!=0)
        } }
        val targets=texts.mapIndexed { i,text -> SubtitleCue(starts[i]+7.25,starts[i]+text.split(" ").lastIndex*.4+.3+7.25,text) } +
            listOf(SubtitleCue(300.0,304.0,"Other unrelated words"),SubtitleCue(310.0,314.0,"More unrelated words"))
        assertEquals(-7.25,requireNotNull(DialogueWordAlignment.match(targets,observed)).offset,.01)
        assertNull(DialogueWordAlignment.match(targets,observed.take(7)))
        assertNull(DialogueWordAlignment.match(targets,observed.map { if(it.start>=104.7) it.copy(start=it.start+1.5,end=it.end+1.5) else it }))
    }
    @Test fun smallAsrInsertionsAndJoinedSubtitleWordsStillRequireIndependentMeasuredPhrases() {
        val texts = listOf("Quiet purple lanterns shine above snowy mountains", "Ifyou need silver boats sent out to quiet gardens stay beside the river")
        val targets = texts.mapIndexed { i, text -> SubtitleCue(100.0 + i * 9 + 7.25, 105.0 + i * 9 + 7.25, text) } +
            listOf(SubtitleCue(300.0, 304.0, "Other unrelated words"), SubtitleCue(310.0, 314.0, "More unrelated words"))
        val spoken = listOf(texts.first(), "If you need silver boats send out to quiet gardens please just stay beside the river")
        val observed = spoken.flatMapIndexed { i, text -> text.split(" ").mapIndexed { j, word ->
            HeardWord(word, 100.0 + i * 9 + j * .25, 100.2 + i * 9 + j * .25, measuredEnd = true)
        } }
        assertEquals(-7.25, requireNotNull(DialogueWordAlignment.match(targets, observed)).offset, .01)
        assertNull(DialogueWordAlignment.match(targets.map { it.copy(text="Wrong dialogue with unrelated distinctive words") },observed))
        assertNull(DialogueWordAlignment.match(targets,observed.map { if(it.start>=109) it.copy(start=it.start+1.5,end=it.end+1.5) else it }))
    }
    @Test fun measuredLongWordsRemainUsableButEstimatedSilencePaddedBoundariesDoNot() {
        val texts = listOf("Quiet purple lanterns shine above snowy mountains", "Several silver boats crossed bright rivers beside gardens")
        val targets = texts.mapIndexed { i, text -> SubtitleCue(100.0 + i * 8 + 7.25, 105.0 + i * 8 + 7.25, text) } +
            listOf(SubtitleCue(300.0, 304.0, "Other unrelated words"), SubtitleCue(310.0, 314.0, "More unrelated words"))
        val measured = texts.flatMapIndexed { i, text -> text.split(" ").mapIndexed { j, word ->
            HeardWord(word, 100.0 + i * 8 + j * .7, 100.69 + i * 8 + j * .7, measuredEnd = true)
        } }
        assertEquals(-7.25, requireNotNull(DialogueWordAlignment.match(targets, measured)).offset, .01)
        assertNull(DialogueWordAlignment.match(targets, measured.map { it.copy(measuredEnd = false) }))
        assertNull(DialogueWordAlignment.match(targets, measured.map { if (it.start >= 108) it.copy(start = it.start + 1.4, end = it.end + 1.4) else it }))
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
        assertTrue(offsets.all { kotlin.math.abs(it-result.offset) < .3 })
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
    @Test fun floatingPointClockAccumulationDoesNotRejectTheExactVerificationBoundary() {
        val originals = cues().take(3) + SubtitleCue(140.0,144.0,"Other words are unrelated")
        assertNotNull(DialogueWordAlignment.match(originals,heard(listOf(-.52,-.82,-.12,0.0))))
    }
    @Test fun newestClearPhraseCannotBeDiscardedAsAnOutlier() {
        assertNull(DialogueWordAlignment.match(cues(), heard(listOf(0.0, .1, .2, 1.7))))
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
    @Test fun unknownTerminalEndsRetainTheirActualPcmPlaybackFence() {
        val complete = HeardWord("painted", 100.0, 100.4)
        val terminal = HeardWord("windows", 100.5, 100.5, endReliable = false, playedThrough = 102.0)
        val ahead = HeardWord("lanterns", 101.0, 101.4)
        assertEquals(listOf(complete), playedRecognitionWords(listOf(complete, terminal, ahead), 100.8))
        assertEquals(listOf(complete, terminal, ahead), playedRecognitionWords(listOf(complete, terminal, ahead), 102.0))
    }

    @Test fun completedTranscriptTerminalWordsCanVerifyRealOnsetsWithoutInventedEnds() {
        val observed = heard().map { word ->
            if (word.text in phrases.map { it.substringAfterLast(" ") }) word.copy(end = word.start, endReliable = false) else word
        }
        val correction = requireNotNull(DialogueWordAlignment.match(cues(7.25), observed, recentAfter = 115.0))
        assertEquals(-7.25, correction.offset, .2)
        // If starts are silence padded too, neither boundary is measured.
        val unknown = observed.map { word ->
            if (word.text in phrases.map { it.substringBefore(" ") }) word.copy(start = word.start - 2) else word
        }
        assertNull(DialogueWordAlignment.match(cues(7.25), unknown))
    }

    @Test fun rejectsInvalidAudioTimesAndHonoursCancellation() {
        assertNull(DialogueWordAlignment.match(cues(), heard().map { it.copy(start = Double.NaN) }))
        var calls = 0
        try { DialogueWordAlignment.match(cues(), heard()) { calls++; throw java.util.concurrent.CancellationException() }; fail() }
        catch (_: java.util.concurrent.CancellationException) { assertEquals(1, calls) }
    }
    @Test fun clearPhrasesInsideThePlayedWindowRemainUsefulAfterRecognitionSilence() {
        val observed = heard().take(13)
        val position = 140.0
        assertNotNull(DialogueWordAlignment.match(cues(7.25),observed,recentAfter=position-CURRENT_DIALOGUE_SECONDS))
        assertNull(DialogueWordAlignment.match(cues(7.25),observed,recentAfter=position-18))
        assertNull(DialogueWordAlignment.match(cues(7.25),observed,recentAfter=position+31-CURRENT_DIALOGUE_SECONDS))
    }
}
