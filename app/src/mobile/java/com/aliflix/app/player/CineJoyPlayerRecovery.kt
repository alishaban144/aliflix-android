package com.aliflix.app.player

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride

/** Keeps late HLS failures inside the current player instead of re-running the catalogue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CineJoyPlayerRecovery(
    private val player: Player,
    private val handler: Handler,
    private val saveAudio: (String?, String?) -> Unit,
) : Player.Listener {
    private val audio = PlaybackAudioTransaction<TrackSelectionOverride>()
    var enabled = false
        private set
    var hasPlayed = false
        private set
    var message: String? = null
        private set
    private var generation = 0
    private var recovering = false
    private var stoppedOnFailure = false
    private var resumeAfterFailure = false
    private var bufferingSince = 0L
    private var observedPosition = 0L
    private var observedChoice: TrackSelectionOverride? = null

    fun reset(enabled: Boolean) {
        generation++
        this.enabled = enabled
        hasPlayed = false; recovering = false; stoppedOnFailure = false; resumeAfterFailure = false
        message = null; bufferingSince = 0; observedChoice = null
        audio.reset()
    }

    fun select(group: TrackGroup, index: Int): Boolean {
        if (!enabled || index !in 0 until group.length) return false
        val actual = player.currentTracks.groups.firstOrNull { it.mediaTrackGroup == group } ?: return false
        if (actual.type != C.TRACK_TYPE_AUDIO || !actual.isTrackSupported(index, true)) return false
        val choice = TrackSelectionOverride(actual.mediaTrackGroup, index)
        if (audio.confirmed == null && player.playbackState == Player.STATE_READY && player.playerError == null)
            selected()?.let(audio::confirm)
        if (player.playbackState == Player.STATE_READY) hasPlayed = true
        audio.select(choice)
        val resume = stoppedOnFailure && resumeAfterFailure
        val restartRendition = hasPlayed && selected() != choice
        val play = player.playWhenReady || resume
        generation++; recovering = false; stoppedOnFailure = false; message = null
        bufferingSince = SystemClock.elapsedRealtime(); observedChoice = null
        // Repeated rendition changes after distant seeks can leave the HLS audio
        // clock blocked on an old timestamp adjuster while reporting READY. Flush
        // that pipeline before changing audio, retaining the item and position.
        if (restartRendition) player.stop()
        apply(choice)
        // Track overrides alone do not restart an IDLE player after a source error.
        if (restartRendition || player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        if (play) player.play()
        return true
    }

    private fun selected(): TrackSelectionOverride? = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { group ->
            (0 until group.length).firstOrNull(group::isTrackSelected)?.let { TrackSelectionOverride(group.mediaTrackGroup, it) }
        }

    private fun apply(choice: TrackSelectionOverride) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(choice).build()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!enabled || !stoppedOnFailure || !playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
        generation++; recovering = false; audio.seek()
        stoppedOnFailure = false; resumeAfterFailure = false; message = null
        bufferingSince = SystemClock.elapsedRealtime()
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (!enabled || reason != Player.DISCONTINUITY_REASON_SEEK) return
        generation++; recovering = false; audio.seek(); message = null
        bufferingSince = SystemClock.elapsedRealtime(); observedChoice = null
        val resume = stoppedOnFailure && resumeAfterFailure
        stoppedOnFailure = false
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            if (resume) player.play()
        }
    }

    /** Called from the service's main-thread ticker, including while the activity is stopped. */
    fun tick() {
        if (!enabled || player.deviceInfo.playbackType != androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_LOCAL) return
        val now = SystemClock.elapsedRealtime()
        if (player.playerError != null) { if (hasPlayed) recover(); return }
        if (player.playbackState == Player.STATE_READY) {
            hasPlayed = true
            bufferingSince = 0
            val choice = selected() ?: return
            if (choice != observedChoice) {
                observedChoice = choice; observedPosition = player.currentPosition
                return
            }
            // READY plus actual progress proves the selected stream supplied samples.
            if (player.currentPosition - observedPosition >= 10_000) audio.seek()
            if (!player.playWhenReady || player.currentPosition - observedPosition >= 500) {
                if (audio.confirm(choice)) {
                    val format = choice.mediaTrackGroup.getFormat(choice.trackIndices.first())
                    saveAudio(format.language, format.label)
                }
            }
        } else if (hasPlayed && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
            if (bufferingSince == 0L) bufferingSince = now
            if (now - bufferingSince >= 60_000) recover()
        }
    }

    fun recover(manual: Boolean = false) {
        if (!enabled || recovering || (!manual && stoppedOnFailure)) return
        if (manual) { audio.seek(); stoppedOnFailure = false; message = null }
        val retry = audio.retry()
        val rollback = if (retry) null else audio.rollback()
        if (!retry && rollback == null) {
            stoppedOnFailure = true
            resumeAfterFailure = player.playWhenReady
            player.pause()
            message = "This part couldn't load. Seek to another position, choose audio, or retry."
            return
        }
        recovering = true
        val expected = generation
        handler.post {
            if (expected != generation || !enabled) return@post
            recovering = false
            val play = player.playWhenReady || manual
            if (rollback != null) {
                apply(rollback)
                message = "That audio couldn't load. Previous audio restored."
            }
            // Stop/prepare resets failed loaders and timestamp adjusters, but retains
            // the media item, selected renditions, subtitles and exact seek position.
            player.stop()
            player.prepare()
            player.playWhenReady = play
            bufferingSince = SystemClock.elapsedRealtime()
            observedChoice = null
        }
    }
}
