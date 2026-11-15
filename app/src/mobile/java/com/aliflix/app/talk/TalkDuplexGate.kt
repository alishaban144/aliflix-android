package com.aliflix.app.talk

import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded far-end reference for echo evidence only. TalkInputTurnGate owns all
 * speech timing and prefix buffering. Reference expires after playback's tail;
 * an old assistant word cannot veto a later human utterance. PCM stays in RAM. */
internal class TalkDuplexGate(private val now:()->Long={android.os.SystemClock.elapsedRealtime()}) {
 private val reference=DoubleArray(4_000)
 private var referenceCount=0
 private var nextReference=0
 private var expiresAt=0L
 @Synchronized fun reference(pcm:ByteArray,offset:Int=0,length:Int=pcm.size-offset) {
  for(at in offset until offset+length-23 step 24){
   var sum=0.0
   for(i in at until at+24 step 2)sum+=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()/32768.0
   reference[nextReference]=sum/12
   nextReference=(nextReference+1)%reference.size
   if(referenceCount<reference.size)referenceCount++
  }
  expiresAt=now()+1200
 }
 @Synchronized fun isEcho(pcm:ByteArray,nearProbability:Float=0f):Boolean {
  if(now()>expiresAt)return false
  val input=DoubleArray(pcm.size/16){n->
   var sum=0.0
   for(i in n*16 until n*16+16 step 2)sum+=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort()/32768.0
   sum/8
  }
  if(input.size<32||referenceCount<input.size)return false
  val first=if(referenceCount==reference.size)nextReference else 0
  val tail=minOf(reference.size-first,referenceCount)
  val ref=DoubleArray(referenceCount)
  reference.copyInto(ref,0,first,first+tail)
  if(tail<referenceCount)reference.copyInto(ref,tail,0,referenceCount-tail)
  val energy=input.sumOf{it*it}
  if(energy<1e-8)return false
  var best=0.0
  for(start in 0..ref.size-input.size step 2){
   var dot=0.0;var power=0.0
   for(i in input.indices){val value=ref[start+i];dot+=input[i]*value;power+=value*value}
   if(power>1e-8)best=maxOf(best,abs(dot)/sqrt(energy*power))
  }
  return best>.92||best>.48&&nearProbability<.9f
 }
 @Synchronized fun reset(){referenceCount=0;nextReference=0;expiresAt=0}
}
