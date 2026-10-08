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

    @Test fun introSkipsButOutroShowsUpNextInstead() {
        lateinit var engine: ExoPlayer
        lateinit var player: ForwardingPlayer
        var position = 11_999L
        val revision = mutableIntStateOf(0)
        val episode = mutableIntStateOf(1)
        var playbackState = Player.STATE_READY
        compose.runOnUiThread {
            engine = ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
            player = object : ForwardingPlayer(engine) {
                override fun getDuration() = 90_000L
                override fun getCurrentPosition() = position
                override fun isCurrentMediaItemSeekable() = true
                override fun getPlaybackState() = playbackState
                override fun getPlayWhenReady() = false
                override fun getMediaItemCount() = 1
                override fun seekTo(positionMs: Long) { position = positionMs; revision.intValue++ }
            }
        }
        try {
            compose.setContent { AliflixMobileTheme {
                NativePlayerScreen(
                    state = NativePlayerUi(
                        title = "Aliflix",
                        ready = true,
                        revision = revision.intValue,
                        episodes = listOf(
                            com.aliflix.app.model.Episode(1, 2, "Episode two"),
                        ),
                        episodeNumber = episode.intValue,
                        playbackSelection = com.aliflix.app.model.PlaybackSelection(
                            media = com.aliflix.app.model.Media(1396, com.aliflix.app.model.MediaType.TV, "Series"),
                            seasonNumber = 1,
                            episodeNumber = episode.intValue,
                        ),
                        segments = listOf(IntroSegment(IntroSegmentKind.INTRO, 12000, 34000), IntroSegment(IntroSegmentKind.OUTRO, 70000, 80000))
                    ),
                    player = player,
                    settings = PlayerSettings(playNextEpisode = false)
                )
            } }
            compose.onNodeWithText("Skip intro").assertDoesNotExist()
            compose.runOnIdle { position = 12000; revision.intValue++ }
            compose.onNodeWithText("Skip intro").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.runOnIdle { assertEquals(34000L, position) }
            compose.onNodeWithText("Skip intro").assertDoesNotExist()
            compose.runOnIdle { position = 69_999; revision.intValue++ }
            compose.onNodeWithText("UP NEXT").assertDoesNotExist()
            compose.runOnIdle { position = 70_000; revision.intValue++ }
            compose.onNodeWithText("Skip outro").assertDoesNotExist()
            compose.onNodeWithText("UP NEXT").assertIsDisplayed()
            compose.runOnIdle { position = 80_000; revision.intValue++ }
            compose.onNodeWithText("UP NEXT").assertIsDisplayed()
            compose.runOnIdle { position = 90_000; playbackState = Player.STATE_ENDED; revision.intValue++ }
            compose.onNodeWithText("UP NEXT").assertIsDisplayed()
            compose.onNodeWithText("Watch credits").performClick()
            compose.onNodeWithText("UP NEXT").assertDoesNotExist()
            compose.runOnIdle { episode.intValue = 2; revision.intValue++ }
            compose.onNodeWithText("UP NEXT").assertDoesNotExist() // The last episode has no successor.
        } finally { compose.runOnUiThread { engine.release() } }
    }
}
