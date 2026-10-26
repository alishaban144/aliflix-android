package com.aliflix.app.player

import com.aliflix.app.model.PlaybackSelection

/** Off follows the title/series; a manually chosen file belongs to its exact episode. */
internal fun scopedManualSubtitleChoice(owner: String?, content: String?, enabled: Boolean?,
    selection: PlaybackSelection?): Boolean? {
    if (selection == null || owner != selection.media.key || enabled == null) return null
    return enabled.takeIf { !it || content == subtitleContentKey(selection) }
}

internal fun automaticSubtitleMayActivate(enabled: Boolean, manual: Boolean, language: String,
    preferredLanguage: String, embeddedActive: Boolean): Boolean = enabled && !manual && !embeddedActive &&
        canonicalSubtitleLanguageCode(language) == canonicalSubtitleLanguageCode(preferredLanguage) &&
        com.aliflix.app.model.SubtitleLanguage.entries.any { it.code == canonicalSubtitleLanguageCode(language) }
