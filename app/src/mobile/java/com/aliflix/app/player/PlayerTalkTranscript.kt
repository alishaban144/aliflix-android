package com.aliflix.app.player

import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TalkTranscript(val cues: List<SubtitleCue>, val edition: String, val source: String)

/** Read-only retrieval. Talk must not depend on whether captions are displayed, and must
 * never enable captions, replace their selected edition or move the playback clock. */
internal class PlayerTalkTranscript {
    private val mutex = Mutex()
    private var owner = ""
    private var cached: TalkTranscript? = null

    suspend fun prepare(requestId: String, selection: PlaybackSelection, language: String,
        tracks: List<SubtitleTrack>, durationMs: Long, stillCurrent: () -> Boolean): TalkTranscript? = mutex.withLock {
        if (owner != requestId) { owner = requestId; cached = null }
        cached?.let { return@withLock it }
        val repository = SubdlSubtitleRepository()
        val result = try {
            AutomaticSubtitleLoader(
                sourceTracks = { tracks },
                searchTracks = { repository.search(selection, language).getOrThrow() },
                download = { repository.download(it, selection).getOrThrow() },
            ).load(selection, language, durationMs, NativePlaybackService.selectedVideoFrameRate(), stillCurrent)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (!stillCurrent() || owner != requestId) return@withLock null
        result?.let { TalkTranscript(it.cues, it.track.id, "retrieved_timed_captions").also { cached = it } }
    }
}
