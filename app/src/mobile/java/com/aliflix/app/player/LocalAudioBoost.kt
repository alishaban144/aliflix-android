package com.aliflix.app.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer

internal interface BoostEffect {
    fun gain(value: Int)
    fun enable(value: Boolean): Boolean
    fun release()
}

private class AndroidBoostEffect(session: Int) : BoostEffect {
    private val native = LoudnessEnhancer(session)
    private var released = false
    init {
        try {
            native.setControlStatusListener { _, _ -> reportStatus() }
            native.setEnableStatusListener { _, _ -> reportStatus() }
        } catch (error: RuntimeException) {
            native.release()
            throw error
        }
    }
    private fun reportStatus() {
        if (!released) runCatching {
            AudioBoostStatus.update(if (native.hasControl() && native.enabled)
                AudioBoostAvailability.ACTIVE else AudioBoostAvailability.UNAVAILABLE)
        }
    }
    override fun gain(value: Int) = native.setTargetGain(value)
    override fun enable(value: Boolean): Boolean =
        native.setEnabled(value) == AudioEffect.SUCCESS && native.enabled == value && native.hasControl()
    override fun release() { released = true; native.release() }
}

/** One service-owned effect, strictly scoped to the local Media3 audio session. */
internal class LocalAudioBoost(
    private val factory: (Int) -> BoostEffect = ::AndroidBoostEffect,
    private val publish: (AudioBoostAvailability) -> Unit = AudioBoostStatus::update,
) {
    private var closed = false
    private var effect: BoostEffect? = null
    private var session = 0
    private var attemptedSession = 0

    fun update(enabled: Boolean, audioSession: Int, remote: Boolean, routeChanged: Boolean = false) {
        if (closed) return
        if (!enabled || remote || audioSession <= 0) {
            release()
            publish(if (!enabled) AudioBoostAvailability.OFF else if (remote) AudioBoostAvailability.UNAVAILABLE else AudioBoostAvailability.WAITING)
            return
        }
        if (session == audioSession && !routeChanged && effect != null) return
        if (attemptedSession == audioSession && !routeChanged && effect == null) return
        release()
        attemptedSession = audioSession
        try {
            val created = factory(audioSession)
            effect = created
            check(created.enable(false))
            created.gain(TARGET_GAIN_MB)
            check(created.enable(true))
            session = audioSession
            publish(AudioBoostAvailability.ACTIVE)
        } catch (_: RuntimeException) {
            closeEffect()
            publish(AudioBoostAvailability.UNAVAILABLE)
        }
    }

    private fun closeEffect() {
        effect?.let { runCatching { it.enable(false) }; runCatching { it.release() } }
        effect = null
        session = 0
    }

    fun release() {
        closeEffect()
        attemptedSession = 0
    }

    fun close() { closed = true; release(); publish(AudioBoostAvailability.OFF) }

    companion object { const val TARGET_GAIN_MB = 954 }
}
