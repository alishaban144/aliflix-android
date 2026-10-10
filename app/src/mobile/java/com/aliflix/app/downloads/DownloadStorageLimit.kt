package com.aliflix.app.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.launch

internal fun storageLimitInput(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..200 }
internal fun storageLimitBelowUsage(limitGb: Int, used: Long): Boolean = used > limitGb * DOWNLOAD_GB

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
    val usage by animateFloatAsState(
        (usedBytes.toDouble() / (limit * DOWNLOAD_GB)).toFloat().coerceIn(0f, 1f), label = "download-storage-usage",
    )
    Column(Modifier.fillMaxWidth().clickable { input = limit.toString(); sheet = true }
        .testTag("download-storage-limit").padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Icon(Icons.Rounded.Storage, null, Modifier.size(22.dp), tint = AliflixContentSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Storage limit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${downloadSize(usedBytes)} used", fontSize = 11.sp, color = AliflixContentSecondary)
            }
            Text("$limit GB", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AliflixAccentSecondary)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = AliflixContentTertiary)
        }
        LinearProgressIndicator(progress = { usage }, modifier = Modifier.fillMaxWidth().padding(start = 33.dp, top = 9.dp).height(3.dp),
            color = if (storageLimitBelowUsage(limit, usedBytes)) AliflixError else AliflixAccentSecondary,
            trackColor = AliflixSurfaceSecondary, drawStopIndicator = {})
    }
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
