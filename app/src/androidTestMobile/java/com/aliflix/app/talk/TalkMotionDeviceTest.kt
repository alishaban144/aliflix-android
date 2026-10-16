package com.aliflix.app.talk

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real Choreographer frames, outside Compose's deliberately frozen test clock. */
class TalkMotionDeviceTest {
    @Test fun existingNativeVadDetectsTheThreeGeneratedLanguagesWithoutNewModels() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkMotion") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (language in listOf("en", "de", "ar")) {
            val bytes = File(context.getExternalFilesDir(null), "talk-validation/question-$language.pcm").readBytes()
            com.konovalov.vad.webrtc.VadWebRTC(com.konovalov.vad.webrtc.config.SampleRate.SAMPLE_RATE_16K,
                com.konovalov.vad.webrtc.config.FrameSize.FRAME_SIZE_320, com.konovalov.vad.webrtc.config.Mode.AGGRESSIVE).use { vad ->
                var spoken = 0
                for (at in 0..bytes.size - 640 step 640) {
                    val pcm = ShortArray(320) { i -> ((bytes[at+i*2].toInt() and 255) or (bytes[at+i*2+1].toInt() shl 8)).toShort() }
                    if (vad.isSpeech(pcm)) spoken++
                }
                assertTrue("Expected actual native speech detection for $language", spoken >= 20)
            }
        }
    }
    @Test fun particleWordIsVisibleAndTheFieldKeepsMovingOnThePhysicalDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkMotion") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), "talk-motion").apply { mkdirs() }
        ActivityScenario.launch<TalkVoiceActivity>(Intent(context, TalkVoiceActivity::class.java)).use {
            Thread.sleep(1700)
            instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${context.packageName} reset").close()
            val first = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            Thread.sleep(1400)
            val second = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val stats = instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${context.packageName} framestats")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(stats).use { input -> File(output, "frame-stats.txt").writeBytes(input.readBytes()) }
                File(output, "field-first.png").outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "field-second.png").outputStream().use { second.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val a = sample(first); val b = sample(second)
                assertTrue("Visible violet word/particles expected", a.count { android.graphics.Color.red(it) > 70 } > 20)
                assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
                assertFalse("The real particle flow should animate", a.contentEquals(b))
            } finally { first.recycle(); second.recycle() }
        }
    }
    private fun sample(bitmap: Bitmap): IntArray {
        val points = ArrayList<Int>()
        for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 4)
            for (x in bitmap.width / 8 until bitmap.width * 7 / 8 step 4) points.add(bitmap.getPixel(x, y))
        return points.toIntArray()
    }
}
