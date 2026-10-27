package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkInputTurnGateTest {
 private fun frame(value:Int=0)=ByteArray(1024){value.toByte()}
 @Test fun silenceAndEchoNeverOpenAnActivityEvenDuringLongOutput(){
  val gate=TalkInputTurnGate()
  repeat(6000){assertFalse(gate.accept(frame(),0f,false).start)}
  repeat(600){assertFalse(gate.accept(frame(7),.999f,true).start)}
  assertFalse(gate.active)
 }
 @Test fun confirmedSpeechOpensIn64msAndRetainsTheQuietLeadingWord(){
  val gate=TalkInputTurnGate();val firstWord=frame(4)
  gate.accept(firstWord,.25f,false)
  repeat(10){gate.accept(frame(2),.40f,false)}
  assertFalse(gate.accept(frame(9),.8f,false).start)
  val start=gate.accept(frame(9),.8f,false)
  assertTrue(start.start);assertTrue(start.frames.any{it.contentEquals(firstWord)})
  assertFalse(gate.accept(frame(9),.99f,false).start)
 }
 @Test fun clausePausesDoNotSplitTurnsAndSilenceEndsExactlyOnce(){
  val gate=TalkInputTurnGate();repeat(2){gate.accept(frame(5),.9f,false)}
  repeat(18){assertFalse(gate.accept(frame(),0f,false).end)}
  gate.accept(frame(5),.50f,false)
  repeat(21){assertFalse(gate.accept(frame(),0f,false).end)}
  assertTrue(gate.accept(frame(),0f,false).end)
  assertFalse(gate.accept(frame(),0f,false).end);assertFalse(gate.active)
 }
 @Test fun aTransientCannotSeizeTheFloorAndMuteClosesOnlyAnOpenActivity(){
  val gate=TalkInputTurnGate()
  gate.accept(frame(7),.99f,false);gate.accept(frame(),.1f,false)
  assertFalse(gate.active);assertFalse(gate.finish())
  repeat(2){gate.accept(frame(7),.9f,false)}
  assertTrue(gate.finish());assertFalse(gate.finish())
  assertFalse(gate.accept(frame(),0f,false).start)
 }
}
