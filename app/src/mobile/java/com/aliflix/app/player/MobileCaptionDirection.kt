@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import androidx.media3.common.text.Cue

/** Paragraph direction is explicit for each RTL caption line, including neutral
 * punctuation/dashes and Latin names. Do not reverse strings or move punctuation.
 * RLE/PDF works with Media3 CanvasSubtitleOutput and with WebVTT on Cast receivers.
 */
internal fun mobileCaptionText(text: CharSequence): String = text.toString().lineSequence().joinToString("\n") { line ->
    val clean = line.replace(Regex("[\\u202a-\\u202e\\u2066-\\u2069]"), "")
    val rtl = clean.count { Character.getDirectionality(it) in setOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) }
    val ltr = clean.count { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT }
    if (rtl > 0 && rtl >= ltr) "\u202b$clean\u202c" else clean
}

internal fun mobileCaptionCue(cue: Cue): Cue = cue.text?.let {
    val directed = if (it is android.text.Spanned) {
        // Preserve embedded italic/colour/position spans while changing only
        // Unicode paragraph controls. Replacing the whole string loses styles.
        android.text.SpannableStringBuilder(it).apply {
            for (index in length - 1 downTo 0) if (this[index] in '\u202a'..'\u202e' || this[index] in '\u2066'..'\u2069') delete(index, index + 1)
            val clean = toString()
            val lines = clean.split('\n')
            var end = clean.length
            for (line in lines.asReversed()) {
                val start = end - line.length
                if (mobileCaptionText(line).startsWith('\u202b')) { insert(end, "\u202c"); insert(start, "\u202b") }
                end = start - 1
            }
        }
    } else mobileCaptionText(it)
    cue.buildUpon().setText(directed).build()
} ?: cue
