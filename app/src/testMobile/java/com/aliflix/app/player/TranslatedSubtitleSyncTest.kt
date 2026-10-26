package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class TranslatedSubtitleSyncTest {
    @Test fun onlineTranslatedBoundariesNeedIndependentResponsesAndUseTheirMeasuredCenter() {
        val texts=listOf("Quiet purple lanterns shine above snowy mountains", "Several silver boats crossed bright rivers beside gardens")
        val source=texts.mapIndexed { i,t -> SubtitleCue(100.0+i*7,103.1+i*7,t) } +
            listOf(SubtitleCue(300.0,304.0,"Other unrelated words"),SubtitleCue(310.0,314.0,"More unrelated sentences"))
        val target=source.map { it.copy(text="العبارة الأصلية") }
        val reference=SubtitleSyncReference(source,AudioSubtitleCorrection(0.0,1.0,.95),target)
        fun heard(shift: Double)=texts.flatMapIndexed { i,t -> t.split(" ").mapIndexed { j,w ->
            HeardWord(w,100.0+i*7+j*.4+shift,100.3+i*7+j*.4+shift,measuredEnd=true)
        } }
        val one=heard(7.25);val two=heard(7.4)
        assertNull(TranslatedSubtitleSync.match(reference,one,one,emptyList(),120.0,independentClips=listOf(one)))
        val result=requireNotNull(TranslatedSubtitleSync.match(reference,one,one,emptyList(),120.0,independentClips=listOf(one,two)))
        assertEquals(7.325,result.offset,.001)
    }
    @Test fun widelyVerifiedExactCaptionEditionsRetainTheirFrameRateAfterCurrentSpeechVerification() {
        val names=listOf("one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve")
        val source=names.mapIndexed { i,name -> SubtitleCue(100.0+i*24,104.0+i*24, when(i) {
            0 -> "Quiet purple lanterns shine above snowy mountains"
            1 -> "Several silver boats crossed bright rivers beside gardens"
            else -> "Independent numbered $name sentence describes distant hidden valleys"
        }) }
        val rate=25.0/24
        val target=source.map { it.copy(startSeconds=(it.startSeconds-2)/rate,endSeconds=(it.endSeconds-2)/rate) }
        val reference=requireNotNull(TranslatedSubtitleSync.prepare(target,source))
        assertTrue(reference.verifiedEditionClock)
        val heard=source.take(3).map { cue -> cue.text.split(" ").mapIndexed { j,word ->
            HeardWord(word,cue.startSeconds+j*.5,cue.startSeconds+j*.5+.3,measuredEnd=true)
        } }
        val current=heard.take(2).flatten()
        // Complete caption correspondence alone cannot choose the audio edition rate.
        assertNull(TranslatedSubtitleSync.match(reference,current,current,emptyList(),130.0))
        val result=requireNotNull(TranslatedSubtitleSync.match(reference,current,current,emptyList(),154.0,independentClips=heard))
        assertEquals(rate,result.rate,.00001)
        assertEquals(2.0,result.offset,.001)
        assertNull(TranslatedSubtitleSync.match(reference.copy(verifiedEditionClock=false),current,current,emptyList(),130.0))
    }
    @Test fun threeTranslatedBoundariesUseTheAlreadyVerifiedExchangeRatherThanAnEightSecondCutoff() {
        val source = listOf(SubtitleCue(100.0,103.0,"Independent first phrase"), SubtitleCue(103.5,108.0,"Independent later phrase"))
        val target = listOf(SubtitleCue(100.3,102.0,"العبارة الأولى"), SubtitleCue(103.6,105.0,"العبارة الثانية"),
            SubtitleCue(105.1,108.2,"العبارة الثالثة"))
        val reference = SubtitleSyncReference(source,AudioSubtitleCorrection(0.0,1.0,.9),target)
        val observed = listOf(VerifiedDialogueBoundary(0,100.0,107.3,false,107.3,6),
            VerifiedDialogueBoundary(1,103.5,110.6,false,110.6,8), VerifiedDialogueBoundary(1,108.0,115.27,true,110.6,8))
        val result = requireNotNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(7.0,1.0,.9)))
        assertEquals(7.035,result.offset,.001)
        assertNull(refineTranslatedBoundaries(reference,observed.map { it.copy(distinctiveWords=0) },result))
        assertNull(refineTranslatedBoundaries(reference,observed.dropLast(1)+observed.last().copy(audioTime=116.3),result))
    }
    @Test fun splitTranslationsUseOnlyUniqueMeasuredClauseEndingsInsideVerifiedPhrases() {
        val source = listOf(SubtitleCue(100.0,103.0,"Independent first phrase"), SubtitleCue(104.0,108.0,"Independent later phrase"))
        val target = listOf(SubtitleCue(100.3,101.8,"العبارة الأولى"), SubtitleCue(104.1,105.4,"العبارة الثانية"),
            SubtitleCue(105.5,108.6,"العبارة الثالثة"))
        val reference = SubtitleSyncReference(source,AudioSubtitleCorrection(0.0,1.0,.9),target)
        val observed = listOf(VerifiedDialogueBoundary(0,103.0,110.1,true,107.3,6),
            VerifiedDialogueBoundary(1,108.0,115.3,true,111.1,8))
        val words = listOf(HeardWord("complete.",111.8,112.2,measuredEnd=true),
            HeardWord("later",112.2,112.8,startReliable=false,measuredEnd=true))
        val result = requireNotNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(7.1,1.0,.9),heard=words))
        assertEquals(6.75,result.offset,.001)
        assertNull(refineTranslatedBoundaries(reference,observed,result,heard=words.map { it.copy(measuredEnd=false) }))
        assertNull(refineTranslatedBoundaries(reference,observed.drop(1),result,heard=words))
        val ambiguous = words+HeardWord("competing.",112.3,112.6,measuredEnd=true)
        assertNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(7.1,1.0,.9),heard=ambiguous))
    }
    @Test fun verifiedTranslatedSentencesCanUseTwoUniqueBoundariesDespiteDifferentHolds() {
        val source = listOf(SubtitleCue(100.0,103.0,"Independent first phrase"), SubtitleCue(104.0,108.0,"Independent later phrase"))
        val target = listOf(SubtitleCue(100.3,101.8,"العبارة الأولى"), SubtitleCue(104.1,105.4,"العبارة الثانية"),
            SubtitleCue(105.5,108.6,"العبارة الثالثة"))
        val reference = SubtitleSyncReference(source,AudioSubtitleCorrection(0.0,1.0,.9),target)
        val observed = listOf(VerifiedDialogueBoundary(0,103.0,110.1,true,107.3,6),
            VerifiedDialogueBoundary(1,104.0,111.1,false,111.1,8), VerifiedDialogueBoundary(1,108.0,114.97,true,111.1,8))
        val result = requireNotNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(7.1,1.0,.9)))
        assertEquals(6.685,result.offset,.001)
        assertNull(refineTranslatedBoundaries(reference,observed.drop(1),result))
        assertNull(refineTranslatedBoundaries(reference,observed.dropLast(1)+observed.last().copy(audioTime=116.9),result))
    }
    @Test fun aTranslationWithShorterHoldsAndSplitSentencesUsesItsOwnMeasuredBoundaries() {
        val texts = listOf("I thought bright purple lanterns cover mountains beside gardens",
            "Several silver boats crossed quiet rivers above snowy fields")
        val source = listOf(SubtitleCue(100.0,103.3,texts[0]), SubtitleCue(105.0,109.3,texts[1]),
            SubtitleCue(300.0,304.0,"Other unrelated words"), SubtitleCue(310.0,314.0,"More unrelated words"))
        val target = listOf(SubtitleCue(100.3,102.3,"العبارة الأولى"), SubtitleCue(105.1,106.8,"العبارة الثانية"),
            SubtitleCue(106.9,109.2,"العبارة الثالثة"))
        val heard = texts.flatMapIndexed { i, text -> text.split(" ").mapIndexed { j, word ->
            val start = 100.0 + i * 5 + j * if (i == 0) .4 else .5
            HeardWord(word,start,start+.1,measuredEnd=true)
        } }
        val reference = SubtitleSyncReference(source,AudioSubtitleCorrection(0.0,1.0,.9),target)
        val result = requireNotNull(TranslatedSubtitleSync.match(reference,heard,heard,emptyList(),115.0))
        assertEquals(-.2,result.offset,.001)
        assertNull(TranslatedSubtitleSync.match(reference,heard.map {
            if (it.start >= 105) it.copy(start=it.start+1.4,end=it.end+1.4) else it
        },emptyList(),emptyList(),115.0))
    }
    @Test fun twoSubstantialVerifiedSentencesCanIndependentlyCheckTranslatedBoundaries() {
        val target = listOf(SubtitleCue(100.0,103.0,"العبارة الأولى"), SubtitleCue(103.5,108.0,"العبارة الثانية"))
        val source = target.map { it.copy(text="Independent spoken reference") }
        val reference = SubtitleSyncReference(source,AudioSubtitleCorrection(0.0,1.0,.9),target)
        val observed = listOf(VerifiedDialogueBoundary(0,103.0,110.2,true,107.8,6),
            VerifiedDialogueBoundary(1,108.0,115.35,true,110.7,8))
        val result = requireNotNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(7.25,1.0,.9)))
        assertEquals(7.275,result.offset,.001)
        assertNull(refineTranslatedBoundaries(reference,observed.map { it.copy(distinctiveWords=0) },result))
        assertNull(refineTranslatedBoundaries(reference,observed.dropLast(1)+observed.last().copy(audioTime=116.5),result))
        assertNull(refineTranslatedBoundaries(reference,observed,result.copy(rate=1.04)))
        assertNull(refineTranslatedBoundaries(reference.copy(targetCues=target+target.first().copy(endSeconds=103.1)),observed,result))
    }
    @Test fun shortVerifiedConversationCannotInheritAnUnverifiedMovieWideFramerate() {
        val texts=listOf("Quiet purple lanterns shine above snowy mountains", "Several silver boats crossed bright rivers beside gardens")
        val cues=texts.mapIndexed { i,text -> SubtitleCue(100.0+i*7,104.0+i*7,text) } +
            listOf(SubtitleCue(400.0,404.0,"Unrelated distant dialogue"),SubtitleCue(410.0,414.0,"Other unmatched sentences"))
        val heard=texts.flatMapIndexed { i,text -> text.split(" ").mapIndexed { j,word ->
            HeardWord(word,100.0+i*7+j*.4,100.3+i*7+j*.4)
        } }
        val reference=SubtitleSyncReference(cues,AudioSubtitleCorrection(0.0,1.04,.95),exactTextClock=true)
        assertNull(TranslatedSubtitleSync.match(reference,heard,heard,emptyList(),112.0))
        assertNotNull(TranslatedSubtitleSync.match(reference.copy(targetToReference=AudioSubtitleCorrection(0.0,1.0,.95)),
            heard,heard,emptyList(),112.0))
    }
    @Test fun independentClocksComposeWithoutMovingReferenceRegionIndexes() {
        val target = AudioSubtitleCorrection(-7.0, 24.0 / 25, .92, listOf(TimingRegion(20, -5.0)))
        val reference = AudioSubtitleCorrection(2.0, 25.0 / 24, .89)
        val composed = requireNotNull(composeSubtitleClocks(target, reference))
        assertEquals(1.0, composed.rate, 1e-9)
        assertEquals(-7 * 25.0 / 24 + 2, composed.offset, 1e-9)
        assertEquals(20, composed.regions.single().firstCue)
        assertEquals(-5 * 25.0 / 24 + 2, composed.regions.single().offset, 1e-9)
        assertNull(composeSubtitleClocks(target, reference.copy(regions = listOf(TimingRegion(7, 2.0)))))
    }
    @Test fun translatedTimingRequiresAnIndependentReferenceAndCannotCertifyUnheardSpeech() {
        val random = Random(435)
        var time = 0.0
        val reference = List(120) {
            time += .8 + random.nextDouble() * 4
            val start = time
            time += .8 + random.nextDouble() * 3
            SubtitleCue(start, time, "Distinct dialogue sentence")
        }
        val translated = reference.map { it.copy(startSeconds = it.startSeconds + 7.25, endSeconds = it.endSeconds + 7.25, text = "حوار باللغة العربية") }
        val prepared = requireNotNull(TranslatedSubtitleSync.prepare(translated, reference))
        assertEquals(-7.25, prepared.targetToReference.offset, .12)
        assertFalse(prepared.exactTextClock)
        assertNull(TranslatedSubtitleSync.match(prepared, emptyList(), emptyList(), emptyList(), 100.0))
        val repeated = List(160) { SubtitleCue(it * 3.0, it * 3.0 + 1, "Repeated dialogue") }
        assertNull(TranslatedSubtitleSync.prepare(repeated, reference))
    }
    @Test fun translatedBoundariesFitEarlierPhrasesAndIndependentlyCheckTheLatest() {
        val target = listOf(SubtitleCue(100.0,103.0,"العبارة الأولى"), SubtitleCue(106.0,109.0,"العبارة الثانية"),
            SubtitleCue(114.0,117.0,"العبارة الثالثة"))
        val source = target.mapIndexed { i, cue -> cue.copy(endSeconds=cue.endSeconds+listOf(.18,.46,.34)[i],text="Independent spoken reference") }
        val reference = SubtitleSyncReference(source, AudioSubtitleCorrection(-.07,1.0,.9,model="verified_caption_gaps"),target)
        val observed = source.mapIndexed { i,cue -> VerifiedDialogueBoundary(i,cue.endSeconds,
            target[i].endSeconds+listOf(6.2,6.35,6.85)[i],true) }
        val result = requireNotNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(6.15,1.0,.9)))
        assertEquals(6.525,result.offset,1e-9)
        assertTrue(observed.indices.all { kotlin.math.abs(target[it].endSeconds+result.offset-observed[it].audioTime) < .55 })
        assertNull(refineTranslatedBoundaries(reference,observed.dropLast(1)+observed.last().copy(audioTime=124.5),result))
        assertNull(refineTranslatedBoundaries(reference,observed.take(2),result))
    }
    @Test fun ambiguousTranslatedBoundaryCannotSupplyTimingEvidence() {
        val target = listOf(SubtitleCue(100.0,103.0,"أ"),SubtitleCue(106.0,109.0,"ب"),SubtitleCue(114.0,117.0,"ج"))
        val reference = SubtitleSyncReference(target,AudioSubtitleCorrection(0.0,1.0,.9,model="verified_caption_gaps"),
            target+SubtitleCue(100.0,103.1,"Alternative overlapping cue"))
        val observed = target.mapIndexed { i,cue -> VerifiedDialogueBoundary(i,cue.endSeconds,cue.endSeconds+5,true) }
        assertNull(refineTranslatedBoundaries(reference,observed,AudioSubtitleCorrection(5.0,1.0,.9)))
    }
}
