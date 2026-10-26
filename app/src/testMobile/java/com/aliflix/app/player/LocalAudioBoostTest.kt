package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class LocalAudioBoostTest {
    private class Effect : BoostEffect {
        val events = mutableListOf<String>()
        override fun gain(value: Int) { events += "gain:$value" }
        override fun enable(value: Boolean): Boolean { events += "enabled:$value"; return true }
        override fun release() { events += "release" }
    }
    @Test fun onlyOneGainIsAppliedAndToggleIsImmediate() {
        val effects = mutableListOf<Effect>()
        val boost = LocalAudioBoost({ Effect().also(effects::add) }, {})
        boost.update(false, 42, false)
        assertTrue(effects.isEmpty())
        boost.update(true, 42, false)
        repeat(5) { boost.update(true, 42, false) }
        assertEquals(1, effects.size)
        assertEquals(listOf("enabled:false", "gain:954", "enabled:true"), effects.single().events)
        boost.update(false, 42, false)
        assertEquals(listOf("enabled:false", "release"), effects.single().events.takeLast(2))
    }
    @Test fun sessionAndRouteChangesReleaseOldEffectBeforeCreatingAnother() {
        val effects = mutableListOf<Effect>()
        val ids = mutableListOf<Int>()
        val boost = LocalAudioBoost({ ids += it; Effect().also(effects::add) }, {})
        boost.update(true, 3, false)
        boost.update(true, 4, false)
        boost.update(true, 4, false, routeChanged = true)
        assertEquals(listOf(3, 4, 4), ids)
        assertEquals("release", effects[0].events.last())
        assertEquals("release", effects[1].events.last())
        boost.release()
        assertEquals("release", effects[2].events.last())
    }
    @Test fun castingInvalidSessionsAndUnsupportedEffectsRetainNormalAudio() {
        val states = mutableListOf<AudioBoostAvailability>()
        var calls = 0
        val boost = LocalAudioBoost({ calls++; throw UnsupportedOperationException() }, states::add)
        boost.update(true, 0, false)
        boost.update(true, 44, true)
        assertEquals(0, calls)
        repeat(4) { boost.update(true, 44, false) }
        assertEquals(1, calls)
        assertEquals(AudioBoostAvailability.UNAVAILABLE, states.last())
        boost.update(false, 44, false)
        assertEquals(AudioBoostAvailability.OFF, states.last())
    }
    @Test fun rejectedEnablingReleasesFailedEffect() {
        var released = false
        val boost = LocalAudioBoost({ object : BoostEffect {
            override fun gain(value: Int) {}
            override fun enable(value: Boolean) = !value
            override fun release() { released = true }
        } }, {})
        boost.update(true, 9, false)
        assertTrue(released)
    }
    @Test fun destroyedControllerIgnoresLateSessionAndRouteCallbacks() {
        var creations = 0
        val states = mutableListOf<AudioBoostAvailability>()
        val effect = Effect()
        val boost = LocalAudioBoost({ creations++; effect }, states::add)
        boost.update(true, 42, false)
        boost.close()
        boost.update(true, 42, false, routeChanged = true)
        boost.update(true, 43, false)
        assertEquals(1, creations)
        assertEquals("release", effect.events.last())
        assertEquals(AudioBoostAvailability.OFF, states.last())
    }

}
