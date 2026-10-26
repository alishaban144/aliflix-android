package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class TalkDuplexGateTest {
 private fun pcm(rate:Int,seconds:Double,frequency:Double,amplitude:Double=.2,offset:Double=0.0)=ByteArray((rate*seconds).toInt()*2).also{bytes->
  for(i in 0 until bytes.size/2){val value=(sin(2*Math.PI*frequency*(i.toDouble()/rate+offset))*amplitude*32767).toInt();bytes[i*2]=value.toByte();bytes[i*2+1]=(value shr 8).toByte()}
 }
 @Test fun farEndSpeechCannotInterruptEvenWithPerfectSpeechProbability(){
  val gate=TalkDuplexGate();gate.reference(pcm(24000,1.0,317.0))
  for(frame in 0 until 40){
   val output=gate.microphone(pcm(16000,.05,317.0,.02,frame*.05),true,.99f,1000L+frame*50)
   assertTrue(output.all{it.all{b->b==0.toByte()}})
   assertFalse("Speaker echo must never seize the floor",gate.interrupted)
  }
 }
 @Test fun realDoubleTalkRetainsItsOpeningWordsAndInterrupts(){
  val gate=TalkDuplexGate();gate.reference(pcm(24000,1.0,317.0))
  val beginning=pcm(16000,.05,739.0)
  var released=emptyList<ByteArray>()
  for(frame in 0 until 5)released=gate.microphone(beginning,true,.99f,1000L+frame*50)
  assertTrue(gate.interrupted);assertTrue(released.any{it.contentEquals(beginning)})
 }
 @Test fun aQuieterHumanAfterLoudEchoRetainsWordsBeforeNeuralConfirmation(){
  val gate=TalkDuplexGate();gate.reference(pcm(24000,1.0,317.0))
  repeat(40){gate.microphone(pcm(16000,.05,317.0,.2,it*.05),true,.99f,1000L+it*50)}
  val first=pcm(16000,.05,881.0,.04)
  gate.microphone(first,true,.2f,3000)
  repeat(12){gate.microphone(pcm(16000,.05,739.0,.04),true,.2f,3050L+it*50)}
  var released=emptyList<ByteArray>()
  repeat(4){released=gate.microphone(pcm(16000,.05,739.0,.04),true,.99f,3650L+it*50)}
  assertTrue("A human does not have to be louder than the assistant",gate.interrupted)
  assertTrue("Delayed neural confirmation must retain the first word",released.any{it.contentEquals(first)})
 }
 @Test fun noiseAndBriefTransientCannotInterruptAndResetErasesReference(){
  val gate=TalkDuplexGate();gate.reference(pcm(24000,1.0,317.0))
  repeat(3){assertTrue(gate.microphone(pcm(16000,.05,739.0),true,.99f,1000L+it*50).all{it.all{b->b==0.toByte()}})}
  assertFalse(gate.interrupted)
  gate.microphone(ByteArray(1600),true,0f,1200)
  repeat(10){gate.microphone(pcm(16000,.05,739.0,.002),true,.1f,1250L+it*50)}
  assertFalse(gate.interrupted)
  gate.reset();val input=pcm(16000,.05,317.0)
  assertTrue(gate.microphone(input,false,0f,3000).single().contentEquals(input))
 }
}
