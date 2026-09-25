package com.aliflix.app.player

internal fun playerLevelGestureAllowed(
    x: Float,
    y: Float,
    screenWidth: Float,
    screenHeight: Float,
    margin: Float,
    leftInset: Float = 0f,
    topInset: Float = 0f,
    rightInset: Float = 0f,
    bottomInset: Float = 0f,
): Boolean {
    val left = maxOf(margin, leftInset)
    val top = 0f
    val right = minOf(screenWidth - margin, screenWidth - rightInset)
    val bottom = screenHeight
    return x > left && x < right && y > top && y < bottom
}
