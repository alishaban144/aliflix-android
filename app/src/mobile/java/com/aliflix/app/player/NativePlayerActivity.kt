package com.aliflix.app.player

import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.DeviceInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.common.util.concurrent.ListenableFuture

/** A disposable controller/surface. Closing or recreating this Activity never releases playback. */
@androidx.annotation.OptIn(UnstableApi::class)
class NativePlayerActivity : FragmentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var video: SurfaceView
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private lateinit var controls: PlayerControlView
    private lateinit var status: TextView
    private lateinit var castButton: MediaRouteButton
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateOutput()
        override fun onDisplayChanged(displayId: Int) = updateOutput()
        override fun onDisplayRemoved(displayId: Int) = updateOutput()
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateOutput()
        override fun onPlayerError(error: PlaybackException) {
            status.text = "This stream could not play (${error.errorCodeName}). Return to Aliflix and try another server."
            status.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = sendSurface()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = sendSurface()
            override fun surfaceDestroyed(holder: SurfaceHolder) = sendSurface(clear = true)
        })
        videoFrame = AspectRatioFrameLayout(this).apply { addView(video, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        subtitles = SubtitleView(this)
        root.addView(subtitles, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply { setTextColor(-1); textSize = 18f; gravity = Gravity.CENTER; setPadding(32, 72, 32, 72) }
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        controls = PlayerControlView(this).apply { showTimeoutMs = 0 }
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(8, 12, 8, 8) }
        bar.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        castButton = MediaRouteButton(this).apply { contentDescription = "Cast video to TV" }
        runCatching { CastButtonFactory.setUpMediaRouteButton(this, castButton) }
        bar.addView(castButton, LinearLayout.LayoutParams(56.dp, 48.dp))
        bar.addView(Button(this).apply {
            text = "Wireless display"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }.onFailure { status.text = "Wireless display settings are unavailable" }
            }
        })
        bar.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                controller?.stop(); controller?.clearMediaItems()
                startService(Intent(this@NativePlayerActivity, NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP))
                finish()
            }
        })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        if (savedInstanceState == null) acceptRequest(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptRequest(intent) }

    private fun acceptRequest(intent: Intent) {
        val raw = nativeRequestPayload(this, intent) ?: return
        val file = intent.getStringExtra("requestFile")
        intent.removeExtra("request")
        intent.removeExtra("requestFile")
        val service = Intent(this, NativePlaybackService::class.java)
        if (file != null) service.putExtra("requestFile", file) else service.putExtra("request", raw)
        if (NativePlaybackRequest.fromJson(raw).playing) androidx.core.content.ContextCompat.startForegroundService(this, service)
        else startService(service)
    }

    override fun onStart() {
        super.onStart()
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, NativePlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            runCatching { future.get() }.onSuccess {
                controller = it; it.addListener(listener); controls.player = it; sendSurface(); updateOutput()
            }.onFailure { status.text = "Unable to connect to playback controls" }
        }, mainExecutor)
    }

    private fun updateOutput() {
        val current = controller ?: return
        val external = current.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE ||
            displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty()
        video.visibility = if (external) View.GONE else View.VISIBLE
        val size = current.videoSize
        if (size.height > 0) videoFrame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
        subtitles.setCues(current.currentCues.cues)
        subtitles.visibility = if (external) View.GONE else View.VISIBLE
        status.text = when {
            current.playerError != null -> "This stream could not play (${current.playerError?.errorCodeName}). Try another server."
            external -> "Playing on your TV\nYou can use other apps. Playback controls are in notifications."
            current.playbackState == Player.STATE_BUFFERING -> "Preparing video…"
            current.mediaItemCount == 0 -> "Playback stopped"
            else -> ""
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        displays.unregisterDisplayListener(displayListener)
        sendSurface(clear = true); controls.player = null
        controller?.removeListener(listener); controller = null
        controllerFuture?.let(MediaController::releaseFuture); controllerFuture = null
        super.onStop()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun sendSurface(clear: Boolean = false) {
        val surface = video.holder.surface.takeIf { !clear && it.isValid }
        controller?.sendCustomCommand(SessionCommand(NativePlaybackService.ACTION_PHONE_SURFACE, Bundle.EMPTY), Bundle().apply { putParcelable("surface", surface) })
    }
}
