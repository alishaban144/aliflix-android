package com.aliflix.app.downloads

import androidx.compose.runtime.Composable
import com.aliflix.app.model.Media
import com.aliflix.app.model.Episode

// Mobile UI entry points; the TV application has no download feature.
@Composable internal fun DownloadButton(media: Media, episode: Episode? = null, compact: Boolean = false) = Unit
@Composable internal fun downloadCount(): Int = 0
@Composable internal fun DownloadsSection() = Unit
@Composable internal fun DownloadSettings() = Unit
