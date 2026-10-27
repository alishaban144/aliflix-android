package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TalkSpokenAttentionTest {
    private val cards=listOf(TalkTitleCard(1,MediaType.MOVIE,"Arrival"),TalkTitleCard(2,MediaType.MOVIE,"Moon"),TalkTitleCard(3,MediaType.MOVIE,"Coherence"))
    @Test fun anAlreadyCompleteTranscriptDoesNotFocusItsLastTitleBeforePlayback() {
        val text="Arrival begins the conversation. Moon changes the scale. Coherence brings it home."
        val attention=TalkSpokenAttention(text,cards,locked=true)
        attention.receive(240000,1000);attention.complete()
        assertNull(attention.cardAt(999))
        assertEquals(cards[0].key,attention.cardAt(1001))
        assertEquals(cards[1].key,attention.cardAt(1000+text.indexOf("Moon")*240000L/text.length+100))
        assertEquals(cards[2].key,attention.cardAt(240900))
        assertNull(attention.cardAt(241000))
    }
    @Test fun aNewPlaybackEpochCannotRetainThePreviousAnswersAttention() {
        val old=TalkSpokenAttention("Arrival and Moon",cards);old.receive(24000,0);old.complete()
        val replacement=TalkSpokenAttention("Coherence",cards)
        assertNull(replacement.cardAt(12000))
        replacement.receive(24000,0);replacement.complete()
        assertEquals(cards.last().key,replacement.cardAt(100))
    }
    @Test fun partialTranscriptionCanGrowWithoutResettingItsAudioClock() {
        val attention=TalkSpokenAttention("",cards)
        attention.receive(48000,7000)
        attention.update("Moon is a chamber drama.",cards);attention.complete()
        assertEquals(cards[1].key,attention.cardAt(7100))
    }
    @Test fun searchObservationsCannotBeSearchAnnouncementsOrPhraseBankSlogans() {
        assertFalse(talkResearchNoteAllowed("I'm looking for titles with stories that stay with you."))
        assertFalse(talkResearchNoteAllowed("Please wait while I search."))
        assertTrue(talkResearchNoteAllowed("A closed-room setting can turn a small cast into a much bigger puzzle."))
    }
    @Test fun returningToAnEarlierTitleReturnsAttentionToItsCard() {
        val text="Arrival has a broad scope. Moon is intimate. Arrival offers the stronger contrast."
        val attention=TalkSpokenAttention(text,cards,locked=true)
        attention.receive(240000,0);attention.complete()
        assertEquals(cards[1].key,attention.cardAt(text.indexOf("Moon")*240000L/text.length+100))
        assertEquals(cards[0].key,attention.cardAt(text.lastIndexOf("Arrival")*240000L/text.length+100))
    }
}
