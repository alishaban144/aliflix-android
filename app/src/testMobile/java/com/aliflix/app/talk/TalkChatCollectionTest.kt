package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TalkChatCollectionTest {
    private val cards=(1..12).map{TalkTitleCard(it,MediaType.TV,"Catalogue title $it","2024","/poster$it.jpg")}
    @Test fun aLargeCollectionSurvivesHistoryAndRestoresItsActualLatestOrder() {
        val old=cards.take(3).map{it.copy(id=it.id+100)}
        val chat=TalkChat("collection","A programme",1,listOf(TalkLine("assistant","Earlier choices",titles=old),TalkLine("assistant","A collection",titles=cards)))
        val restored=parseTalkChat(chat.json())
        assertEquals(cards,restored.lines.last().titles)
        val context=talkRestoredCards(restored.lines)
        assertEquals(cards.map{it.key},context.group.map{it.key})
        assertEquals(cards[9],context.resolve("the tenth one").single())
        assertEquals(cards.last(),context.references("What about the last one?").single())
        assertEquals(cards[10],context.references("Open the 11th film").single())
    }
    @Test fun aRefinementCannotMutateTheEarlierMessagesCollection() {
        val earlier=TalkLine("assistant","Earlier collection",titles=cards)
        val next=earlier.copy(text="A narrower collection",titles=cards.takeLast(4))
        assertEquals(12,earlier.titles.size)
        assertEquals(4,next.titles.size)
        assertEquals(cards,earlier.titles)
    }
    @Test(timeout=3000) fun oversizedArtworkAndAttributionCannotTrapHistorySerialization() {
        val titles=(1..24).map{TalkTitleCard(it,MediaType.MOVIE,"片".repeat(180),"2024","/"+"p".repeat(180)+".jpg","原".repeat(180))}
        val sources=(1..12).map{TalkReference("題".repeat(160),"https://example.org/"+"a".repeat(500)+"/$it","著".repeat(240))}
        val json=TalkChat("large","A collection",1,listOf(TalkLine("assistant","A genuine collection",sources,titles=titles))).json()
        assertTrue(json.toString().toByteArray(Charsets.UTF_8).size<=21000)
        assertEquals(24,json.getJSONArray("lines").getJSONObject(0).getJSONArray("titles").length())
        assertTrue(json.getJSONArray("lines").getJSONObject(0).getString("text").isNotEmpty())
    }
    @Test fun aResultCollectionCanBeSavedBeforeItsOptionalNarrationArrives() {
        val line=TalkLine("assistant","",titles=cards)
        assertEquals(line,parseTalkChat(TalkChat("cards","A search",1,listOf(line)).json()).lines.single())
    }
}
