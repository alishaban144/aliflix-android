package com.aliflix.app.talk

/** Bounded far-end reference for echo evidence only. TalkInputTurnGate owns all
 * speech timing and prefix buffering. Reference expires after playback's tail;
 * an old assistant word cannot veto a later human utterance. PCM stays in RAM. */
internal class TalkDuplexGate(private val now:()->Long={android.os.SystemClock.elapsedRealtime()}) {
 private val reference=DoubleArray(4_000)
 private var referenceCount=0
 private var nextReference=0
 private var referenceSum=0.0
 private var referenceSamples=0
 private var expiresAt=0L
 @Synchronized fun reference(pcm:ByteArray,offset:Int=0,length:Int=pcm.size-offset) {
  for(at in offset until offset+length-1 step 2){
   referenceSum+=((pcm[at].toInt() and 255) or (pcm[at+1].toInt() shl 8)).toShort()/32768.0
   referenceSamples++
   if(referenceSamples==12){
    reference[nextReference]=referenceSum/12
    nextReference=(nextReference+1)%reference.size
    if(referenceCount<reference.size)referenceCount++
    referenceSum=0.0
    referenceSamples=0
   }
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
  var referencePower=0.0
  for(i in input.indices)referencePower+=ref[i]*ref[i]
  for(start in 0..ref.size-input.size){
   if(referencePower>1e-8){
    var dot=0.0
    for(i in input.indices)dot+=input[i]*ref[start+i]
    val squaredCorrelation=dot*dot/(energy*referencePower)
    // Post-AEC speech can be quiet even when the neural detector is confident.
    // Require a waveform match as well as attenuation to use the lower bound.
    if(squaredCorrelation>.85 ||
       squaredCorrelation>.23&&nearProbability<.9f ||
       squaredCorrelation>.16&&energy<referencePower*.015625)return true
   }
   if(start+input.size<ref.size){
    val entering=ref[start+input.size]
    val leaving=ref[start]
    referencePower+=entering*entering-leaving*leaving
   }
  }
  return false
 }
 @Synchronized fun reset(){
  referenceCount=0
  nextReference=0
  referenceSum=0.0
  referenceSamples=0
  expiresAt=0
 }
}
