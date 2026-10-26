package com.aliflix.app.talk

import android.media.AudioAttributes
import android.media.AudioDeviceInfo

/** Response speech must not inherit minimum call volume or telephony routing. */
internal fun talkAudioAttributes():AudioAttributes=AudioAttributes.Builder()
 .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal fun talkNeedsCommunicationMode(type:Int?):Boolean=
 type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||type==AudioDeviceInfo.TYPE_BLE_HEADSET
