package com.aliflix.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Shared bytes, never shared credentials: each candidate retains its own upstream factory. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StartupStreamCache {
    private var cache: SimpleCache? = null
    @Synchronized fun factory(context: Context, upstream: androidx.media3.datasource.DataSource.Factory): CacheDataSource.Factory {
        val app = context.applicationContext
        val disk = cache ?: SimpleCache(java.io.File(app.cacheDir, "playback-streams"),
            LeastRecentlyUsedCacheEvictor(128L * 1024 * 1024), StandaloneDatabaseProvider(app)).also { cache = it }
        return CacheDataSource.Factory().setCache(disk).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    suspend fun awaitPlayable(context: Context, request: NativePlaybackRequest) = withTimeout(8_000) {
        val origin = java.net.URI(request.referer).let { "${it.scheme}://${it.rawAuthority}" }
        val http = DefaultHttpDataSource.Factory().setUserAgent(request.userAgent)
            .setConnectTimeoutMs(4_000).setReadTimeoutMs(4_000)
            .setDefaultRequestProperties(mapOf("Referer" to request.referer, "Origin" to origin))
        val scoped = ResolvingDataSource.Factory(http) { spec ->
            request.resolveStreamSpec(spec)
        }
        val player = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory(context, DefaultDataSource.Factory(context, scoped))))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 2_000, 250, 500).build()).build()
        try {
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            player.setMediaItem(MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build(), request.positionMs)
            player.prepare()
            while (player.playbackState != Player.STATE_READY) {
                player.playerError?.let { throw it }
                delay(50)
            }
            check(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }) { "No playable audio" }
        } finally { player.release() }
    }
}
