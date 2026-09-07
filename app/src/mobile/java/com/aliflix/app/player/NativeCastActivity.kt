package com.aliflix.app.player

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import com.google.common.util.concurrent.ListenableFuture
import java.lang.ref.WeakReference

/** A lock-aware window on the TV display. Playback remains owned by the phone service. */
@androidx.annotation.OptIn(UnstableApi::class)
class NativeCastActivity : Activity() {
    private val surfaceOwner = java.util.UUID.randomUUID().toString()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var surface: SurfaceView
    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val size = player.videoSize
            if (size.height > 0) frame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
            subtitles.setCues(player.currentCues.cues)
            if (player.mediaItemCount == 0) finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (windowManager.defaultDisplay.displayId == android.view.Display.DEFAULT_DISPLAY) { finish(); return }
        active = WeakReference(this)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = sendSurface()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = sendSurface()
            override fun surfaceDestroyed(holder: SurfaceHolder) = sendSurface(true)
        })
        frame = AspectRatioFrameLayout(this).apply { addView(surface, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        subtitles = SubtitleView(this); root.addView(subtitles, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val connection = MediaController.Builder(this, SessionToken(this, ComponentName(this, NativePlaybackService::class.java))).buildAsync()
        future = connection
        connection.addListener({ if (future === connection) runCatching { connection.get() }.onSuccess {
            controller = it; it.addListener(listener); sendSurface(); listener.onEvents(it, Player.Events(androidx.media3.common.FlagSet.Builder().build()))
        }.onFailure { finish() } }, mainExecutor)
    }
    private fun sendSurface(clear: Boolean = false) {
        NativePlaybackService.attachSurface(surfaceOwner, surface.holder.surface.takeIf { !clear && it.isValid }, tv = true)
    }
    override fun onDestroy() {
        if (::surface.isInitialized) sendSurface(true)
        controller?.removeListener(listener); controller = null
        future?.let(MediaController::releaseFuture); future = null
        if (active?.get() === this) active = null
        super.onDestroy()
    }
    companion object {
        private var active: WeakReference<NativeCastActivity>? = null
        internal fun closeOutput() { active?.get()?.finish() }
    }
}
