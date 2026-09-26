package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.aliflix.app.ui.theme.AliflixBackgroundBase

/** A single continuous image plane behind hero, actions, and the page below. */
@Composable internal fun CinematicBackdrop(artwork: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(AliflixBackgroundBase)) {
        AsyncImage(artwork, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(
                    0f to AliflixBackgroundBase.copy(alpha = .20f),
                    .28f to AliflixBackgroundBase.copy(alpha = .30f),
                    .56f to AliflixBackgroundBase.copy(alpha = .76f),
                    .82f to AliflixBackgroundBase.copy(alpha = .94f),
                    1f to AliflixBackgroundBase))
                drawRect(Brush.horizontalGradient(listOf(AliflixBackgroundBase.copy(alpha = .22f), Color.Transparent)))
            })
    }
}
