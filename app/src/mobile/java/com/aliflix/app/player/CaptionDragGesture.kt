package com.aliflix.app.player

import android.content.Context
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent

/** Observes ordinary taps; only takes ownership after a hold on the rendered caption. */
internal class CaptionDragGesture(
    context: Context,
    private val bounds: () -> RectF?,
    private val offset: () -> Int,
    private val cancelChildGesture: (MotionEvent) -> Unit,
    private val start: () -> Unit,
    private val move: (Int) -> Unit,
    private val end: (Boolean) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private var candidate = false
    private var dragging = false
    private var startY = 0f
    private var startOffset = 0
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = candidate
        override fun onLongPress(e: MotionEvent) {
            if (!candidate) return
            dragging = true
            startY = e.y
            startOffset = offset()
            MotionEvent.obtain(e).also {
                it.action = MotionEvent.ACTION_CANCEL
                cancelChildGesture(it)
                it.recycle()
            }
            start()
        }
    })

    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            candidate = bounds()?.contains(event.x, event.y) == true
        }
        if (!candidate) return false
        detector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            if (dragging) end(false)
            dragging = false; candidate = false
            return false
        }
        val consumed = dragging
        if (dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            move((startOffset + (startY - event.y) / density).toInt().coerceIn(-200, 200))
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) end(event.actionMasked == MotionEvent.ACTION_UP)
            dragging = false; candidate = false
        }
        return consumed
    }
}
