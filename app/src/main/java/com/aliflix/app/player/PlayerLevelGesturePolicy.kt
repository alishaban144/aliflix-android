package com.aliflix.app.player

internal fun playerLevelGestureAllowed(x: Float, y: Float, screenWidth: Float,
    left: Float, top: Float, right: Float, bottom: Float, margin: Float, landscape: Boolean): Boolean {
    val edge = if (landscape) 0f else screenWidth * .15f
    return x > maxOf(left + margin, edge) && x < minOf(right - margin, screenWidth - edge) &&
        y > top + margin && y < bottom - margin
}
