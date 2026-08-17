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
import com.aliflix.app.ui.AliflixTvApp
import com.aliflix.app.ui.theme.AliflixMobileTheme
import com.aliflix.app.ui.theme.AliflixTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AliflixViewModel by viewModels()
    private lateinit var playerController: WebPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.IS_TV) {
            enableEdgeToEdge()
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        }
        playerController = WebPlayerController(this)

        setContent {
            if (BuildConfig.IS_TV) {
                AliflixTheme {
                    AliflixTvApp(
                        viewModel = viewModel,
                        playerController = playerController,
                    )
                }
            } else {
                AliflixMobileTheme {
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

}
