package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkTurnDetectorTest {
    @Test fun establishedLanguageChangesOnlyForAnExplicitRequest() {
        assertEquals("en",chooseTalkResponseLanguage(null,"en",false))
        assertEquals("en",chooseTalkResponseLanguage("en","de",false))
        assertEquals("de",chooseTalkResponseLanguage("en","de",true))
        assertEquals("de",chooseTalkResponseLanguage("de","ar",false))
        assertEquals("en",chooseTalkResponseLanguage("en","not a code",true))
    }
    @Test fun semanticTopicsRejectAcknowledgementsAndPreserveFollowUpContext() {
        assertNull(validatedTalkTopic("Briefly"));assertNull(validatedTalkTopic("One sentence"));assertNull(validatedTalkTopic("Okay"));assertNull(validatedTalkTopic("just just"))
        assertNull(validatedTalkTopic("نعم"));assertNull(validatedTalkTopic("thank you"))
        assertEquals("THE SHINING",validatedTalkTopic("The Shining"))
        assertEquals("THE SHINING",topicWords("Okay, just tell me more","","THE SHINING"))
        assertEquals("TIME LOOP",topicWords("Explain a time loop",""))
        assertEquals("ALIFLIX",topicWords("Okay","just just"))
    }
    @Test fun topicsUpdateFromCompletedSpeechEvenWhenTheVisualToolIsMissing() {
        assertEquals("THE SHINING",topicWords("Explain the ending of the movie The Shining (1980).",""))
        assertEquals("DARK",topicWords("Let's discuss Dark season 2 episode 1.","","THE SHINING"))
        assertEquals("DARK",topicWords("What does that mean?","","DARK"))
        assertEquals("DARK",topicWords("Okay, just just","","DARK"))
        assertEquals("THE SHINING",topicWords("Okay, just tell me more about that atmosphere in one sentence.","","THE SHINING"))
        assertEquals("SCI-FI",topicWords("Can you recommend a science fiction series?","","DARK"))
        assertEquals("INCEPTION",topicWords("Erkläre das Ende von Inception.","","SCI-FI"))
        assertEquals("دارك",topicWords("اشرح نهاية مسلسل دارك","","SCI-FI"))
        assertEquals("FAVORITES",topicWords("What are my favorites?","","ALIFLIX"))
        assertEquals("SUBTITLES",topicWords("How do subtitles work?","","FAVORITES"))
        assertEquals("SUBTITLES",topicWords("How are subtitles sync with audio? audio language and my download quality connected in AllOflex? Explain briefly.","","DARK"))
    }
    @Test fun incompleteLibraryMetadataKeepsTheCatalogueIdentityWithoutInvalidImdbValues() {
        val media=com.aliflix.app.model.Media(70523,com.aliflix.app.model.MediaType.TV,"not sent",imdbId="")
        val missing=talkLibraryIdentity(media,"favorite")
        assertEquals(70523,missing.getInt("id"));assertFalse(missing.has("imdbId"));assertFalse(missing.has("title"))
        assertEquals("favorite",missing.getString("kind"))
        assertEquals("tt5753856",talkLibraryIdentity(media.copy(imdbId="tt5753856"),"favorite").getString("imdbId"))
    }
    @Test fun libraryCompletionCannotRevealTheUnwatchedLastTwentyPercent() {
        assertFalse(talkPlaybackCompleted(800.0,1000.0,true))
        assertFalse(talkPlaybackCompleted(997.0,1000.0,true))
        assertTrue(talkPlaybackCompleted(999.0,1000.0,true))
        assertFalse(talkPlaybackCompleted(Double.NaN,1000.0,true))
        assertFalse(talkPlaybackCompleted(999.0,1000.0,false))
    }
    @Test fun skippedEpisodesAreNotSilentlyAddedToTheViewingBoundary() {
        assertEquals(listOf(Triple(1,1,2),Triple(1,4,4),Triple(2,1,1)),
            compactTalkViewedRanges(listOf(1 to 4,1 to 1,2 to 1,1 to 2,1 to 2,-1 to 1)))
    }
    @Test fun quietRoomAndSingleClicksDoNotStartAnUtterance() {
        val vad=TalkTurnDetector()
        repeat(50) { assertEquals(TalkTurnDetector.NONE,vad.accept(.001,it*20L,false)) }
        assertEquals(TalkTurnDetector.NONE,vad.accept(.2,1000,false))
        assertEquals(TalkTurnDetector.NONE,vad.accept(.001,1020,false));assertFalse(vad.active)
    }
    @Test fun sustainedSpeechStartsOnceAndNaturalPausesUseHangover() {
        val vad=TalkTurnDetector()
        vad.accept(.05,0,false);vad.accept(.05,20,false)
        assertEquals(TalkTurnDetector.START,vad.accept(.05,40,false));assertTrue(vad.active)
        assertEquals(TalkTurnDetector.NONE,vad.accept(.001,600,false))
        assertEquals(TalkTurnDetector.END,vad.accept(.001,800,false));assertFalse(vad.active)
    }
    @Test fun utterancesAreBoundedAndMuteResetsWithoutSpuriousTurns() {
        val vad=TalkTurnDetector();repeat(3){vad.accept(.05,it*20L,false)}
        assertEquals(TalkTurnDetector.END,vad.accept(.05,26000,false));assertFalse(vad.active)
        assertFalse(vad.reset());repeat(3){vad.accept(.05,27000+it*20L,false)}
        assertTrue(vad.reset());assertFalse(vad.active)
    }
}
