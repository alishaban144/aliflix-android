package com.aliflix.app.talk

import kotlin.math.sqrt

/** Bounded amplitude landmarks on the actual AudioTrack frame timeline. The
 * face follows rendered syllables, irrespective of microphone read cadence. */
internal class TalkOutputEnvelope {
 private data class Window(val first:Long,val end:Long,val rms:Double)
 private val windows=ArrayDeque<Window>()
 @Synchronized fun offer(bytes:ByteArray,offset:Int,count:Int,firstFrame:Long){
  var at=offset
  while(at<offset+count){
   val end=minOf(at+960,offset+count)
   var squared=0.0
   for(i in at until end step 2){val sample=((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort()/32768.0;squared+=sample*sample}
   windows.addLast(Window(firstFrame+(at-offset)/2,firstFrame+(end-offset)/2,sqrt(squared/((end-at)/2))))
   at=end
  }
  while(windows.size>1024)windows.removeFirst()
 }
 @Synchronized fun at(renderedFrame:Long):Double {
  while(windows.isNotEmpty()&&renderedFrame>=windows.first().end)windows.removeFirst()
  return windows.firstOrNull()?.takeIf{renderedFrame>=it.first}?.rms?:0.0
 }
 @Synchronized fun clear(){windows.clear()}
}
