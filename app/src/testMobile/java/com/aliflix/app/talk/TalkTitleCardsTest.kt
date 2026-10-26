package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TalkTitleCardsTest {
    @Test fun conversationCardsSurviveFollowupsFocusAndCanBeRestored() {
        val cards=listOf(TalkTitleCard(1,MediaType.MOVIE,"First Film"),TalkTitleCard(2,MediaType.MOVIE,"Second Film"),TalkTitleCard(3,MediaType.MOVIE,"Third Film"))
        val group=TalkCardContext().show(cards)
        val focused=group.question("Tell me about Third Film")
        assertEquals(listOf(cards[2]),focused.visible)
        assertEquals(focused,focused.question("Why is it worth watching?"))
        assertEquals(listOf(cards[2]),focused.topic("Third Film").visible)
        assertTrue(focused.question("Let's change subjects. Why are leaves red?").visible.isEmpty())
        assertEquals(listOf(cards[0]),focused.question("Show the first one again").visible)
        assertEquals("A visual keyword is not a card dismissal",focused,focused.topic("ELEGANT CINEMA"))
        val changed=focused.question("Let's change topics to travel")
        assertTrue(changed.visible.isEmpty())
        assertEquals(cards,changed.show(changed.select(cards.map{it.title})).visible)
        assertTrue(TalkCardContext().visible.isEmpty())
        assertFalse(talkCanDismissCards("Show me the card of the mentioned title",listOf(cards[2])))
        assertFalse(talkCanDismissCards("What makes its style interesting?",listOf(cards[2])))
        assertTrue(talkCanDismissCards("Hide the card",listOf(cards[2])))
        assertTrue(talkCanDismissCards("Let's change subjects to travel",listOf(cards[2])))
    }
    @Test fun cardsAcceptOnlyRealCatalogueIdentitiesAndSafePosterPaths() {
        val cards=parseTalkTitles(JSONArray("""[{"tmdbId":27205,"mediaType":"movie","title":"Inception","year":"2010","posterPath":"/poster.jpg"},{"id":0,"type":"movie","title":"Invalid"},{"id":27205,"type":"movie","title":"Duplicate"},{"id":70523,"type":"tv","title":"Dark","posterPath":"https://bad.example/a"}]"""))
        assertEquals(2,cards.size);assertEquals("movie:27205",cards.first().key);assertNull(cards.last().posterPath)
        assertEquals(cards,parseTalkTitles(JSONArray(cards.map{it.json()})))
    }
    @Test fun aSpokenBridgeCannotCountAsTheRequestedTitleAnswer() {
        val cards=listOf(TalkTitleCard(522627,MediaType.MOVIE,"The Gentlemen"),TalkTitleCard(207703,MediaType.MOVIE,"Kingsman: The Secret Service"))
        assertFalse(talkAnswerNamesAllTitles("I'll find elegant films with charm and character.",cards))
        assertFalse(talkAnswerNamesAllTitles("Try The Gentlemen (2020) for its sharp wit.",cards))
        assertTrue(talkAnswerNamesAllTitles("The Gentlemen and Kingsman: The Secret Service are stylish crime comedies.",cards))
    }
    @Test fun savedRepliesRetainTheirCompactCards() {
        val cards=listOf(TalkTitleCard(70523,MediaType.TV,"Dark","2017","/poster.jpg"))
        val chat=TalkChat(java.util.UUID.randomUUID().toString(),"A mystery",123,listOf(TalkLine("assistant","Try Dark.",titles=cards)))
        assertEquals(chat,parseTalkChat(chat.json()))
    }
}
