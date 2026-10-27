package com.aliflix.app.talk

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor

/** Creation alone is not evidence that the effect controls or processes I/O. */
internal fun enableTalkPlatformEcho(session:Int):AcousticEchoCanceler? {
    if(!AcousticEchoCanceler.isAvailable())return null
    var effect:AcousticEchoCanceler?=null
    return try {
        val created=AcousticEchoCanceler.create(session)?:return null
        effect=created
        if(created.hasControl()&&created.setEnabled(true)==AudioEffect.SUCCESS&&created.enabled)created
        else {runCatching{created.release()};null}
    }catch(_:Exception){runCatching{effect?.release()};null}
}

internal fun enableTalkPlatformNoise(session:Int):NoiseSuppressor? {
    if(!NoiseSuppressor.isAvailable())return null
    var effect:NoiseSuppressor?=null
    return try {
        val created=NoiseSuppressor.create(session)?:return null
        effect=created
        if(created.hasControl()&&created.setEnabled(true)==AudioEffect.SUCCESS&&created.enabled)created
        else {runCatching{created.release()};null}
    }catch(_:Exception){runCatching{effect?.release()};null}
}
