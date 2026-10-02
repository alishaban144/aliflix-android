package com.aliflix.app.player

import java.util.Locale

/**
 * Every source must expose every alternate audio rendition the site player offers.
 * CineJoy masters label renditions "Track 1..4" with no LANGUAGE tag; other CDNs
 * provide language codes without labels. Combine both so German (or any language)
 * is always identifiable and selectable, on CineJoy and every other mirror.
 */
internal fun formatAudioTrackLabel(language: String?, label: String?): String {
    val cleanLabel = label?.trim().takeIf { !it.isNullOrEmpty() }
    val displayLanguage = language?.trim()?.takeIf { it.isNotEmpty() }?.let { code ->
        runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    }
    if (displayLanguage.isNullOrEmpty()) return cleanLabel ?: "Original audio"
    if (cleanLabel.isNullOrEmpty()) return displayLanguage
    // Avoid "German • German" when the label already names the language.
    if (cleanLabel.equals(displayLanguage, ignoreCase = true)) return displayLanguage
    return "$displayLanguage • $cleanLabel"
}

internal fun formatVideoTrackLabel(height: Int): String =
    height.takeIf { it > 0 }?.let { "${it}p" } ?: "Original"
