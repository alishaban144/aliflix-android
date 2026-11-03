package com.aliflix.app.player

import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

internal data class AutomaticSubtitleResult(val track: SubtitleTrack, val cues: List<SubtitleCue>, val timingDurationMs:Long=0)

internal class AutomaticSubtitleLoader(
    private val sourceTracks: suspend () -> List<SubtitleTrack>,
    private val searchTracks: suspend () -> List<SubtitleTrack>,
    private val download: suspend (SubtitleTrack) -> List<SubtitleCue>,
    private val alternateTracks: (suspend () -> List<SubtitleTrack>)? = null,
) {
    suspend fun load(selection: PlaybackSelection, language: String, durationMs: Long, frameRate: Float,
        stillCurrent: () -> Boolean, onTracks: (List<SubtitleTrack>) -> Unit = {},
        latestFrameRate: () -> Float = { frameRate }, latestDurationMs: () -> Long = { durationMs },
        preferClosestMovieEdition: Boolean = true): AutomaticSubtitleResult? {
        val attempted = hashSetOf<String>()
        val limited = hashSetOf<String>()
        val compareEditions=preferClosestMovieEdition && selection.media.type==com.aliflix.app.model.MediaType.MOVIE
        var fallback:AutomaticSubtitleResult?=null
        var fallbackGap=Double.POSITIVE_INFINITY
        val lookups=listOfNotNull(12_000L to sourceTracks,alternateTracks?.let { 12_000L to it },16_000L to searchTracks)
        for ((budget, lookup) in lookups) {
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) return null
            // Reserve time for SubDL even when several source files stall.
            var best: AutomaticSubtitleResult? = null
            var bestGap = Double.POSITIVE_INFINITY
            val result = withTimeoutOrNull(budget) {
                val tracks = try { withTimeoutOrNull(6_000) { normalizeMobileSubtitleTracks(lookup()) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                if (!stillCurrent()) return@withTimeoutOrNull null
                onTracks(tracks)
                val candidates = mobileSubtitleCandidates(tracks, language, selection.seasonNumber, selection.episodeNumber, selection.media.title)
                if (candidates.isNotEmpty() && latestFrameRate() <= 0) {
                    // HLS can omit FPS. Allow the first decoded frames to arrive
                    // while playback continues; never wait indefinitely when paused.
                    withTimeoutOrNull(1_000) {
                        while (latestFrameRate() <= 0 && stillCurrent()) delay(50)
                    }
                }
                for (track in rankCompleteSubtitleCandidates(candidates, latestFrameRate()).take(6)) {
                    val origin=runCatching { java.net.URI(directSubtitleUrl(track.downloadToken).orEmpty()).host.orEmpty() }.getOrDefault("")
                    if(origin.isNotBlank() && origin in limited)continue
                    if (!attempted.add(track.downloadToken) || !stillCurrent()) continue
                    val cues = try { withTimeoutOrNull(3_500) { download(track) }.orEmpty() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { if(error is SubtitleException && error.status==429 && origin.isNotBlank())limited+=origin;emptyList() }
                    currentCoroutineContext().ensureActive()
                    if (!stillCurrent()) return@withTimeoutOrNull null
                    if (cues.isNotEmpty() && subtitleLanguageIsPlausible(cues, language) &&
                        automaticCaptionCoversMovie(cues, selection, latestDurationMs()))
                    {
                        val duration=latestDurationMs()/1000.0
                        val candidate=AutomaticSubtitleResult(track,cues,(duration*1000).toLong().coerceAtLeast(0))
                        if(!compareEditions || duration<1800)
                            return@withTimeoutOrNull candidate
                        val end=cues.maxOf { it.endSeconds }
                        if(end>duration+60)continue
                        val gap=duration-end
                        if(gap<bestGap) { best=candidate;bestGap=gap }
                        if(gap<=300)return@withTimeoutOrNull candidate
                    }
                }
                best
            }
            if(!stillCurrent())return null
            if (result != null && (!compareEditions || result.timingDurationMs<1_800_000 || bestGap<=300)) return result
            val candidate=result ?: best
            if(candidate!=null && bestGap<fallbackGap) { fallback=candidate;fallbackGap=bestGap }
        }
        return fallback
    }
}
