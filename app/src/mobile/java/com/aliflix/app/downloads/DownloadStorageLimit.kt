package com.aliflix.app.downloads

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun storageLimitInput(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..200 }
internal fun storageLimitBelowUsage(limitGb: Int, used: Long): Boolean = used > limitGb * DOWNLOAD_GB
internal fun storageLimitSliderValue(value: Float): Int = value.roundToInt().coerceIn(1, 200)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun DownloadStorageLimit(store: OfflineDownloads, usedBytes: Long) {
    var limit by remember { mutableIntStateOf((store.limitBytes / DOWNLOAD_GB).toInt()) }
    DisposableEffect(store) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "limitGb") limit = (store.limitBytes / DOWNLOAD_GB).toInt()
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf(limit.toString()) }
    DownloadStorageSlider(
        limit = limit,
        usedBytes = usedBytes,
        onCommit = { store.preferences.edit().putInt("limitGb", it).apply() },
        onEdit = { input = limit.toString(); sheet = true },
    )
    if (sheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        fun dismiss() { scope.launch { state.hide(); sheet = false } }
        ModalBottomSheet(onDismissRequest = { sheet = false },
        sheetState = state,
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
        contentColor = AliflixContentPrimary, shape = AliflixCorners.Chrome,
    ) {
        Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 580.dp).fillMaxWidth().imePadding()
            .padding(horizontal = AliflixSpacing.Content).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Storage limit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 25, 50, 100, 200).forEach { preset ->
                    FilterChip(selected = storageLimitInput(input) == preset, onClick = { input = preset.toString() },
                        label = { Text("$preset GB") }, shape = AliflixCorners.Small,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AliflixAccentPrimary.copy(alpha = .24f),
                            selectedLabelColor = AliflixAccentSecondary))
                }
            }
            val selected = storageLimitInput(input)
            OutlinedTextField(value = input, onValueChange = { value ->
                if (value.length <= 3 && value.all { it in '0'..'9' }) input = value
            }, label = { Text("Custom") }, suffix = { Text("GB") }, singleLine = true,
                isError = selected == null, supportingText = if (selected == null) ({ Text("1–200 GB") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = AliflixCorners.Small, modifier = Modifier.fillMaxWidth().testTag("download-storage-custom"))
            if (selected != null && storageLimitBelowUsage(selected, usedBytes))
                Text("Limit below usage", color = AliflixError, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = ::dismiss, modifier = Modifier.weight(1f), shape = AliflixCorners.Small) { Text("Cancel") }
                Button(enabled = selected != null, onClick = {
                    selected?.let { store.preferences.edit().putInt("limitGb", it).apply() }
                    dismiss()
                }, modifier = Modifier.weight(1f).testTag("download-storage-save"), shape = AliflixCorners.Small,
                    colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary)) { Text("Save") }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DownloadStorageSlider(
    limit: Int,
    usedBytes: Long,
    onCommit: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    var value by remember(limit) { mutableFloatStateOf(limit.coerceIn(1, 200).toFloat()) }
    val selected = storageLimitSliderValue(value)
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val thumbSize by animateDpAsState(if (dragged || pressed) 20.dp else 16.dp, label = "storage-limit-thumb")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val belowUsage = storageLimitBelowUsage(selected, usedBytes)
    Column(Modifier.fillMaxWidth().testTag("download-storage-limit")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Storage, null, Modifier.size(20.dp), tint = AliflixContentSecondary)
            Text("Storage limit", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onEdit, shape = AliflixCorners.Small,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = AliflixAccentSecondary,
                    containerColor = AliflixAccentPrimary.copy(alpha = .12f)),
                modifier = Modifier.testTag("download-storage-value").semantics {
                    contentDescription = "Storage limit"
                    stateDescription = "$selected GB"
                }) {
                Text("$selected GB", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Slider(value = value, onValueChange = { value = storageLimitSliderValue(it).toFloat() },
            onValueChangeFinished = { onCommit(storageLimitSliderValue(value)) },
            valueRange = 1f..200f, steps = 198, interactionSource = interactions,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("download-storage-slider")
                .semantics { contentDescription = "Storage limit"; stateDescription = "$selected GB" },
            thumb = {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(thumbSize).background(AliflixAccentSecondary, CircleShape))
                }
            },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val start = Offset(if (rtl) size.width else 0f, center.y)
                    val end = Offset(if (rtl) 0f else size.width, center.y)
                    val fraction = ((state.value - 1f) / 199f).coerceIn(0f, 1f)
                    val activeEnd = Offset(start.x + (end.x - start.x) * fraction, center.y)
                    drawLine(AliflixBorderSubtle, start, end, size.height, StrokeCap.Round)
                    if (fraction > 0f) drawLine(AliflixAccentSecondary, start, activeEnd, size.height, StrokeCap.Round)
                }
            })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("1 GB", fontSize = 10.sp, color = AliflixContentTertiary)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("${downloadSize(usedBytes)} used", fontSize = 11.sp,
                    color = if (belowUsage) AliflixError else AliflixContentSecondary)
            }
            Text("200 GB", fontSize = 10.sp, color = AliflixContentTertiary)
        }
        if (belowUsage) Text("Limit below usage", color = AliflixError,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}
