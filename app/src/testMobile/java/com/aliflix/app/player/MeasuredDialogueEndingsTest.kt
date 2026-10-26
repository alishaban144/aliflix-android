package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class MeasuredDialogueEndingsTest {
    private fun window(start:Double=10.0)=SpeechWindow(start,DoubleArray(100) { if(it<20) 1.0 else 0.0 })
    @Test fun terminalWordsUseOnlyObservedSilenceAfterTheActualPhraseInAnyLanguage() {
        for(text in listOf("hello","hallo","hola","مرحبا")) {
            val word=HeardWord(text,10.0,10.0,false,12.0)
            val result=measuredDialogueEndings(listOf(word),listOf(window())).single()
            assertEquals(10.4,result.end,.00001);assertTrue(result.measuredEnd);assertTrue(result.endReliable)
            assertEquals(12.0,result.playedThrough,0.0)
        }
    }
    @Test fun unfinishedSpeechUnknownSamplesAndDecoderGapsCannotManufactureAnEnding() {
        val word=HeardWord("hallo",10.0,10.0,false,12.0)
        for(windows in listOf(emptyList(),listOf(SpeechWindow(10.0,DoubleArray(100){1.0})),
            listOf(SpeechWindow(10.0,DoubleArray(30){if(it<20)1.0 else 0.0}),SpeechWindow(11.0,DoubleArray(50))),
            listOf(window().let { it.copy(speech=it.speech.copyOf().apply { this[25]=Double.NaN }) }))) {
            assertEquals(word,measuredDialogueEndings(listOf(word),windows).single())
        }
    }
    @Test fun anotherWordOrUnreliableOnsetCannotSupplyThisWordsEnding() {
        val word=HeardWord("hallo",10.0,10.3,true,10.3)
        val next=HeardWord("welt",10.3,10.3,false,12.0)
        assertEquals(word,measuredDialogueEndings(listOf(word,next),listOf(window())).first())
        val unknown=word.copy(startReliable=false)
        assertEquals(unknown,measuredDialogueEndings(listOf(unknown),listOf(window())).single())
    }
    @Test fun existingMeasuredTimesRemainExactAndEvidenceDoesNotExposeFutureWords() {
        val word=HeardWord("hello",10.0,10.37,true,12.0,true)
        assertEquals(word,measuredDialogueEndings(listOf(word),listOf(window())).single())
        assertTrue(playedRecognitionWords(measuredDialogueEndings(listOf(word.copy(measuredEnd=false)),listOf(window())),11.0).isEmpty())
    }
}
