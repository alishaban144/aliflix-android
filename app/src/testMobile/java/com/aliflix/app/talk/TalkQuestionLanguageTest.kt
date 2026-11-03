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
        assertEquals("en", detectTalkQuestionLanguage("What happened in Dark?"))
        assertEquals("en", detectTalkQuestionLanguage("Who is the hooded woman?"))
        assertEquals("de", detectTalkQuestionLanguage("Wer ist die Frau mit der Kapuze?"))
        assertEquals("ar", detectTalkQuestionLanguage("من هي المرأة التي ترتدي القبعة؟"))
        assertNull(detectTalkQuestionLanguage(""))
    }
}
