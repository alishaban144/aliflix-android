@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import com.aliflix.app.player.*
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.data.playbackProgressKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal data class SavedDownload(val download: Download, val playback: NativePlaybackRequest, val quality: String, val estimate: Long,
    val downloadedBytes: Long = download.bytesDownloaded, val percent: Float = download.percentDownloaded) {
    val id get() = download.request.id
    val selection get() = nativeSelection(playback.selectionJson)
}

internal class OfflineDownloads private constructor(val context: Context) {
    val preferences = context.getSharedPreferences("offline-downloads", Context.MODE_PRIVATE)
    val directory = File(context.noBackupFilesDir, "downloads").apply { mkdirs() }
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(directory, NoOpCacheEvictor(), database)
    private val executor = Executors.newFixedThreadPool(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    val entries = MutableStateFlow<List<SavedDownload>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    private val lock = Any()
    private var pendingBytes = 0L
    private val refreshLock = kotlinx.coroutines.sync.Mutex()
    private val index = DefaultDownloadIndex(database)
    val manager: DownloadManager = DownloadManager(context, index, DownloaderFactory { request ->
        val native = NativePlaybackRequest.fromJson(JSONObject(String(request.data, Charsets.UTF_8)).getString("playback"))
        DefaultDownloaderFactory(cacheFactory(native), executor).createDownloader(request)
    })
    val limitBytes get() = preferences.getInt("limitGb", 50).coerceIn(1, 200) * DOWNLOAD_GB
    val preferredHeight get() = preferences.getInt("quality", 720)

    init {
        manager.maxParallelDownloads = 2
        manager.minRetryCount = 3
        manager.requirements = Requirements(Requirements.NETWORK)
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) { refresh() }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) {
                    val quota = generateSequence(finalException as Throwable?) { it.cause }.any { it is DownloadStorageException }
                    val reason = if (quota) "Free space or increase the download limit in Settings." else "Download interrupted. Retry when connected."
                    preferences.edit().putString("error:${download.request.id}", reason).apply()
                    message.value = reason
                }
                refresh()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                preferences.edit().remove("error:${download.request.id}").apply(); refresh()
            }
        })
        // Media3's index restores completed and interrupted downloads after process death.
        scope.launch {
            while (isActive) {
                withContext(Dispatchers.Main.immediate) {
                    val live = manager.currentDownloads.associateBy { it.request.id }
                    entries.value = entries.value.map { saved -> live[saved.id]?.let {
                        saved.copy(download = it, downloadedBytes = it.bytesDownloaded, percent = it.percentDownloaded)
                    } ?: saved }
                }
                delay(250)
            }
        }
    }

    private fun refresh() { scope.launch { refreshLock.withLock {
        val values = runCatching { index.getDownloads().use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val d = cursor.download
                runCatching {
                    val json = JSONObject(String(d.request.data, Charsets.UTF_8))
                    SavedDownload(d, NativePlaybackRequest.fromJson(json.getString("playback")), json.optString("quality"), json.optLong("estimate"))
                }.getOrNull()?.let(::add)
            }
        } } }.getOrDefault(emptyList())
        withContext(Dispatchers.Main.immediate) {
            val live = manager.currentDownloads.associateBy { it.request.id }
            entries.value = values.map { saved -> live[saved.id]?.let {
                saved.copy(download = it, downloadedBytes = it.bytesDownloaded, percent = it.percentDownloaded)
            } ?: saved }.sortedByDescending { it.download.startTimeMs }
        }
    } } }

    fun completed(selection: PlaybackSelection): SavedDownload? = entries.value.firstOrNull {
        it.id == playbackProgressKey(selection) && it.download.state == Download.STATE_COMPLETED
    }

    fun offlineFactory(): CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)

    private fun cacheFactory(request: NativePlaybackRequest): CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(downloadHttpFactory(request)).setCacheWriteDataSinkFactory {
            val delegate = CacheDataSink(cache, 1_048_576)
            object : DataSink {
                private var outstanding = 0L
                override fun open(dataSpec: DataSpec) = delegate.open(dataSpec)
                override fun write(buffer: ByteArray, offset: Int, length: Int) = synchronized(lock) {
                    if (!downloadFits(cache.cacheSpace, pendingBytes, length.toLong(), limitBytes, directory.usableSpace)) {
                        handler.post { manager.setStopReason(null, 2) }
                        message.value = "Free space or increase the download limit in Settings."
                        throw DownloadStorageException()
                    }
                    val before = cache.cacheSpace
                    pendingBytes += length
                    outstanding += length
                    try { delegate.write(buffer, offset, length) }
                    finally {
                        val committed = (cache.cacheSpace - before).coerceAtLeast(0)
                        pendingBytes = (pendingBytes - committed).coerceAtLeast(0)
                        outstanding = (outstanding - committed).coerceAtLeast(0)
                    }
                }
                override fun close() = synchronized(lock) {
                    try { delegate.close() } finally {
                        pendingBytes = (pendingBytes - outstanding).coerceAtLeast(0)
                        outstanding = 0
                    }
                }
            }
        }

    suspend fun enqueue(requests: List<DownloadRequest>) {
        check(requests.isNotEmpty())
        for (request in requests) {
            val existing = withContext(Dispatchers.IO) { index.getDownload(request.id) }
            check(existing?.state != Download.STATE_COMPLETED) { "Already downloaded. Delete the saved video to change quality." }
            if (existing != null && (existing.request.uri != request.uri || existing.request.streamKeys != request.streamKeys)) {
                remove(request.id)
                withTimeout(15_000) {
                    while (withContext(Dispatchers.IO) { index.getDownload(request.id) } != null) delay(100)
                }
            }
        }
        val reserved = entries.value.filter { it.id !in requests.map { r -> r.id } && it.download.state !in setOf(Download.STATE_COMPLETED, Download.STATE_REMOVING) }
            .sumOf { (it.estimate - it.download.bytesDownloaded).coerceAtLeast(0) }
        val incoming = requests.sumOf { JSONObject(String(it.data, Charsets.UTF_8)).optLong("estimate").coerceAtLeast(0) }
        val fits = withContext(Dispatchers.IO) { downloadFits(cache.cacheSpace, reserved, incoming, limitBytes, directory.usableSpace) }
        check(fits) { "Free space or increase the download limit in Settings." }
        requests.forEach {
            preferences.edit().remove("error:${it.id}").apply()
            // Large caption payloads stay in-process instead of crossing Binder's 1 MB limit.
            manager.addDownload(it, 0)
        }
        DownloadService.sendResumeDownloads(context, OfflineDownloadService::class.java, false)
    }

    fun pause(id: String) {
        manager.setStopReason(id, 1)
    }
    fun resume(item: SavedDownload) {
        preferences.edit().remove("error:${item.id}").apply()
        manager.setStopReason(item.id, 0)
        DownloadService.sendResumeDownloads(context, OfflineDownloadService::class.java, false)
    }
    fun remove(id: String) = DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, id, false)

    companion object {
        @Volatile private var instance: OfflineDownloads? = null
        fun get(context: Context): OfflineDownloads = instance ?: synchronized(this) {
            instance ?: OfflineDownloads(context.applicationContext).also { instance = it }
        }
    }
}

internal class DownloadStorageException : IOException("Download storage limit reached")

internal fun downloadHttpFactory(request: NativePlaybackRequest): DataSource.Factory {
    val headers = buildMap {
        if (request.referer.isNotBlank()) {
            put("Referer", request.referer)
            val uri = Uri.parse(request.referer)
            put("Origin", "${uri.scheme}://${uri.authority}")
        }
    }
    val http = DefaultHttpDataSource.Factory().setUserAgent(request.userAgent)
        .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setDefaultRequestProperties(headers)
    return ResolvingDataSource.Factory(http) { spec ->
        if (request.cookie.isNotBlank() && spec.uri.host == Uri.parse(request.url).host)
            spec.withRequestHeaders(spec.httpRequestHeaders + ("Cookie" to request.cookie)) else spec
    }
}
