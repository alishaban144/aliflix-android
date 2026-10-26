package com.aliflix.app.talk

import kotlin.math.abs
import kotlin.math.sqrt

/** Compare microphone speech against the actual far-end PCM, not merely a VAD
 * score. Keep a bounded pre-roll so confirming a real interruption loses no words.
 * Samples exist only in RAM and are cleared at each connection boundary. */
internal class TalkDuplexGate {
 private val reference=ArrayDeque<Double>()
 private val preRoll=ArrayDeque<ByteArray>()
 private var preRollBytes=0
 private var candidateMs=0
 private var opened=false
 private var echoTailUntil=0L
 private var echoFloor=.002
 @Synchronized fun reference(pcm:ByteArray,offset:Int=0,length:Int=pcm.size-offset) {
  for(at in offset until offset+length-23 step 24){
   var sum=0.0
   for(i in at until at+24 step 2)sum+=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()/32768.0
   reference.addLast(sum/12)
  }
  while(reference.size>4000)reference.removeFirst()
 }
 @Synchronized fun microphone(pcm:ByteArray,assistantActive:Boolean,probability:Float,now:Long):List<ByteArray> {
  if(assistantActive)echoTailUntil=now+240
  if(!assistantActive&&now>=echoTailUntil){resetInput();return listOf(pcm)}
  if(opened)return listOf(pcm)
  preRoll.addLast(pcm.copyOf());preRollBytes+=pcm.size
  while(preRollBytes>38400&&preRoll.isNotEmpty())preRollBytes-=preRoll.removeFirst().size
  val samples=DoubleArray(pcm.size/16){n->
   var sum=0.0;for(i in n*16 until n*16+16 step 2)sum+=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()/32768.0
   sum/8
  }
  val energy=sqrt(samples.sumOf{it*it}/samples.size.coerceAtLeast(1))
  val echo=correlation(samples)>.48
  if(echo||probability<.65f)echoFloor=echoFloor*.94+energy.coerceAtMost(.05)*.06
  val near=probability>=.90f&&!echo&&energy>maxOf(.003,minOf(.008,echoFloor*1.6))
  candidateMs=if(near)candidateMs+pcm.size/32 else 0
  if(candidateMs>=180){opened=true;return preRoll.toList().also{preRoll.clear();preRollBytes=0}}
  return listOf(ByteArray(pcm.size))
 }
 private fun correlation(input:DoubleArray):Double {
  if(input.size<32||reference.size<input.size)return 0.0
  val ref=reference.toDoubleArray();val energy=input.sumOf{it*it}
  if(energy<1e-8)return 0.0
  var best=0.0
  for(start in 0..ref.size-input.size step 2){
   var dot=0.0;var power=0.0
   for(i in input.indices){val v=ref[start+i];dot+=input[i]*v;power+=v*v}
   if(power>1e-8)best=maxOf(best,abs(dot)/sqrt(energy*power))
  }
  return best
 }
 val interrupted:Boolean @Synchronized get()=opened
 @Synchronized fun reset(){reference.clear();echoTailUntil=0;echoFloor=.002;resetInput()}
 private fun resetInput(){preRoll.clear();preRollBytes=0;candidateMs=0;opened=false}
}
