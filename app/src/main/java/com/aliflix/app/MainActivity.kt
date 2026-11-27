package com.aliflix.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.ui.AliflixApp
import com.aliflix.app.ui.theme.AliflixMobileTheme

open class MainActivity : ComponentActivity() {
    protected open val conversationTitle: com.aliflix.app.model.Media? get() = null
    protected open val conversationAction: org.json.JSONObject? get() = null
    private val viewModel: AliflixViewModel by viewModels()
    private lateinit var playerController: WebPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        playerController = WebPlayerController(this, viewModel.playbackProgressStore)

        setContent {
            AliflixMobileTheme {
                AliflixApp(
                    viewModel = viewModel,
                    playerController = playerController,
                    initialMedia = conversationTitle,
                    assistantAction = conversationAction,
                    onInitialDetailClosed = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)
    }

    override fun onDestroy() {
        playerController.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        playerController.onAppBackground()
        super.onStop()
    }

}
