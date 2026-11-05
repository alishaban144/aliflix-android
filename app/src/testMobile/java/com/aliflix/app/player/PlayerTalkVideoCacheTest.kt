package com.aliflix.app.player

import com.aliflix.app.talk.TalkSceneFrame
import org.junit.Assert.*
import org.junit.Test

class PlayerTalkVideoCacheTest {
 @Test fun tinyPausePositionDriftReusesTheActualDecodedFrame(){
  val frame=TalkSceneFrame(30.033,byteArrayOf(1))
  assertSame(frame,cachedTalkVideoFrame(mapOf(30000L to frame),30040,31.0))
  assertNull(cachedTalkVideoFrame(mapOf(30000L to frame),30400,31.0))
 }
 @Test fun rewindingNeverReusesAFrameBeyondTheNewViewingBoundary(){
  val later=TalkSceneFrame(30.033,byteArrayOf(1));val earlier=TalkSceneFrame(29.9,byteArrayOf(2))
  assertNull(cachedTalkVideoFrame(mapOf(30000L to later),30000,30.0))
  assertSame(earlier,cachedTalkVideoFrame(mapOf(30000L to later,29980L to earlier),30000,30.0))
 }
}
