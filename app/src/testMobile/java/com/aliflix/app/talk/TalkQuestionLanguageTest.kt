package com.aliflix.app.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkQuestionLanguageTest {
    @Test fun newestQuestionLanguageWinsAcrossASeekAndAcrossAnEarlierAnswer() {
        assertEquals("en", chooseQuestionResponseLanguage("de", "What happened at 6:20?"))
        assertEquals("en", chooseQuestionResponseLanguage("de", "What happened in the previous scene?"))
        assertEquals("de", chooseQuestionResponseLanguage("en", "Was ist in der vorherigen Szene passiert?"))
        assertEquals("de", chooseQuestionResponseLanguage("en", "Was ist passiert?"))
        assertEquals("ar", chooseQuestionResponseLanguage("en", "ماذا حدث في المشهد السابق؟"))
        assertEquals("es", chooseQuestionResponseLanguage("en", "¿Qué pasó en la escena anterior?"))
        assertEquals("fr", chooseQuestionResponseLanguage("en", "Qu'est-ce qui s'est passé dans la scène précédente ?"))
        assertEquals("it", chooseQuestionResponseLanguage("en", "Cosa è successo nella scena precedente?"))
        assertEquals("pt", chooseQuestionResponseLanguage("en", "O que aconteceu na cena anterior?"))
        assertEquals("ru", chooseQuestionResponseLanguage("en", "Что произошло в предыдущей сцене?"))
    }

    @Test fun titlesNamesAndBorrowedWordsCannotChangeTheLanguage() {
        assertEquals("en", chooseQuestionResponseLanguage("en", "Dark"))
        assertEquals("en", chooseQuestionResponseLanguage("en", "The Shining"))
        assertEquals("en", chooseQuestionResponseLanguage("en", "Tell me about Das Boot"))
        assertEquals("de", chooseQuestionResponseLanguage("de", "Dark"))
        assertNull(detectTalkQuestionLanguage("Dark"))
        assertNull(detectTalkQuestionLanguage("Blade Runner 2049"))
    }

    @Test fun explicitRequestStillOverridesTheQuestionLanguage() {
        assertEquals("de", chooseQuestionResponseLanguage("en", "What happened? Answer in German."))
        assertEquals("en", chooseQuestionResponseLanguage("de", "Was ist passiert? Please answer in English."))
        assertEquals("ar", chooseQuestionResponseLanguage("en", "What happened? أجب بالعربية الآن"))
    }

    @Test fun questionLanguageIsDetectedFromTheQuestionItself() {
        assertEquals("en", chooseQuestionResponseLanguage("de", "Why did she fell down?"))
        assertEquals("en", chooseQuestionResponseLanguage("ar", "Why did she fall down?"))
        assertEquals("en", chooseQuestionResponseLanguage("de", "How did he know about the letter?"))
        assertEquals("en", detectTalkQuestionLanguage("What happened in Dark?"))
        assertEquals("en", detectTalkQuestionLanguage("Who is the hooded woman?"))
        assertEquals("de", detectTalkQuestionLanguage("Wer ist die Frau mit der Kapuze?"))
        assertEquals("ar", detectTalkQuestionLanguage("من هي المرأة التي ترتدي القبعة؟"))
        assertNull(detectTalkQuestionLanguage(""))
    }

    @org.junit.Test fun shortSpokenFragmentsPreserveTheEstablishedConversationLanguage() {
        org.junit.Assert.assertEquals("en",chooseSpokenResponseLanguage("en","Hola"))
        org.junit.Assert.assertEquals("en",chooseSpokenResponseLanguage("en","مرحبا"))
        org.junit.Assert.assertEquals("en",chooseSpokenResponseLanguage("en","Hi"))
        org.junit.Assert.assertEquals("de",chooseSpokenResponseLanguage("en","Answer in German"))
        org.junit.Assert.assertEquals("de",chooseSpokenResponseLanguage("en","Warum hat sie das getan?"))
        org.junit.Assert.assertFalse(talkActionRequested("new_chat","Hi"))
        org.junit.Assert.assertFalse(talkActionRequested("new_chat","Can you tell me why she left?"))
        org.junit.Assert.assertFalse(talkActionRequested("new_chat","Don't start a new chat"))
        org.junit.Assert.assertTrue(talkActionRequested("new_chat","Start a new conversation"))
    }

    @org.junit.Test fun explicitProviderQuotaMustNotTriggerAnAutomaticReconnectLoop() {
        org.junit.Assert.assertTrue(talkProviderQuotaFailure(1011,"You exceeded your current quota, please check your plan and billing details."))
        org.junit.Assert.assertFalse(talkProviderQuotaFailure(1011,"Internal server error"))
        org.junit.Assert.assertFalse(talkActionRequested("new_chat","What if I start a new chat?"))
        org.junit.Assert.assertFalse(talkNeedsCurrentSceneObservation("How did Neo suddenly learn kung fu?"))
        org.junit.Assert.assertFalse(talkNeedsCurrentSceneObservation("Why are these gunmen after him, and what did he give them instead of what he promised?"))
        org.junit.Assert.assertTrue(talkNeedsCurrentSceneObservation("Why did she fall down?"))
    }
}
