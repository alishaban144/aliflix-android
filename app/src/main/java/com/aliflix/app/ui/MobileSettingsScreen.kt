package com.aliflix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.aliflix.app.account.AccountState
import com.aliflix.app.model.*
import com.aliflix.app.player.PreferredVideoQuality
import com.aliflix.app.recommendation.RecommendationAiModel
import com.aliflix.app.ui.theme.*

@Composable
internal fun MobileSettingsDialog(
    accountState: AccountState, onOpenAccount: () -> Unit,
    generalProvider: PlaybackProviderId, onSelectProvider: (PlaybackProviderId) -> Unit,
    onEditProviderUrl: (PlaybackProviderId) -> Unit,
    aiRecommendationsEnabled: Boolean, onSetAiRecommendationsEnabled: (Boolean) -> Unit,
    recommendationAiModel: RecommendationAiModel, onSetRecommendationAiModel: (RecommendationAiModel) -> Unit,
    preferredSubtitleLanguage: SubtitleLanguage, onSelectPreferredSubtitleLanguage: (SubtitleLanguage) -> Unit,
    autoDisplaySubtitles: Boolean, onSetAutoDisplaySubtitles: (Boolean) -> Unit,
    updateUi: MobileUpdateUiState, onCheckForUpdates: () -> Unit, onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit, onClearRecent: () -> Unit, onDismiss: () -> Unit,
) {
    val store = (LocalContext.current.applicationContext as AliflixApplication).playerSettingsStore
    val playerSettings by store.settings.collectAsState()
    var clearConfirmation by remember { mutableStateOf(false) }
    if (clearConfirmation) AlertDialog(
        onDismissRequest = { clearConfirmation = false },
        title = { Text("Clear viewing history?") },
        text = { Text("Remove watched titles and reset history-based recommendations. Your saved list stays available.") },
        confirmButton = { TextButton(onClick = { clearConfirmation = false; onClearRecent() }) { Text("Clear history", color = AliflixError) } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") } },
        containerColor = AliflixSurfaceElevated, titleContentColor = AliflixContentPrimary,
        textContentColor = AliflixContentSecondary,
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AliflixBackgroundBase, contentColor = AliflixContentPrimary) {
            Column(Modifier.safeDrawingPadding().widthIn(max = 580.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ALIFLIX", color = AliflixAccentSecondary, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Text("Settings", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.background(AliflixSurfaceSecondary, CircleShape)) {
                        Icon(Icons.Rounded.Close, "Close settings", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("settings-content"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = AliflixSurfaceSecondary,
                        border = BorderStroke(1.dp, AliflixBorderSubtle), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAccount)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(42.dp).background(AliflixAccentPrimary.copy(alpha = .18f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Person, null, tint = AliflixAccentSecondary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(accountState.displayName ?: "Your Aliflix account", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (accountState.isSignedIn) accountState.email ?: "Manage account" else "Sign in to keep your watchlist in sync",
                                    fontSize = 11.sp, color = AliflixContentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = AliflixContentTertiary, modifier = Modifier.size(18.dp))
                        }
                    }
                    SettingsGroup("PLAYBACK") {
                        var sourceMenu by remember { mutableStateOf(false) }
                        Box {
                            SettingsRow(Icons.Rounded.PlayCircle, "Streaming source", generalProvider.displayName, onClick = { sourceMenu = true }) {
                                IconButton(onClick = { onEditProviderUrl(generalProvider) }) { Icon(Icons.Rounded.Edit, "Edit URL", Modifier.size(17.dp), tint = AliflixContentSecondary) }
                                Icon(Icons.Rounded.ExpandMore, "Choose source", Modifier.size(19.dp), tint = AliflixAccentSecondary)
                            }
                            SettingsMenu(sourceMenu, { sourceMenu = false }, mobileGeneralPlaybackProviders(), { it.displayName }, generalProvider) {
                                sourceMenu = false; onSelectProvider(it)
                            }
                        }
                        SettingsDivider()
                        SettingsRow(Icons.Rounded.HighQuality, "Preferred quality", if (playerSettings.preferredVideoQuality == PreferredVideoQuality.LOW) "Lowest available on every server" else "Adapts to your connection") {
                            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(AliflixBackgroundBase).padding(3.dp)) {
                                PreferredVideoQuality.entries.forEach { quality ->
                                    val selected = playerSettings.preferredVideoQuality == quality
                                    Text(quality.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (selected) Color.White else AliflixContentSecondary,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) AliflixAccentPrimary else Color.Transparent)
                                            .selectable(selected, role = Role.RadioButton, onClick = { store.updatePreferredVideoQuality(quality) })
                                            .testTag("settings-quality-${quality.name.lowercase()}").padding(horizontal = 13.dp, vertical = 14.dp))
                                }
                            }
                        }
                    }
                    SettingsGroup("SUBTITLES") {
                        var languageMenu by remember { mutableStateOf(false) }
                        Box {
                            SettingsRow(Icons.Rounded.Translate, "Language", preferredSubtitleLanguage.displayName, onClick = { languageMenu = true }) {
                                Icon(Icons.Rounded.ExpandMore, "Choose subtitle language", tint = AliflixAccentSecondary)
                            }
                            SettingsMenu(languageMenu, { languageMenu = false }, SubtitleLanguage.entries, { it.displayName }, preferredSubtitleLanguage) {
                                languageMenu = false; onSelectPreferredSubtitleLanguage(it)
                            }
                        }
                        SettingsDivider()
                        SettingsToggle(Icons.Rounded.Subtitles, "Auto display", "Find and match subtitles automatically", autoDisplaySubtitles, "settings-auto-subtitles-switch", onSetAutoDisplaySubtitles)
                    }
                    SettingsGroup("DISCOVER") {
                        SettingsToggle(Icons.Rounded.AutoAwesome, "Ask Aliflix", "Personal recommendations in Discover", aiRecommendationsEnabled, "settings-ask-aliflix-switch", onSetAiRecommendationsEnabled)
                        SettingsDivider()
                        var modelMenu by remember { mutableStateOf(false) }
                        Box {
                            SettingsRow(Icons.Rounded.Tune, "Recommendation model", recommendationAiModel.label, onClick = { modelMenu = true }) {
                                Icon(Icons.Rounded.ExpandMore, "Choose AI recommendation model", tint = AliflixAccentSecondary)
                            }
                            SettingsMenu(modelMenu, { modelMenu = false }, RecommendationAiModel.entries, { it.label }, recommendationAiModel) {
                                modelMenu = false; onSetRecommendationAiModel(it)
                            }
                        }
                    }
                    SettingsGroup("APP & STORAGE") {
                        SettingsRow(Icons.Rounded.SystemUpdate, "App updates", updateUi.message.ifBlank { "Aliflix ${BuildConfig.VERSION_NAME}" }) {
                            if (updateUi.busy) CircularProgressIndicator(Modifier.size(22.dp), color = AliflixAccentSecondary, strokeWidth = 2.dp)
                            else TextButton(onClick = when {
                                updateUi.downloadedApk != null -> onInstallUpdate
                                updateUi.available != null -> onDownloadUpdate
                                else -> onCheckForUpdates
                            }) { Text(when { updateUi.downloadedApk != null -> "Install"; updateUi.available != null -> "Download"; else -> "Check" }, color = AliflixAccentSecondary) }
                        }
                        updateUi.progress?.let { progress -> LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(), color = AliflixAccentPrimary) }
                        SettingsDivider()
                        SettingsRow(Icons.Rounded.DeleteSweep, "Clear watch history", "Reset watched titles and recommendations", onClick = { clearConfirmation = true }) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = AliflixContentTertiary)
                        }
                    }
                    Text("ALIFLIX  ·  ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(bottom = 20.dp), color = AliflixContentTertiary,
                        fontSize = 10.sp, letterSpacing = 1.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = AliflixContentTertiary, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp))
        Surface(shape = RoundedCornerShape(17.dp), color = AliflixSurfaceSecondary, border = BorderStroke(1.dp, AliflixBorderSubtle)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}
