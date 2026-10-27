package com.aliflix.app.talk
import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TalkComparisonPreparationTest {
 @Test fun aLateComparisonCannotReplaceNewerRecommendationsButRemainsAvailableForFollowups(){
  fun card(id:Int)=TalkTitleCard(id,MediaType.MOVIE,"Owned $id","2001",null)
  val picks=listOf(card(1),card(2));val subjects=listOf(card(3),card(4))
  val context=TalkCardContext().show(picks,replaceGroup=true)
  val late=context.preparedComparison(subjects,true)
  assertEquals(picks,late.visible);assertEquals(picks,late.group)
  assertTrue(late.known.containsAll(picks+subjects))
  assertEquals(subjects,late.choiceGroups.last())
  assertEquals(subjects,context.preparedComparison(subjects,false).visible)
 }
}
