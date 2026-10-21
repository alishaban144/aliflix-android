package com.aliflix.app

import android.app.Application
import android.os.StrictMode

open class AliflixApplication : Application() {
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
