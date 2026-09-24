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
    val top = maxOf(margin, topInset)
    val right = minOf(screenWidth - margin, screenWidth - rightInset)
    val bottom = minOf(screenHeight - margin, screenHeight - bottomInset)
    return x > left && x < right && y > top && y < bottom
}
