@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

class OfflineDownloadService : DownloadService(2104, 1_000, "video-downloads", com.aliflix.app.R.string.app_name, 0) {
    private var cpuLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) = updateLocks()
        override fun onIdle(manager: DownloadManager) = updateLocks()
        override fun onDownloadsPausedChanged(manager: DownloadManager, paused: Boolean) = updateLocks()
    }
    override fun onCreate() {
        super.onCreate()
        downloadManager.addListener(listener)
        updateLocks()
    }
    private fun updateLocks() {
        val active = !downloadManager.downloadsPaused && downloadManager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }
        if (active) {
            if (cpuLock?.isHeld != true) cpuLock = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:downloads").apply { acquire() }
            if (wifiLock?.isHeld != true) wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:downloads").apply { acquire() }
        } else releaseLocks()
    }
    private fun releaseLocks() {
        cpuLock?.takeIf { it.isHeld }?.release(); cpuLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }
    override fun onDestroy() {
        downloadManager.removeListener(listener)
        releaseLocks()
        super.onDestroy()
    }
    override fun getDownloadManager(): DownloadManager = OfflineDownloads.get(this).manager
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 2105)
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!.putExtra("openDownloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 2104, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return DownloadNotificationHelper(this, "video-downloads").buildProgressNotification(this,
            android.R.drawable.stat_sys_download, pending, "Downloads", downloads, notMetRequirements)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloadManager.pauseDownloads()
        stopSelf()
    }
}
