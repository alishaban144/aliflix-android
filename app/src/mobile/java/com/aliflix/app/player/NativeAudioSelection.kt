package com.aliflix.app.player

import android.os.SystemClock
import androidx.media3.common.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AudioSelectionState(val pending: String? = null, val failed: String? = null, val selected: String? = null)
internal fun nativeAudioKey(format: Format): String = listOf(format.id, format.language, format.label,
    format.sampleMimeType, format.codecs, format.channelCount, format.sampleRate).joinToString("|")

/** HLS declarations and decoder formats enrich IDs, codec strings and unknown
 * channel/rate fields differently. Verify the soundtrack identity, not byte
 * equality of these two different metadata representations. */
internal fun nativeAudioDecoderMatches(expected:Format,actual:Format):Boolean {
    if(expected.sampleMimeType!=null&&actual.sampleMimeType!=null&&expected.sampleMimeType!=actual.sampleMimeType)return false
    if(expected.channelCount>0&&actual.channelCount>0&&expected.channelCount!=actual.channelCount)return false
    if(expected.sampleRate>0&&actual.sampleRate>0&&expected.sampleRate!=actual.sampleRate)return false
    fun language(value:String?)=value?.takeIf{it.isNotBlank()&&it!="und"}?.let{canonicalSubtitleLanguageCode(it)}
    val a=language(expected.language);val b=language(actual.language)
    if(a!=null&&b!=null)return a==b
    if(!expected.label.isNullOrBlank()&&!actual.label.isNullOrBlank())return expected.label==actual.label
    return !expected.id.isNullOrBlank()&&expected.id==actual.id
}

/** A service-owned transaction survives sheets/activity recreation and commits only the applied choice. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NativeAudioSelection(
    private val player: Player,
    private val apply: (TrackSelectionOverride) -> Unit,
    private val decoded: () -> Pair<Long, Long>,
    private val inputFormat: () -> Format?,
    private val persist: (String?, String?) -> Unit,
) {
    private val mutable = MutableStateFlow(AudioSelectionState())
    val state = mutable.asStateFlow()
    private var choice: TrackSelectionOverride? = null
    private var previous: TrackSelectionOverride? = null
    private var started = 0L
    private var baseline = 0L to 0L
    private fun selected(): TrackSelectionOverride? = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
        (0 until g.length).firstOrNull(g::isTrackSelected)?.let { TrackSelectionOverride(g.mediaTrackGroup, it) }
    }
    fun select(group: TrackGroup, index: Int): Boolean {
        val mapped = nativeAudioChoice(player.currentTracks, group, index)
        if (mapped == null) { mutable.value = mutable.value.copy(failed = "Audio unavailable"); return false }
        if (choice == mapped) return true
        if (choice == null && selected() == mapped && player.playbackState == Player.STATE_READY) { mutable.value = AudioSelectionState(selected = nativeAudioKey(mapped.mediaTrackGroup.getFormat(mapped.trackIndices.first()))); return true }
        if (choice == null) previous = selected()
        choice = mapped; started = SystemClock.elapsedRealtime(); baseline = decoded()
        mutable.value = AudioSelectionState(pending = nativeAudioKey(mapped.mediaTrackGroup.getFormat(mapped.trackIndices.first())),
            selected = mutable.value.selected)
        apply(mapped)
        return true
    }
    fun tick() {
        val pending = choice ?: return
        val format = pending.mediaTrackGroup.getFormat(pending.trackIndices.first())
        val evidence = decoded()
        val decodedNewAudio = evidence.second > 0 && (evidence.first != baseline.first || evidence.second > baseline.second)
        val decoderMatches = inputFormat()?.let { nativeAudioDecoderMatches(format,it) } == true
        if (selected() == pending && player.playbackState == Player.STATE_READY &&
            (!player.playWhenReady || (decoderMatches && decodedNewAudio) ||
                (decoderMatches && SystemClock.elapsedRealtime() - started >= 800))) {
            persist(format.language, format.label); choice = null; previous = null
            mutable.value = AudioSelectionState(selected = nativeAudioKey(format)); return
        }
        if (player.playerError != null || SystemClock.elapsedRealtime() - started >= 12000) {
            val rollback = previous
            choice = null; previous = null
            if (rollback != null && player.currentTracks.groups.any { it.mediaTrackGroup == rollback.mediaTrackGroup }) apply(rollback)
            mutable.value = AudioSelectionState(failed = "Audio unavailable", selected = rollback?.let { nativeAudioKey(it.mediaTrackGroup.getFormat(it.trackIndices.first())) })
        }
    }
    fun reset() { choice = null; previous = null; mutable.value = AudioSelectionState() }
}
