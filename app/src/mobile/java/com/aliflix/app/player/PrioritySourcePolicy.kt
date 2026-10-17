package com.aliflix.app.player

import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.hasSourceEditions

/** Priority controls ordering. Exhausted, unavailable or failed sources permit
 * bounded automatic recovery; a person never has to choose individual mirrors. */
internal class ProviderItemUnavailable(val provider:PlaybackProvider):Exception("${provider.displayName}: title unavailable")
internal fun mayAutomaticallyLeaveSource(provider:PlaybackProvider,error:Throwable):Boolean = true
