package com.aliflix.app.talk

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Matching VoIP usage, capture and mode supply the vendor's far-end reference.
 * Activity volume keys follow this stream; system volume is never rewritten. */
internal fun talkAudioAttributes():AudioAttributes=AudioAttributes.Builder()
 .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal fun talkPreferredCommunicationType(available:Set<Int>,current:Int?):Int? {
 val priority=listOf(AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
  AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_USB_DEVICE)
 val external=available-setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
 if(current in external&&current in available)return current
 return priority.firstOrNull{it in external}?:external.sorted().firstOrNull()
  ?:AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.takeIf{it in available}
  ?:current?.takeIf{it in available}
  ?:available.sorted().firstOrNull()
}

/** One process-owned audio lease. Reserve focus while connecting, configure
 * routing immediately BEFORE I/O, and release only this app's requests. An
 * observed previous mode/device is not a lease that this app may recreate. */
internal class TalkAudioLease(context:Context,
 private val onFocus:(TalkAudioLease,Int)->Unit,
 private val onRoute:(TalkAudioLease,AudioDeviceInfo?)->Unit,
 private val onUnavailable:(TalkAudioLease)->Unit) : AutoCloseable {
 companion object {
  private val ownership=Any();private var owner:TalkAudioLease?=null
  @Volatile private var releasedAt=0L
  suspend fun awaitOwnRelease(context:Context){
   val manager=context.getSystemService(AudioManager::class.java)
   val release=releasedAt
   while(release>0&&releasedAt==release&&android.os.SystemClock.elapsedRealtime()-release<600&&manager.mode!=AudioManager.MODE_NORMAL)kotlinx.coroutines.delay(10)
  }
 }
 private val manager=context.getSystemService(AudioManager::class.java)
 private val executor=context.mainExecutor
 private val handler=Handler(Looper.getMainLooper())
 private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
  .setAudioAttributes(talkAudioAttributes()).setOnAudioFocusChangeListener({value->if(held)onFocus(this,value)},handler).build()
 var held=false;private set
 private var activated=false
 private var modeRequested=false
 private var deviceRequested=false
 private var devicesRegistered=false
 private var communicationRegistered=false
 private var oldLegacySpeaker=false
 private var requestedLegacySpeaker:Boolean?=null
 @Volatile var outputDevice:AudioDeviceInfo?=null;private set
 private val communicationListener=if(Build.VERSION.SDK_INT>=31)AudioManager.OnCommunicationDeviceChangedListener{device->
  if(held&&activated&&outputDevice?.id!=device?.id){outputDevice=device;onRoute(this,device)}
 }else null
 private val devices=object:AudioDeviceCallback(){
  override fun onAudioDevicesAdded(added:Array<out AudioDeviceInfo>){hardwareChanged()}
  override fun onAudioDevicesRemoved(removed:Array<out AudioDeviceInfo>){hardwareChanged()}
 }
 private fun hardwareChanged(){try{refreshRoute()}catch(_:Exception){if(held)onUnavailable(this)}}
 fun reserve():Boolean {
  if(held)return true
  // Do not take the microphone/mode from another call or communication app.
  synchronized(ownership){
   if(owner!=null&&owner!==this||manager.mode!=AudioManager.MODE_NORMAL)return false
   owner=this
  }
  held=runCatching{manager.requestAudioFocus(focus)}.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  if(!held)synchronized(ownership){if(owner===this)owner=null}
  return held
 }
 fun activate(){
  check(held){"Audio focus unavailable"}
  if(activated)return
  try{
   check(manager.mode==AudioManager.MODE_NORMAL){"Another communication session is active"}
   manager.mode=AudioManager.MODE_IN_COMMUNICATION;modeRequested=true
   activated=true
   refreshRoute()
   if(Build.VERSION.SDK_INT>=31){manager.addOnCommunicationDeviceChangedListener(executor,requireNotNull(communicationListener));communicationRegistered=true}
   manager.registerAudioDeviceCallback(devices,handler);devicesRegistered=true
  }catch(error:Exception){close();throw error}
 }
 private fun refreshRoute(){
  if(!held||!activated)return
  if(Build.VERSION.SDK_INT>=31){
   val choices=manager.availableCommunicationDevices
   val type=talkPreferredCommunicationType(choices.map{it.type}.toSet(),manager.communicationDevice?.type)
   val selected=choices.firstOrNull{it.type==type}?:error("No communication route available")
   if(outputDevice?.id==selected.id&&deviceRequested)return
   check(manager.setCommunicationDevice(selected)){"Audio route unavailable"}
   deviceRequested=true;outputDevice=selected;onRoute(this,selected)
  }else{
   val external=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any{it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_BLUETOOTH_SCO)}
   val speaker=!external
   @Suppress("DEPRECATION")
   if(requestedLegacySpeaker==null){oldLegacySpeaker=manager.isSpeakerphoneOn}
   @Suppress("DEPRECATION")
   if(requestedLegacySpeaker!=speaker){manager.isSpeakerphoneOn=speaker;requestedLegacySpeaker=speaker;onRoute(this,null)}
  }
 }
 override fun close(){
  val hadFocus=held;held=false;activated=false
  if(devicesRegistered){runCatching{manager.unregisterAudioDeviceCallback(devices)};devicesRegistered=false}
  if(Build.VERSION.SDK_INT>=31&&communicationRegistered){runCatching{manager.removeOnCommunicationDeviceChangedListener(requireNotNull(communicationListener))};communicationRegistered=false}
  if(Build.VERSION.SDK_INT>=31&&deviceRequested){runCatching{manager.clearCommunicationDevice()};deviceRequested=false}
  @Suppress("DEPRECATION")
  requestedLegacySpeaker?.let{requested->runCatching{if(manager.isSpeakerphoneOn==requested)manager.isSpeakerphoneOn=oldLegacySpeaker}}
  requestedLegacySpeaker=null;outputDevice=null
  if(modeRequested){modeRequested=false;releasedAt=android.os.SystemClock.elapsedRealtime();runCatching{manager.mode=AudioManager.MODE_NORMAL}}
  if(hadFocus)runCatching{manager.abandonAudioFocusRequest(focus)}
  synchronized(ownership){if(owner===this)owner=null}
 }
}
