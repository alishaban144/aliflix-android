package com.aliflix.app.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixSurfacePrimary
import kotlin.math.roundToLong

private const val DEFAULT_SCENE_MS = 30_000L

internal fun defaultSceneRange(positionMs: Long, durationMs: Long): SharedSceneRange {
    val length = minOf(DEFAULT_SCENE_MS, durationMs)
    val start = (positionMs - 10_000).coerceIn(0, durationMs - length)
    return SharedSceneRange(start, start + length)
}

internal fun parseSceneTime(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 1..3 || parts.dropLast(1).any { !it.matches(Regex("[0-9]{1,3}")) }) return null
    val last = parts.last().replace(',', '.')
    if (!last.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,3})?"))) return null
    val seconds = last.substringBefore('.').toLong()
    val millis = last.substringAfter('.', "").padEnd(3, '0').toLong()
    if (parts.size > 1 && seconds >= 60 || parts.size == 3 && parts[1].toInt() >= 60) return null
    val minutes = parts.dropLast(1).fold(0L) { value, part -> value * 60 + part.toLong() }
    return (minutes * 60 + seconds) * 1000 + millis
}

private fun sceneTime(positionMs: Long): String {
    val time = formatPlaybackTime(positionMs / 1000.0)
    val fraction = positionMs % 1000
    return if (fraction == 0L) time else "$time.${fraction.toString().padStart(3, '0').trimEnd('0')}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSceneSheet(
    selection: PlaybackSelection,
    durationMs: Long,
    positionMs: Long,
    thumbnails: PlayerSeekThumbnails?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val initial = remember { defaultSceneRange(positionMs, durationMs) }
    var startText by rememberSaveable { mutableStateOf(sceneTime(initial.startMs)) }
    var endText by rememberSaveable { mutableStateOf(sceneTime(initial.endMs)) }
    var sliderStart by rememberSaveable { mutableLongStateOf(initial.startMs) }
    var sliderEnd by rememberSaveable { mutableLongStateOf(initial.endMs) }
    val start = parseSceneTime(startText)?.takeIf { it in 0 until durationMs }
    val end = parseSceneTime(endText)?.takeIf { it in 1..durationMs }
    val range = if (start != null && end != null && start < end) SharedSceneRange(start, end) else null
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val title = if (selection.episodeNumber != null) {
        "${selection.media.title} · S${selection.seasonNumber ?: 1} E${selection.episodeNumber}"
    } else selection.media.title

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 520.dp, containerColor = AliflixSurfacePrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("share-scene-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share Scene", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerSeekThumbnail(thumbnails, start ?: sliderStart, Modifier.weight(1f), showTime = false)
                PlayerSeekThumbnail(thumbnails, ((end ?: sliderEnd) - 1).coerceAtLeast(0), Modifier.weight(1f), showTime = false)
            }
            RangeSlider(
                value = sliderStart.toFloat()..sliderEnd.toFloat(),
                onValueChange = { values ->
                    val from = values.start.roundToLong().coerceIn(0, durationMs - 1)
                    val to = values.endInclusive.roundToLong().coerceIn(from + 1, durationMs)
                    sliderStart = from
                    sliderEnd = to
                    startText = sceneTime(from)
                    endText = sceneTime(to)
                },
                valueRange = 0f..durationMs.toFloat(),
                colors = SliderDefaults.colors(thumbColor = AliflixAccentSecondary, activeTrackColor = AliflixAccentSecondary),
                modifier = Modifier.testTag("scene-range"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(startText, { value ->
                    if (value.length <= 13) startText = value
                    val from = parseSceneTime(startText)
                    val to = parseSceneTime(endText)
                    if (from != null && to != null && from in 0 until to && to <= durationMs) {
                        sliderStart = from
                        sliderEnd = to
                    }
                }, Modifier.weight(1f).testTag("scene-start"), label = { Text("Start") }, singleLine = true,
                    isError = start == null || end != null && start >= end,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                OutlinedTextField(endText, { value ->
                    if (value.length <= 13) endText = value
                    val from = parseSceneTime(startText)
                    val to = parseSceneTime(endText)
                    if (from != null && to != null && from in 0 until to && to <= durationMs) {
                        sliderStart = from
                        sliderEnd = to
                    }
                }, Modifier.weight(1f).testTag("scene-end"), label = { Text("End") }, singleLine = true,
                    isError = end == null || start != null && start >= end,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = range != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
                    val link = SharedSceneLink.from(selection, checkNotNull(range)).uri()
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Share Scene", link.toString()))
                    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy link") }
                Button(enabled = range != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
                    val link = SharedSceneLink.from(selection, checkNotNull(range)).uri()
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, title).putExtra(Intent.EXTRA_TEXT, link.toString())
                    context.startActivity(Intent.createChooser(share, null))
                    onDismiss()
                }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share") }
            }
        }
    }
}
