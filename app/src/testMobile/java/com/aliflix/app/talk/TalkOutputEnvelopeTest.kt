package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkOutputEnvelopeTest {
 private fun pcm(value:Int,frames:Int)=ByteArray(frames*2).also{b->repeat(frames){b[it*2]=value.toByte();b[it*2+1]=(value shr 8).toByte()}}
 @Test fun queuedFutureSpeechCannotAnimateBeforeItsPlaybackPosition(){
  val e=TalkOutputEnvelope();e.offer(pcm(16384,960),0,1920,100)
  assertEquals(0.0,e.at(99),0.0);assertEquals(.5,e.at(100),.001)
  assertEquals(.5,e.at(700),.001);assertEquals(0.0,e.at(1060),0.0)
 }
 @Test fun followsSyllablesInRenderedOrderAndDropsInterruptedSpeech(){
  val e=TalkOutputEnvelope();val bytes=pcm(8192,480)+pcm(0,480)+pcm(16384,480)
  e.offer(bytes,0,bytes.size,0)
  assertEquals(.25,e.at(120),.001);assertEquals(0.0,e.at(600),0.0);assertEquals(.5,e.at(1000),.001)
  e.clear();assertEquals(0.0,e.at(0),0.0)
 }
}
