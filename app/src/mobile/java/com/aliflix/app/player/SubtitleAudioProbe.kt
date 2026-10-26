package com.aliflix.app.player

internal data class TimedSpeechWord(val text: String, val seconds: Double, val sample: Int)
internal fun subtitleMediaSeconds(presentationTimeUs: Long, streamOffsetUs: Long, frame: Int, sampleRate: Int): Double =
    (presentationTimeUs - streamOffsetUs) / 1e6 + frame / sampleRate.toDouble()
