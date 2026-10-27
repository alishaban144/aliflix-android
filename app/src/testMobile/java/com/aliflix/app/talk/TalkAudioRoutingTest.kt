package com.aliflix.app.talk

import android.media.AudioDeviceInfo.*
import org.junit.Assert.*
import org.junit.Test

class TalkAudioRoutingTest {
 @Test fun aHandsFreeConversationChoosesTheSpeakerInsteadOfTheDefaultEarpiece(){
  assertEquals(TYPE_BUILTIN_SPEAKER,talkPreferredCommunicationType(setOf(TYPE_BUILTIN_EARPIECE,TYPE_BUILTIN_SPEAKER),TYPE_BUILTIN_EARPIECE))
  assertNull(talkPreferredCommunicationType(emptySet(),null))
 }
 @Test fun anActiveExternalRouteIsPreservedAndAnAddedHeadsetCanReplaceTheSpeaker(){
  assertEquals(TYPE_BLUETOOTH_SCO,talkPreferredCommunicationType(setOf(TYPE_BLUETOOTH_SCO,TYPE_USB_HEADSET,TYPE_BUILTIN_SPEAKER),TYPE_BLUETOOTH_SCO))
  assertEquals(TYPE_USB_HEADSET,talkPreferredCommunicationType(setOf(TYPE_USB_HEADSET,TYPE_BUILTIN_SPEAKER),TYPE_BUILTIN_SPEAKER))
  assertEquals(TYPE_BUILTIN_SPEAKER,talkPreferredCommunicationType(setOf(TYPE_BUILTIN_SPEAKER),TYPE_USB_HEADSET))
  assertEquals(TYPE_HEARING_AID,talkPreferredCommunicationType(setOf(TYPE_BUILTIN_SPEAKER,TYPE_HEARING_AID),TYPE_HEARING_AID))
  assertEquals(999,talkPreferredCommunicationType(setOf(TYPE_BUILTIN_SPEAKER,999),999))
 }
}
