package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TalkTitleCardsTest {
    @Test fun cardsAcceptOnlyRealCatalogueIdentitiesAndSafePosterPaths() {
        val cards=parseTalkTitles(JSONArray("""[{"tmdbId":27205,"mediaType":"movie","title":"Inception","year":"2010","posterPath":"/poster.jpg"},{"id":0,"type":"movie","title":"Invalid"},{"id":27205,"type":"movie","title":"Duplicate"},{"id":70523,"type":"tv","title":"Dark","posterPath":"https://bad.example/a"}]"""))
        assertEquals(2,cards.size);assertEquals("movie:27205",cards.first().key);assertNull(cards.last().posterPath)
        assertEquals(cards,parseTalkTitles(JSONArray(cards.map{it.json()})))
    }
    @Test fun aSpokenBridgeCannotCountAsTheRequestedTitleAnswer() {
        val cards=listOf(TalkTitleCard(522627,MediaType.MOVIE,"The Gentlemen"),TalkTitleCard(207703,MediaType.MOVIE,"Kingsman: The Secret Service"))
        assertFalse(talkAnswerNamesTitle("I'll find elegant films with charm and character.",cards))
        assertTrue(talkAnswerNamesTitle("Try The Gentlemen (2020) for its sharp wit.",cards))
        assertTrue(talkAnswerNamesTitle("Gentlemen is a stylish crime comedy.",cards))
    }
    @Test fun savedRepliesRetainTheirCompactCards() {
        val cards=listOf(TalkTitleCard(70523,MediaType.TV,"Dark","2017","/poster.jpg"))
        val chat=TalkChat(java.util.UUID.randomUUID().toString(),"A mystery",123,listOf(TalkLine("assistant","Try Dark.",titles=cards)))
        assertEquals(chat,parseTalkChat(chat.json()))
    }
}
