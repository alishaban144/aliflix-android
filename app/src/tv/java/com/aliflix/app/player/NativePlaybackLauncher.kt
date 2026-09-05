package com.aliflix.app.player

import android.app.Activity

internal object NativePlaybackLauncher {
    fun ownsStream(url: String?): Boolean = false

    fun launch(activity: Activity, request: NativePlaybackRequest) = Unit
}
