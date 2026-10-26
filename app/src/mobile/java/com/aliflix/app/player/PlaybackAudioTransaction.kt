package com.aliflix.app.player

/** An unplayed choice must never replace the last working audio preference. */
internal class PlaybackAudioTransaction<T> {
    var confirmed: T? = null
        private set
    var pending: T? = null
        private set
    private var retries = 0

    fun select(choice: T) { pending = choice; retries = 0 }
    fun seek() { retries = 0 }
    fun confirm(playing: T): Boolean {
        if (pending != null && pending != playing) return false
        val changed = pending != null
        confirmed = playing
        pending = null
        return changed
    }
    fun retry(): Boolean = (retries++ == 0)
    fun rollback(): T? = confirmed?.takeIf { pending != null && it != pending }?.also { pending = null }
    fun reset() { confirmed = null; pending = null; retries = 0 }
}
