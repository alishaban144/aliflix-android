package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TalkMentionedTitlesTest {
    private val arrival=TalkTitleCard(329865,MediaType.MOVIE,"Arrival","2016",null)
    @Test fun aSlowMissingTitleCannotHideOtherResolvedCards()=kotlinx.coroutines.runBlocking {
        val second=arrival.copy(id=2,title="Project Hail Mary")
        val reports=mutableListOf<List<String>>()
        val result=resolveTalkNamedCards(listOf("first","slow","second"),budgetMillis=1000,onResolved={reports+=it.map{card->card.key}}){name->
            when(name){"first"->listOf(arrival);"second"->listOf(second);else->{kotlinx.coroutines.awaitCancellation()}}
        }
        assertEquals(listOf(arrival.key,second.key),result.map{it.key})
        assertTrue(reports.any{it==listOf(arrival.key)})
        assertEquals(listOf(arrival.key,second.key),reports.last())
    }
    @Test fun everyMarkedExampleIsResolvedEvenWhenTheQuestionHasAnExistingAnchor(){
        assertEquals(listOf("Arrival","Blue Heron","Project Hail Mary","The Boy and the Heron"),talkAnswerTitleNames(
            "If you liked Arrival, try *Blue Heron*, **Project Hail Mary**, or “The Boy and the Heron”.",listOf(arrival)))
        assertFalse(talkUsesGroundedCardReferences(listOf("Blue Heron","Project Hail Mary"),listOf(arrival),false))
        assertFalse(talkUsesGroundedCardReferences(listOf("Arrival","Project Hail Mary"),listOf(arrival),false))
        assertTrue(talkUsesGroundedCardReferences(listOf("it"),listOf(arrival),false))
    }
    @Test fun duplicateNamesAndOrdinaryLowercaseWordsNeverBecomeExtraCandidates(){
        assertEquals("Try Blue Heron, then Project Hail Mary.",talkFormattedAnswer("Try *Blue Heron*, then **Project Hail Mary**.").text)
        assertEquals(listOf("Arrival"),talkAnswerTitleNames("*Arrival* and Arrival have the same meaning here. Look up at the sky.",listOf(arrival)))
        assertEquals(emptyList<String>(),talkAnswerTitleNames("The answer is that memories are different from facts.",emptyList()))
    }
    @Test fun reconciliationPreservesTheWholeReplyAndExistingResearchedCollection(){
        val extra=arrival.copy(id=1,title="Project Hail Mary",year="2026")
        val reply=TalkChatReply()
        reply.attach(listOf(arrival),emptyList());reply.narrate("Compare *Arrival* with *Project Hail Mary*.",false,listOf(arrival),emptyList())
        reply.reconcileNamedCards(listOf(extra,arrival))
        assertEquals(listOf(arrival.key,extra.key),reply.line().titles.map{it.key})
        assertEquals("Compare *Arrival* with *Project Hail Mary*.",reply.line().text)
    }
}
