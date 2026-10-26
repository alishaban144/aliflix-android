package com.aliflix.app.ui

/** Full duration in a compact single-line pill; no lost minutes. */
internal fun compactLibraryRuntime(value:String):String {
    val match=Regex("(?i)^(\\d{1,4})\\s*(?:min|mins|minute|minutes)(\\s*/\\s*(?:ep|episode))?$").matchEntire(value.trim())?:return value
    val minutes=match.groupValues[1].toInt()
    val duration=if(minutes<60)"${minutes}m"else "${minutes/60}h"+(minutes%60).takeIf{it>0}?.let{"${it}m"}.orEmpty()
    return duration+if(match.groupValues[2].isNotEmpty())"/ep" else ""
}
