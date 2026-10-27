package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TalkChatReplyTest {
 private val cards=(1..12).map{TalkTitleCard(it,MediaType.TV,"Series $it","2020")}
 private val sources=listOf(TalkReference("Evidence","https://example.org/evidence","Publisher"))
 @Test fun aProviderGenerationResetCannotDuplicateCardsOrDetachSources() {
  val reply=TalkChatReply();var lines=listOf(TalkLine("user","Find a collection"))
  reply.attach(cards,sources);lines=reply.upsert(lines)
  reply.narrate("A concrete distinction.",true,cards,emptyList());lines=reply.upsert(lines)
  reply.narrate("A further detail.",true,cards,emptyList());lines=reply.upsert(lines)
  assertEquals(1,lines.count{it.role=="assistant"})
  assertEquals(cards,lines.last().titles);assertEquals(sources,lines.last().sources)
  assertEquals("A concrete distinction. A further detail.",lines.last().text)
 }
 @Test fun growingTranscriptionReplacesItsOwnSegment() {
  val reply=TalkChatReply()
  reply.narrate("The first",true,cards,sources)
  reply.narrate("The first has a closed setting.",false,cards,emptyList())
  assertEquals("The first has a closed setting.",reply.line().text)
 }
 @Test fun aCompetingLateSearchCannotChangeTheCommittedIdentitiesOrEvidence() {
  val reply=TalkChatReply();reply.attach(cards,sources)
  reply.attach(cards.map{it.copy(id=it.id+100)},listOf(TalkReference("Other","https://example.org/other","Other")))
  assertEquals(cards,reply.line().titles);assertEquals(sources,reply.line().sources)
 }
 @Test fun aNewQuestionLeavesThePreviousCollectionIntact() {
  val reply=TalkChatReply();reply.attach(cards,sources)
  val old=reply.line();val lines=listOf(TalkLine("user","First request"),old,TalkLine("user","Only the recent ones"))
  reply.reset();reply.attach(cards.takeLast(2),sources)
  val updated=reply.upsert(lines)
  assertEquals(old,updated[1]);assertEquals(2,updated.last().titles.size)
 }
 @Test fun aLateCumulativeTranscriptDoesNotRepeatTheCompletedPrefix() {
  val reply=TalkChatReply();reply.narrate("A complete answer.",true,cards,sources)
  reply.narrate("A complete answer. Another point.",true,cards,emptyList())
  assertEquals("A complete answer. Another point.",reply.line().text)
 }
 @Test fun anAnchorPreviewDoesNotOccupyTheLaterRecommendationCollection() {
  val reply=TalkChatReply()
  reply.narrate("The selected series establishes the premise.",true,listOf(cards.first()),emptyList())
  reply.attach(cards,sources)
  assertEquals(cards,reply.line().titles);assertEquals(sources,reply.line().sources)
 }
 @Test fun separateFactualLookupsCanShowBothComparedTitles() {
  val reply=TalkChatReply()
  reply.narrate("The first setting.",true,listOf(cards[0]),sources)
  reply.narrate("The second setting.",true,listOf(cards[1]),emptyList())
  assertEquals(cards.take(2),reply.line().titles)
 }
}
