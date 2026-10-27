package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkConversationIntentTest {
    @Test fun clausesAndMovieThemesDoNotHijackTheRequestedTask(){
        assertEquals(TalkIntent.DECISION,talkConversationIntent("Don't recommend Arrival, choose between Primer and Coherence"))
        for(prompt in listOf("Recommend comedy or thriller movies", "Can you recommend films about restricted access?", "Compare Primer with Coherence, then recommend three others"))
            assertFalse(prompt,talkToolNeedsDiscoveryCorrection("recommendations",prompt,2))
        assertFalse(talkActionRequested("download","Don’t download it."))
        assertEquals(TalkIntent.CAPABILITIES,talkConversationIntent("Can you access my other apps?"))
        assertEquals(TalkIntent.DECISION,talkConversationIntent("Recommend Primer or Coherence"))
        assertEquals(listOf("After Life (1998)","After Yang"),talkExplicitComparisonTitles("Compare After Life (1998) with After Yang, then recommend two other films."))
        assertTrue(talkExplicitComparisonTitles("Don't compare Primer with Coherence; recommend two other films.").isEmpty())
    }
    @Test fun discoveryAndInformationNeverAuthorizeAnUnrequestedWrite(){
        assertFalse(talkActionRequested("download","Can you recommend an episode for my child?"))
        assertFalse(talkActionRequested("save","Suggest something with time loops"))
        assertFalse(talkActionRequested("download","What is a download?"))
        assertTrue(talkActionRequested("download","Find me episode two and download it in low quality"))
        assertTrue(talkActionRequested("save","Explain the difference, then save Primer"))
    }
    @Test fun explicitComparisonFallbackParsesNamesWithoutGuessingSentenceFragments() {
        assertEquals(listOf("Arrival","Ex Machina"),talkExplicitComparisonTitles("Should I watch Arrival or Ex Machina tonight?"))
        assertEquals(listOf("Moon","Source Code"),talkExplicitComparisonTitles("Choose between Moon and Source Code for a fast evening."))
        assertEquals(listOf("Blade Runner (1982)","Blade Runner 2049"),talkExplicitComparisonTitles("Compare the 1982 Blade Runner with Blade Runner 2049. Are they the same?"))
        assertTrue(talkExplicitComparisonTitles("Which two movies were in my first question?").isEmpty())
    }
    @Test fun suppliedChoicesCannotBecomeOpenDiscoveryEvenWhenTheySayRecommend() {
        val prompts=listOf("Should I watch Arrival or Ex Machina?","Recommend Arrival or Dune for tonight?","Compare Moon versus Source Code","Which of these would you pick?","Choose between the two for me","Do you recommend Dune over Arrival, or the opposite?","أختار Arrival ولا Ex Machina؟","Soll ich Arrival oder Dune schauen?")
        for(prompt in prompts) {
            assertEquals(prompt,TalkIntent.DECISION,talkConversationIntent(prompt,2))
            assertFalse(prompt,talkDiscoveryQuestion(prompt,true))
            assertTrue(prompt,talkToolNeedsDiscoveryCorrection("recommendations",prompt,2))
        }
    }
    @Test fun newChoicesStillUseTheResearchedPathAndFactsNeverDo() {
        for(prompt in listOf("Recommend two science fiction movies","Give me two different films like these","movies about gentlemen","Recommend one or two movies about identity"))assertTrue(prompt,talkDiscoveryQuestion(prompt,true))
        for(prompt in listOf("What are your technical limitations?","Can you see my other apps?","If I say stop, what happens?","Don't save anything; explain My List","Tell me about Arrival"))assertFalse(prompt,talkDiscoveryQuestion(prompt,true))
        assertEquals(TalkIntent.STOP,talkConversationIntent("Please stop talking."))
        assertEquals(TalkIntent.CAPABILITIES,talkConversationIntent("What are your technical limitations?"))
        assertEquals(TalkIntent.CONVERSATION,talkConversationIntent("If I say stop, what happens?"))
    }
    @Test fun excludedTitleAlternativesNeverBecomeAChoiceBetweenThoseTitles() {
        for(prompt in listOf(
            "Find two deadpan comedies about pointless institutions. Do not choose Brazil or Office Space.",
            "Recommend a quiet thriller, excluding Dune or Arrival.",
            "Find films about memory without choosing Memento or Total Recall.",
            "Recommend two films, avoid Brazil or Office Space, but keep the institutional absurdity."
        )) {
            assertNotEquals(prompt,TalkIntent.DECISION,talkConversationIntent(prompt,2))
            assertFalse(prompt,talkToolNeedsDiscoveryCorrection("recommendations",prompt,2))
        }
        assertEquals(TalkIntent.DECISION,talkConversationIntent("Choose Arrival or Moon. Do not choose Dune."))
    }
    @Test fun completedContentNeverRetainsWritingJustBecauseAuxiliaryWorkIsPending() {
        assertTrue(talkShowsResponseProgress(true,false))
        assertFalse(talkShowsResponseProgress(true,true))
        assertFalse(talkShowsResponseProgress(false,false))
    }
    @Test fun sharedDailyCapacityIsExplainedSeparatelyFromShortTermTraffic() {
        assertTrue(talkConnectionDetail(TalkHttpException(429,"DAILY_LIMIT")).contains("00:00 UTC"))
        assertTrue(talkConnectionDetail(TalkHttpException(429,"TRY_LATER")).contains("shortly"))
        assertEquals("",talkConnectionDetail(IllegalStateException("private diagnostic")))
    }
    @Test fun naturalFloorTakingIsNotLimitedToTheLiteralWordStop() {
        for(prompt in listOf("Please stop now.","Stop, please","Okay, that's enough.","Shut up","Hold on","Wait a second!","Cancel the search","Quiet please","warte kurz","لحظة"))
            assertEquals(prompt,TalkIntent.STOP,talkConversationIntent(prompt))
        for(prompt in listOf("If I say wait a second, will you stop?","Explain the ending of Never Let Me Go","Stop and compare Arrival with Moon instead"))
            assertNotEquals(prompt,TalkIntent.STOP,talkConversationIntent(prompt))
    }
    @Test fun questionsAndQuotesNeverAuthorizeMutationsWhileCompoundCommandsKeepTheirScope() {
        assertFalse(talkActionRequested("save","If I asked you to save Arrival, what would you need?"))
        assertFalse(talkActionRequested("save","The phrase save Arrival is just a quote. Explain it."))
        assertFalse(talkActionRequested("save","Do not save either film."))
        assertFalse(talkActionRequested("favorite","I love Arrival."))
        assertTrue(talkActionRequested("favorite","I love Arrival, favorite it."))
        assertTrue(talkActionRequested("dislike","I hate Arrival, hide it."))
        assertFalse(talkActionRequested("play","What can you play?"))
        assertTrue(talkActionRequested("save","Do not play it, but save it to My List."))
        assertTrue(talkActionRequested("save","If I haven't saved it already, save Arrival."))
        assertTrue(talkActionRequested("title_state","What would happen if I saved Arrival?"))
    }
}
