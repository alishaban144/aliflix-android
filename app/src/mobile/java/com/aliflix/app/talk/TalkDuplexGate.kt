package com.aliflix.app.talk

import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded far-end reference for echo evidence only. TalkInputTurnGate owns all
 * speech timing and prefix buffering. Reference expires after playback's tail;
 * an old assistant word cannot veto a later human utterance. PCM stays in RAM. */
internal class TalkDuplexGate(private val now:()->Long={android.os.SystemClock.elapsedRealtime()}) {
 private val reference=ArrayDeque<Double>()
 private var expiresAt=0L
 @Synchronized fun reference(pcm:ByteArray,offset:Int=0,length:Int=pcm.size-offset) {
  for(at in offset until offset+length-23 step 24){
   var sum=0.0
   for(i in at until at+24 step 2)sum+=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()/32768.0
   reference.addLast(sum/12)
  }
  while(reference.size>4000)reference.removeFirst()
  expiresAt=now()+1200
 }
 @Synchronized fun isEcho(pcm:ByteArray):Boolean {
  if(now()>expiresAt)return false
  val input=DoubleArray(pcm.size/16){n->
   var sum=0.0
   for(i in n*16 until n*16+16 step 2)sum+=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()/32768.0
   sum/8
  }
  if(input.size<32||reference.size<input.size)return false
  val ref=reference.toDoubleArray();val energy=input.sumOf{it*it}
  if(energy<1e-8)return false
  var best=0.0
  for(start in 0..ref.size-input.size step 2){
   var dot=0.0;var power=0.0
   for(i in input.indices){val value=ref[start+i];dot+=input[i]*value;power+=value*value}
   if(power>1e-8)best=maxOf(best,abs(dot)/sqrt(energy*power))
  }
  return best>.48
 }
 @Synchronized fun reset(){reference.clear();expiresAt=0}
}
