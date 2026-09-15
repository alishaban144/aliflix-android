@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

class OfflineDownloadService : DownloadService(2104, 1_000, "video-downloads", com.aliflix.app.R.string.app_name, 0) {
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