@Composable private fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 49.dp, end = 14.dp), color = AliflixBorderSubtle)

@Composable private fun SettingsRow(icon: ImageVector, title: String, detail: String, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .heightIn(min = 64.dp).padding(horizontal = 13.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = AliflixContentSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AliflixContentPrimary)
            Text(detail, fontSize = 11.sp, lineHeight = 14.sp, color = AliflixContentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
@Composable private fun SettingsToggle(icon: ImageVector, title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Box(Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        SettingsRow(icon, title, detail) {
            Switch(checked, onCheckedChange = null, modifier = Modifier.testTag(tag),
                colors = SwitchDefaults.colors(checkedTrackColor = AliflixAccentPrimary, checkedThumbColor = Color.White,
                    uncheckedTrackColor = AliflixSurfaceElevated, uncheckedThumbColor = AliflixContentSecondary, uncheckedBorderColor = AliflixBorderStrong))
        }
    }
}
@Composable private fun <T> SettingsMenu(expanded: Boolean, dismiss: () -> Unit, items: List<T>, label: (T) -> String, selected: T, onSelect: (T) -> Unit) {
    DropdownMenu(expanded, dismiss, modifier = Modifier.heightIn(max = 340.dp).background(AliflixSurfaceElevated)) {
        items.forEach { item -> DropdownMenuItem(text = { Text(label(item), fontSize = 13.sp, color = AliflixContentPrimary) }, onClick = { onSelect(item) },
            trailingIcon = { if (item == selected) Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = AliflixAccentSecondary) }) }
    }
}
