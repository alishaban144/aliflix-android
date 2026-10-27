package com.aliflix.app.talk

/** Progress is measured at the hardware playback head, not at enqueue/write.
 * Never recover while the user owns an intentional interruption. */
internal class TalkOutputHealth {
 private var head=0L
 private var sequence=Long.MIN_VALUE
 private var progressedAt=0L
 private var retried=false
 fun inspect(now:Long,turn:Long,rendered:Long,written:Long,interrupted:Boolean):Int {
  if(sequence!=turn||rendered!=head||written<=rendered||interrupted){
   sequence=turn;head=rendered;progressedAt=now;retried=false;return 0
  }
  if(now-progressedAt<1500)return 0
  progressedAt=now
  if(!retried){retried=true;return 1}
  return 2
 }
}
