package com.aliflix.app.player

import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.hasSourceEditions

/** Only a successful catalogue response that proves absence permits automatic
 * fallback. A broken adapter, network timeout or challenge proves nothing about
 * whether the working website has the requested video. Explicit source choices
 * in the player remain available to the user. */
internal class ProviderItemUnavailable(val provider:PlaybackProvider):Exception("${provider.displayName}: title unavailable")

internal fun mayAutomaticallyLeaveSource(provider:PlaybackProvider,error:Throwable):Boolean =
    !provider.hasSourceEditions || error is ProviderItemUnavailable && error.provider==provider
