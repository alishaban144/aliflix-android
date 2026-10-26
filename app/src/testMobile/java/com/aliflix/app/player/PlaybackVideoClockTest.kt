package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class PlaybackVideoClockTest {
    @Test fun ptsCadenceDistinguishesPalAndFilmWithoutUsingTheWallClock() {
        for (fps in listOf(23.976, 24.0, 25.0, 29.97, 60.0)) {
            val clock = PlaybackVideoClock()
            repeat(60) { clock.frame((it * 1_000_000 / fps).roundToLong()) }
            assertEquals(fps, clock.frameRate.toDouble(), .003)
        }
    }
    @Test fun sourceResetAndSeekGapsCannotReuseAnOldCadence() {
        val clock = PlaybackVideoClock()
        repeat(30) { clock.frame(it * 40_000L) }
        assertEquals(25f, clock.frameRate, .01f)
        clock.frame(90_000_000)
        assertEquals(-1f, clock.frameRate, .01f)
        repeat(30) { clock.frame(90_000_000 + it * 41_667L) }
        assertEquals(24f, clock.frameRate, .01f)
        clock.reset()
        assertEquals(-1f, clock.frameRate, .01f)
    }
    @Test fun duplicatesDoNotInventFramesAndVariableCadenceRemainsUnknown() {
        val clock = PlaybackVideoClock()
        repeat(30) { clock.frame(0) }
        assertEquals(-1f, clock.frameRate, .01f)
        var time = 0L
        repeat(60) { time += if (it % 2 == 0) 40_000 else 60_000; clock.frame(time) }
        assertEquals(-1f, clock.frameRate, .01f)
    }
}
