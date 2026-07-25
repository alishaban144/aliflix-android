package com.aliflix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.ui.AliflixApp
import com.aliflix.app.ui.AliflixTvApp
import com.aliflix.app.ui.theme.AliflixTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AliflixViewModel by viewModels()
    private lateinit var playerController: WebPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playerController = WebPlayerController(this)

        setContent {
            AliflixTheme {
                if (BuildConfig.IS_TV) {
                    AliflixTvApp(
                        viewModel = viewModel,
                        playerController = playerController,
                    )
                } else {
                    AliflixApp(
                        viewModel = viewModel,
                        playerController = playerController,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        playerController.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHomeIfStale()
    }
}
