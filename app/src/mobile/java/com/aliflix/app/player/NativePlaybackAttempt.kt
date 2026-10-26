package com.aliflix.app.player

/** A stable stream URL may have many attempts; an old attempt cannot fail a new one. */
internal fun nativeAttemptMatches(expected: String, active: String?, url: String, activeUrl: String?): Boolean =
    expected == active && url == activeUrl
