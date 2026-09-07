package com.aliflix.app.player

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class NativeBackgroundPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun videoAndNotificationsSurviveBackgroundRecreationAndActivityDestruction() = verifyPlayback(independentDisplay = true)

    @Test fun sharedDisplayRestoresVideoAfterSystemPowerCycle() = verifyPlayback(independentDisplay = false)

    private fun verifyPlayback(independentDisplay: Boolean) {
        grantNativeFixtureNetworkPermission()
        val asset = instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() }
        val server = FixtureServer(asset)
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val lastFrame = java.util.concurrent.atomic.AtomicLong()
        val imageThread = HandlerThread("CastFrameVerification").apply { start() }
        val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                var hash = 1L
                // Sample decoded pixels rather than merely trusting a running media clock.
                for (index in 0 until buffer.limit() step 1024) hash = hash * 31 + buffer.get(index)
                if (lastFrame.getAndSet(hash) != hash) frames.incrementAndGet()
            }
        }, Handler(imageThread.looper))
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix runtime test", "", "Background casting verification", 3000, true)
        var scenario: ActivityScenario<NativePlayerActivity>? = null
        var display: android.hardware.display.VirtualDisplay? = null
        val requestFile = java.io.File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json")
        requestFile.writeText(request.toJson())
        try {
            scenario = ActivityScenario.launch(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", requestFile.name), nativePhoneLaunchOptions())
            val session = awaitSession()
            await("Initial playback: ${session.playbackState}") { session.playbackState?.state == PlaybackState.STATE_PLAYING }
            assertFalse("The service must consume the file handoff", requestFile.exists())
            // Only fixture creation has system privileges; app playback runs with its normal UID.
            // A trusted, separate power group models an independently powered external TV display.
            // AOSP test-only system flags: TRUSTED = 1 << 10, OWN_DISPLAY_GROUP = 1 << 11.
            if (independentDisplay) instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY", "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY")
            try {
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "Aliflix test TV", 640, 360, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    (if (independentDisplay) (1 shl 10) or (1 shl 11) else 0),
            )
            } finally { if (independentDisplay) instrumentation.uiAutomation.dropShellPermissionIdentity() }
            await("Decoded TV frames") { frames.get() >= 3 }
            val before = session.playbackState!!.position
            val framesBefore = frames.get()
            scenario.onActivity { it.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            await("Settings must put the player in the background") { scenario?.state == Lifecycle.State.CREATED }
            Thread.sleep(12_000)
            assertTrue("The media clock must advance while Activity is stopped", position(session) > before + 9000)
            assertTrue("The TV must receive changing decoded frames in the background", frames.get() > framesBefore + 5)
            val beforeLock = position(session)

            instrumentation.uiAutomation.executeShellCommand("input -d 0 keyevent KEYCODE_POWER").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            Thread.sleep(3000)
            val lockFrames = frames.get()
            Thread.sleep(6000)
            assertEquals("Phone display should be off", android.view.Display.STATE_OFF,
                context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).state)
            assertTrue("Playback must advance with the phone locked", position(session) > beforeLock + 4500)
            val poweredWhileLocked = display!!.display.state != android.view.Display.STATE_OFF
            if (independentDisplay && poweredWhileLocked) {
                assertTrue("An independently powered TV display must keep receiving video throughout phone lock", frames.get() > lockFrames + 5)
            } else {
                // A shared power group sleeps its windows even on API 35, where a virtual
                // Display can still report ON. This fixture tests recovery, not lock support.
                android.util.Log.w("AliflixCastValidation", "System-controlled shared/asleep display: phone-lock video is not proven; checking decoder survival and output restoration.")
            }
            assertNull("Display power policy must not cause a decoder failure", NativePlaybackService.playbackFailure)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            val wakeFrames = frames.get()
            await("TV video must resume after a system display power cycle") { frames.get() > wakeFrames + 5 }
            sendNotificationAction("pause")
            await { session.playbackState?.state == PlaybackState.STATE_PAUSED }
            val paused = position(session)
            Thread.sleep(3500)
            assertTrue("Pause must stay paused without a JavaScript watchdog restarting it", kotlin.math.abs(position(session) - paused) < 300)
            sendNotificationAction("play")
            await { session.playbackState?.state == PlaybackState.STATE_PLAYING }
            // Return from the actual Settings task before requesting Activity recreation.
            instrumentation.uiAutomation.executeShellCommand("input -d 0 keyevent KEYCODE_BACK").use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
            await("Returning from Settings must resume the player controls") { scenario?.state == Lifecycle.State.RESUMED }
            scenario.recreate()
            // Activity recreation/rotation can take long enough to reach the short fixture's end.
            // Start a fresh observation window without changing the session's play/pause state.
            session.transportControls.seekTo(3000)
            await("Playback after recreation and seek") { session.playbackState?.state == PlaybackState.STATE_PLAYING && position(session) in 2500..10_000 }
            scenario.close(); scenario = null
            val afterDestroy = position(session)
            val destroyFrames = frames.get()
            Thread.sleep(6000)
            assertTrue("Destroying the Activity must not destroy playback", position(session) > afterDestroy + 4500)
            assertTrue("TV surface belongs to the service after Activity destruction", frames.get() > destroyFrames + 5)
            assertNotNull(context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.notification.extras.containsKey("android.mediaSession") })
            scenario = ActivityScenario.launch(Intent(context, NativePlayerActivity::class.java), nativePhoneLaunchOptions())
            scenario.onActivity { assertEquals("Controls must reopen on the phone", android.view.Display.DEFAULT_DISPLAY, it.windowManager.defaultDisplay.displayId) }
            await("Reopened native controls") { var connected = false; scenario.onActivity { connected = it.playbackController != null }; connected }
            scenario.onActivity { it.playbackController!!.sendCustomCommand(
                androidx.media3.session.SessionCommand(NativePlaybackService.ACTION_STOP_CAST, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY) }
            await("Stop casting returns to native phone output") { var local = false; scenario.onActivity { local = !it.playbackUiState.external }; local }
            val stoppedCast = position(session)
            Thread.sleep(1500)
            assertTrue("Stopping casting must keep playback running", position(session) > stoppedCast + 1000)
            session.transportControls.pause()
            await { session.playbackState?.state == PlaybackState.STATE_PAUSED }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            scenario?.close()
            context.stopService(Intent(context, NativePlaybackService::class.java))
            requestFile.delete()
            display?.release(); reader.close(); imageThread.quitSafely(); server.close()
        }
    }

    private fun awaitSession(): MediaController {
        var result: MediaController? = null
        await {
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.notification.extras.containsKey("android.mediaSession") }?.notification
            val token = notification?.extras?.let { androidx.core.os.BundleCompat.getParcelable(it, "android.mediaSession", android.media.session.MediaSession.Token::class.java) }
            result = token?.let { MediaController(context, it) }
            result != null
        }
        return result!!
    }

    private fun sendNotificationAction(label: String) {
        var action: android.app.Notification.Action? = null
        await {
            action = context.getSystemService(NotificationManager::class.java).activeNotifications
                .flatMap { it.notification.actions?.toList().orEmpty() }
                .firstOrNull { it.title.toString().contains(label, ignoreCase = true) }
            action != null
        }
        action!!.actionIntent.send()
    }

    private fun position(controller: MediaController): Long {
        val state = controller.playbackState!!
        return state.position + if (state.state == PlaybackState.STATE_PLAYING) {
            ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        } else 0
    }

    private fun await(description: String = "Playback condition", condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        // MediaSession updates can change between reads (e.g. PLAYING -> BUFFERING on
        // seek). Do not read twice and report a timeout immediately after observing success.
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("$description timed out")
    }

    private class FixtureServer(private val bytes: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/cast-test.mp4"
        init { thread(isDaemon = true) { while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { client.use(::serve) } }
        } } }
        private fun serve(client: Socket) {
            val input = client.getInputStream().bufferedReader()
            val first = input.readLine() ?: return
            var range: String? = null
            while (true) { val line = input.readLine() ?: return; if (line.isEmpty()) break; if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim() }
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = (range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
            val output = client.getOutputStream()
            val partial = range != null
            output.write(buildString {
                append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
                if (partial) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray())
            if (!first.startsWith("HEAD")) output.write(bytes, start, end - start + 1)
            output.flush()
        }
        override fun close() { socket.close() }
    }
}

/** Grants the real Android 17 LAN permission without changing the app's audio-focus identity. */
internal fun grantNativeFixtureNetworkPermission() {
    if (android.os.Build.VERSION.SDK_INT < 37) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.executeShellCommand(
        "pm grant ${instrumentation.targetContext.packageName} android.permission.ACCESS_LOCAL_NETWORK",
    ).use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
    assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
        instrumentation.targetContext.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK"))
}
