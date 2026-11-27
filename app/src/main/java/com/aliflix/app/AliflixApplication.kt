package com.aliflix.app

import android.app.Application
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

open class AliflixApplication : Application(), ImageLoaderFactory {
    // Mobile owns these for the process, including direct player/Talk entry.
    // Screen destruction must not detach account listeners or pending writes.
    val accountScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val playbackProviderRepository by lazy { com.aliflix.app.data.PlaybackProviderRepository(this) }
    val recommendationStore by lazy { com.aliflix.app.recommendation.RecommendationStore(this) }
    val accountServices by lazy {
        com.aliflix.app.account.createAccountServices(this, libraryStore, playbackProviderRepository,
            playbackProgressStore, recommendationStore, accountScope)
    }
    val libraryStore by lazy { com.aliflix.app.data.LibraryStore(this) }
    val playbackProgressStore by lazy { com.aliflix.app.data.PlaybackProgressStore(this) }
    val playerSettingsStore by lazy { com.aliflix.app.player.PlayerSettingsStore(this) }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "image_cache"))
                .maxSizeBytes(150L * 1024L * 1024L)
                .build()
        }
        .okHttpClient {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(200)
        .build()
    override fun onCreate() {
        super.onCreate()
        runCatching {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("java.net.preferIPv6Addresses", "false")
        }
        if (!BuildConfig.DEBUG) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }
}
