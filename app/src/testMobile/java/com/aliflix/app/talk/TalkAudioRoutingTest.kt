package com.aliflix.app.talk

import android.media.AudioDeviceInfo.*
import org.junit.Assert.*
import org.junit.Test

class TalkAudioRoutingTest {
 @Test fun ordinaryResponsesNeverActivateThePhoneCallMixer(){
  for(type in listOf(null,TYPE_BUILTIN_SPEAKER,TYPE_BUILTIN_EARPIECE,TYPE_WIRED_HEADSET,TYPE_USB_HEADSET,TYPE_BLUETOOTH_A2DP))
   assertFalse(talkNeedsCommunicationMode(type))
 }
 @Test fun anExplicitCommunicationHeadsetRetainsItsMicrophoneRoute(){
  assertTrue(talkNeedsCommunicationMode(TYPE_BLUETOOTH_SCO))
  assertTrue(talkNeedsCommunicationMode(TYPE_BLE_HEADSET))
 }
}
