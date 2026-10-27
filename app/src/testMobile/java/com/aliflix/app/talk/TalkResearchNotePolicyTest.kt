package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkResearchNotePolicyTest {
 @Test fun anObservationNeedsActualPendingWorkAndCannotSeizeAudibleOutput() {
  assertTrue(talkResearchNoteEligible(false,true,false,-1,7))
  assertFalse(talkResearchNoteEligible(false,false,false,-1,7))
  assertFalse(talkResearchNoteEligible(false,true,true,-1,7))
  assertFalse(talkResearchNoteEligible(true,true,false,-1,7))
 }
 @Test fun anObservationCannotRepeatUntilTheUserStartsANewRequest() {
  assertFalse(talkResearchNoteEligible(false,true,false,7,7))
  assertTrue(talkResearchNoteEligible(false,true,false,7,8))
 }
 @Test fun expiredSpeechRecoveryNeverCutsAnAlreadyPlayingLineOrAnotherTurn() {
  val note=TalkResearchNote(7,"A useful observation about the request.",10000)
  assertFalse(talkResearchNoteNeedsRecovery(note,7,9999,false))
  assertTrue(talkResearchNoteNeedsRecovery(note,7,10000,false))
  assertFalse(talkResearchNoteNeedsRecovery(note,7,12000,true))
  assertFalse(talkResearchNoteNeedsRecovery(note,8,12000,false))
 }
}
