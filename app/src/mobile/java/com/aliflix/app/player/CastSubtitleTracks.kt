package com.aliflix.app.player

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack

/** Keep Media3 identity/configuration intact when attaching receiver-fetchable WebVTT. */
internal fun withCastSubtitles(queueItem: MediaQueueItem, request: NativePlaybackRequest, subtitleUrl: String): MediaQueueItem {
    val mediaInfo = queueItem.media
    if (request.subtitlesVtt.isNotBlank() && mediaInfo != null) {
        val trackId = 1L
        val castSubtitleTrack = MediaTrack.Builder(trackId, MediaTrack.TYPE_TEXT)
            .setName(request.subtitleLabel)
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .setContentId(subtitleUrl)
            .setContentType("text/vtt")
            .setLanguage(request.subtitleLanguage)
            .build()
        val updatedMediaInfo = MediaInfo.Builder(mediaInfo.contentId)
            .setStreamType(mediaInfo.streamType)
            .setContentType(mediaInfo.contentType)
            .setMetadata(mediaInfo.metadata)
            .setStreamDuration(mediaInfo.streamDuration)
            .apply { mediaInfo.customData?.let { setCustomData(it) } }
            .setMediaTracks(listOf(castSubtitleTrack))
            .build()
        return MediaQueueItem.Builder(updatedMediaInfo)
            .apply { queueItem.customData?.let { setCustomData(it) } }
            .setActiveTrackIds(longArrayOf(trackId))
            .setAutoplay(queueItem.autoplay)
            .setPreloadTime(queueItem.preloadTime)
            .setStartTime(queueItem.startTime)
            .build()
    }
    return queueItem
}
