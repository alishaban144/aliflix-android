package com.aliflix.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioBoostAvailability { OFF, WAITING, ACTIVE, UNAVAILABLE }

object AudioBoostStatus {
    private val current = MutableStateFlow(AudioBoostAvailability.OFF)
    val availability = current.asStateFlow()
    fun update(value: AudioBoostAvailability) { current.value = value }
}
