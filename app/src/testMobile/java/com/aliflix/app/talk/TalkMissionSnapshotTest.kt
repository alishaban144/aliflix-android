package com.aliflix.app.talk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkMissionSnapshotTest {
 @Test fun cancelledOrRevisedWorkCannotRollBackToAnEarlierSnapshot(){
  fun row(revision:Int,at:Long,status:String)=JSONObject().put("id","night").put("revision",revision).put("updatedAt",at).put("status",status)
  val cancelled=row(4,200,"cancelled")
  assertSame(cancelled,talkLatestMission(cancelled,row(3,300,"running")))
  assertSame(cancelled,talkLatestMission(cancelled,row(4,100,"ready")))
  val resumed=row(5,210,"running");assertSame(resumed,talkLatestMission(cancelled,resumed))
 }
}
