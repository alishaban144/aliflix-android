package com.aliflix.app.downloads

import android.content.SharedPreferences

internal enum class DownloadQualityTier(val label: String, val description: String) {
    LOW("Low", "Smaller file"), MEDIUM("Medium", "Balanced"), HIGH("High", "Best available");
}
internal fun legacyDownloadQualityTier(height: Int): DownloadQualityTier = when {
    height in 1..480 -> DownloadQualityTier.LOW
    height >= 1080 -> DownloadQualityTier.HIGH
    else -> DownloadQualityTier.MEDIUM
}
internal fun readDownloadQualityTier(preferences: SharedPreferences): DownloadQualityTier {
    val stored = preferences.getString("qualityTier", null)
    DownloadQualityTier.entries.firstOrNull { it.name == stored }?.let { return it }
    val tier = legacyDownloadQualityTier(preferences.getInt("quality", 720))
    preferences.edit().putString("qualityTier", tier.name).apply()
    return tier
}

/** Uses the video's discovered extremes/midpoint; two choices deliberately map Medium to Low. */
internal fun preferredDownloadOption(discovery: DownloadDiscovery?, tier: DownloadQualityTier): DownloadOption? {
    val choices = discovery?.choices.orEmpty()
    return when (tier) {
        DownloadQualityTier.HIGH -> choices.firstOrNull()
        DownloadQualityTier.LOW -> choices.lastOrNull()
        DownloadQualityTier.MEDIUM -> choices.getOrNull(1) ?: choices.firstOrNull()
    }
}

/** An explicit partial-discovery choice survives progressive updates and picker dismissal. */
internal fun selectedDownloadOption(discovery: DownloadDiscovery?, tier: DownloadQualityTier, explicitHeight: Int?): DownloadOption? =
    discovery?.choicesKeeping(explicitHeight)?.firstOrNull { it.quality.height == explicitHeight }
        ?: preferredDownloadOption(discovery, tier)

internal fun preferredDownloadQuality(item: PreparedDownload, tier: DownloadQualityTier): DownloadQuality? =
    preferredDownloadOption(DownloadDiscovery(item.qualities.map { DownloadOption(it,item.owner(it)) }, true), tier)?.quality
