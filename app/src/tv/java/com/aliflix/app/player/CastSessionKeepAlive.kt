package com.aliflix.app.player

import android.content.Context

/** Cast background playback is intentionally mobile-only. */
internal object CastSessionKeepAlive {
    fun setPlaybackCommandHandler(handler: ((CastPlaybackCommand) -> Unit)?) = Unit

    fun start(
        context: Context,
        title: String,
        subtitle: String?,
        playing: Boolean,
    ): Boolean = false

    fun update(
        context: Context,
        title: String,
        subtitle: String?,
        playing: Boolean,
    ) = Unit

    fun stop(context: Context) = Unit
}
