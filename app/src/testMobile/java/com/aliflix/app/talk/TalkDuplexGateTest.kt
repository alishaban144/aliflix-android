package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class TalkDuplexGateTest {
 private fun pcm(rate:Int,seconds:Double,frequency:Double,amplitude:Double=.2)=ByteArray((rate*seconds).toInt()*2).also{bytes->
  for(i in 0 until bytes.size/2){val value=(sin(2*Math.PI*frequency*i/rate)*amplitude*32767).toInt();bytes[i*2]=value.toByte();bytes[i*2+1]=(value shr 8).toByte()}
 }
 @Test fun scaledAndResampledFarEndRemainsEchoEvidence(){
  val gate=TalkDuplexGate{0};gate.reference(pcm(24000,1.0,317.0))
  assertTrue(gate.isEcho(pcm(16000,.032,317.0,.02)))
  assertTrue(gate.isEcho(pcm(16000,.032,317.0,.3)))
  assertFalse(gate.isEcho(pcm(16000,.032,881.0)))
 }
 @Test fun oldReferenceAndSilenceCannotVetoAUserTurn(){
  var now=0L;val gate=TalkDuplexGate{now};gate.reference(pcm(24000,1.0,317.0))
  assertFalse(gate.isEcho(ByteArray(1024)))
  now=1201;assertFalse(gate.isEcho(pcm(16000,.032,317.0)))
  now=0;gate.reset();assertFalse(gate.isEcho(pcm(16000,.032,317.0)))
 }
}
