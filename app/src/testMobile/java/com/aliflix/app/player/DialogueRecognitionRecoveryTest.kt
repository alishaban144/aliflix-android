package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class DialogueRecognitionRecoveryTest {
    @Test fun unlabeledEnglishPlaybackCanStartRecognitionWithoutRelabelingOtherAudio() {
        assertEquals("en-US", dialogueRecognitionLanguage("", "EN"))
        assertEquals("en-US", dialogueRecognitionLanguage("und", "eng"))
        assertEquals("en-US", dialogueRecognitionLanguage("en-GB", "ar"))
        assertEquals("ja-JP", dialogueRecognitionLanguage("ja", "en"))
        assertEquals("fr-FR", dialogueRecognitionLanguage("fr", "en"))
        assertNull(dialogueRecognitionLanguage("", "ar"))
        assertEquals("en-US", dialogueRecognitionLanguage("und", "ar", "en"))
        assertEquals("fr-FR", dialogueRecognitionLanguage("fr", "ar", "en"))
    }
    @Test fun serverDisconnectionAndBusySessionsRecoverInsteadOfDisablingTheFilm() {
        for (code in listOf(11, 8, -1, -2)) {
            assertTrue(dialogueRecognitionCanRetry(code))
            assertTrue(dialogueRecognitionRetryDelay(code, 1) in 500..4_000)
            assertEquals(4_000, dialogueRecognitionRetryDelay(code, 100).toInt())
        }
    }
    @Test fun missingPermissionOrLanguageCannotBecomeAnEndlessRetry() {
        for (code in listOf(9, 12, 13, 14, 15)) assertFalse(dialogueRecognitionCanRetry(code))
    }
    @Test fun silenceWaitsForVoicedAudioWithoutAnArtificialDelayAndThrottlingBacksOff() {
        assertEquals(0L, dialogueRecognitionRetryDelay(6, 1))
        assertEquals(0L, dialogueRecognitionRetryDelay(7, 1))
        assertEquals(30_000L, dialogueRecognitionRetryDelay(10, 1))
    }
}
