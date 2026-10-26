package com.aliflix.app.downloads

internal const val DOWNLOAD_GB = 1_000_000_000L
internal const val DOWNLOAD_DISK_RESERVE = 100_000_000L

internal fun downloadFits(used: Long, reserved: Long, incoming: Long, limit: Long, free: Long): Boolean =
    incoming >= 0 && used >= 0 && reserved >= 0 &&
        used <= limit && reserved <= limit - used && incoming <= limit - used - reserved &&
        free >= DOWNLOAD_DISK_RESERVE && incoming <= free - DOWNLOAD_DISK_RESERVE

internal fun preferredDownloadHeight(available: List<Int>, preferred: Int): Int? =
    available.filter { it > 0 }.let { heights ->
        heights.filter { it <= preferred }.maxOrNull() ?: heights.minOrNull()
    }

internal fun downloadSize(bytes: Long): String = when {
    bytes <= 0 -> "Size unavailable"
    bytes >= DOWNLOAD_GB -> "%.1f GB".format(java.util.Locale.ROOT, bytes.toDouble() / DOWNLOAD_GB)
    else -> "%.0f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
}
