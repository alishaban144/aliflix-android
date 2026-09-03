package com.aliflix.app.player

import android.content.Context

/** Cast background playback is intentionally mobile-only. */
internal object CastSessionKeepAlive {
    fun start(context: Context): Boolean = false
    fun stop(context: Context) = Unit
}
