package com.aliflix.app.player

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeSkipControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timedSkipControlSeeksToSegmentEndAndKeepsPostCredits() {
        lateinit var engine: ExoPlayer
        lateinit var player: ForwardingPlayer
        var position = 11_999L
        val revision = mutableIntStateOf(0)
        compose.runOnUiThread {
            engine = ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
            player = object : ForwardingPlayer(engine) {
                override fun getDuration() = 90_000L
                override fun getCurrentPosition() = position
                override fun isCurrentMediaItemSeekable() = true
                override fun getPlaybackState() = Player.STATE_READY
                override fun getPlayWhenReady() = false
                override fun getMediaItemCount() = 1
                override fun seekTo(positionMs: Long) { position = positionMs; revision.intValue++ }
            }
        }
        try {
            compose.setContent { AliflixMobileTheme {
                NativePlayerScreen(NativePlayerUi(title = "Aliflix", ready = true, revision = revision.intValue,
                    segments = listOf(IntroSegment(IntroSegmentKind.INTRO, 12000, 34000), IntroSegment(IntroSegmentKind.OUTRO, 70000, 80000))),
                    player, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            } }
            compose.onNodeWithText("Skip intro").assertDoesNotExist()
            compose.runOnIdle { position = 12000; revision.intValue++ }
            compose.onNodeWithText("Skip intro").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.runOnIdle { assertEquals(34000L, position) }
            compose.onNodeWithText("Skip intro").assertDoesNotExist()
            compose.runOnIdle { position = 71000; revision.intValue++ }
            compose.onNodeWithText("Skip outro").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.runOnIdle { assertEquals(80000L, position) }
            compose.onNodeWithText("Skip outro").assertDoesNotExist()
            compose.runOnIdle { position = 13000; revision.intValue++ }
            compose.onNodeWithText("Skip intro").assertIsDisplayed()
            compose.onNodeWithContentDescription("Lock controls").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Skip intro").assertDoesNotExist()
        } finally { compose.runOnUiThread { engine.release() } }
    }
}
