package com.aliflix.app.player

/** One monotonic timeline per launch. Never logs stream URLs or user identifiers. */
internal object PlaybackStartupTiming {
    private var started = 0L
    private val stages = mutableSetOf<String>()
    fun begin(tap: Long) { started = tap; stages.clear(); mark("play_tap") }
    fun mark(stage: String) {
        if (!com.aliflix.app.BuildConfig.DEBUG || started == 0L || !stages.add(stage)) return
        android.util.Log.d("AliflixStartup", "$stage elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
    }
}
