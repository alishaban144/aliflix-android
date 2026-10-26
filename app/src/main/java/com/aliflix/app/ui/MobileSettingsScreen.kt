@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.aliflix.app.ui

import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    generalProvider: PlaybackProvider, onSelectProvider: (PlaybackProvider) -> Unit,
    onEditProviderUrl: (PlaybackProvider) -> Unit,
    aiRecommendationsEnabled: Boolean, onSetAiRecommendationsEnabled: (Boolean) -> Unit,
    recommendationAiModel: RecommendationAiModel, onSetRecommendationAiModel: (RecommendationAiModel) -> Unit,
    preferredSubtitleLanguage: SubtitleLanguage, onSelectPreferredSubtitleLanguage: (SubtitleLanguage) -> Unit,
    autoDisplaySubtitles: Boolean, onSetAutoDisplaySubtitles: (Boolean) -> Unit,
    updateUi: MobileUpdateUiState, onCheckForUpdates: () -> Unit, onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit, onClearRecent: () -> Unit, onDismiss: () -> Unit,
) {
    val store = (LocalContext.current.applicationContext as AliflixApplication).playerSettingsStore
    val playerSettings by store.settings.collectAsState()
    val context = LocalContext.current
    var contactCard by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }
    if (clearConfirmation) AlertDialog(
        onDismissRequest = { clearConfirmation = false },
        title = { Text("Clear viewing history?") },
        text = { Text("Remove watched titles and reset history-based recommendations. Your saved list stays available.") },
        confirmButton = { TextButton(onClick = { clearConfirmation = false; onClearRecent() }) { Text("Clear history", color = AliflixError) } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") } },
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated), titleContentColor = AliflixContentPrimary,
        textContentColor = AliflixContentSecondary,
        shape = AliflixCorners.Chrome,
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AliflixBackgroundBase, contentColor = AliflixContentPrimary) {
            Column(Modifier.windowInsetsPadding(AliflixInsets.Safe).widthIn(max = 580.dp).fillMaxSize().padding(horizontal = AliflixSpacing.Panel)) {
                Row(Modifier.fillMaxWidth().padding(top = AliflixSpacing.Small, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ALIFLIX", color = AliflixAccentSecondary, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Text("Settings", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    AliflixIconButton(onClick = onDismiss, modifier = Modifier.aliflixSurface(AliflixSurfaceLevel.Content, CircleShape)) {
                        Icon(Icons.Rounded.Close, "Close settings", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("settings-content"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    AliflixSurface(shape = AliflixCorners.Card, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAccount),
                        level = AliflixSurfaceLevel.Content,
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium)) {
                            Box(Modifier.size(42.dp).background(AliflixAccentPrimary.copy(alpha = .18f), AliflixCorners.Small), contentAlignment = Alignment.Center) {
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
                    androidx.compose.runtime.CompositionLocalProvider(LocalUpdateAvailable provides
                        (updateUi.available != null || updateUi.downloadedApk != null)) {
                    SettingsGroup("APP UPDATES") {
                        SettingsRow(Icons.Rounded.SystemUpdate, "App updates", updateUi.message.ifBlank { "Aliflix ${BuildConfig.VERSION_NAME}" }) {
                            if (updateUi.busy) CircularProgressIndicator(Modifier.size(22.dp), color = AliflixAccentSecondary, strokeWidth = 2.dp)
                            else TextButton(onClick = when {
                                updateUi.downloadedApk != null -> onInstallUpdate
                                updateUi.available != null -> onDownloadUpdate
                                else -> onCheckForUpdates
                            }) { Text(when { updateUi.downloadedApk != null -> "Install"; updateUi.available != null -> "Download"; else -> "Check" }, color = AliflixAccentSecondary) }
                        }
                        updateUi.progress?.let { progress -> LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(), color = AliflixAccentPrimary) }
                    }
                    }
                    SettingsGroup("PLAYBACK") {
                        Column(Modifier.padding(horizontal=13.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Preferred quality",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=AliflixContentPrimary)
                            AliflixSegmentedControl(Modifier.fillMaxWidth()) {
                                PreferredVideoQuality.entries.forEach { quality ->
                                    val selected=quality==playerSettings.preferredVideoQuality
                                    Box(Modifier.weight(1f).aliflixSelection(selected,AliflixCorners.Card)
                                        .selectable(selected,role=Role.RadioButton,onClick={store.updatePreferredVideoQuality(quality)})
                                        .testTag("settings-quality-${quality.name.lowercase()}").heightIn(min=48.dp),contentAlignment=Alignment.Center) {
                                        Text(quality.label,color=if(selected)AliflixAccentSecondary else AliflixContentSecondary,fontSize=13.sp)
                                    }
                                }
                            }
                        }
                        SettingsDivider()
                        SettingsToggle(Icons.Rounded.SkipNext, "Play Next Episode", "", playerSettings.playNextEpisode, "settings-next-episode-switch", store::updatePlayNextEpisode)
                    }
                    com.aliflix.app.downloads.DownloadSettings()
                    SettingsGroup("SUBTITLES") {
                        var languageMenu by remember { mutableStateOf(false) }
                        Box {
                            SettingsRow(Icons.Rounded.Translate, "Preferred language", preferredSubtitleLanguage.displayName, onClick = { languageMenu = true }) {
                                Icon(Icons.Rounded.ExpandMore, "Choose subtitle language", tint = AliflixAccentSecondary)
                            }
                            SettingsMenu(languageMenu, { languageMenu = false }, SubtitleLanguage.entries, { it.displayName }, preferredSubtitleLanguage) {
                                languageMenu = false; onSelectPreferredSubtitleLanguage(it)
                            }
                        }
                        SettingsDivider()
                        SettingsToggle(Icons.Rounded.Subtitles, "Auto display", "", autoDisplaySubtitles, "settings-auto-subtitles-switch", onSetAutoDisplaySubtitles)
                    }
                    SettingsGroup("DISCOVER") {
                        SettingsToggle(Icons.Rounded.AutoAwesome, "Ask Aliflix", "Personal recommendations in Discover", aiRecommendationsEnabled, "settings-ask-aliflix-switch", onSetAiRecommendationsEnabled)
                    }
                    SettingsGroup("APP & STORAGE") {

                        SettingsRow(Icons.Rounded.DeleteSweep, "Clear watch history", "Reset watched titles and recommendations", onClick = { clearConfirmation = true }) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = AliflixContentTertiary)
                        }
                    }
                    AliflixSurface(shape = AliflixCorners.Card, level = AliflixSurfaceLevel.Content) {
                        SettingsRow(Icons.Rounded.AlternateEmail, "Get in touch", "Questions, ideas or feedback", onClick = { contactCard = true }) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = AliflixAccentSecondary)
                        }
                    }
                    if (contactCard) ModalBottomSheet(onDismissRequest = { contactCard = false },
                        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated).copy(alpha = 1f),
                        contentColor = AliflixContentPrimary, shape = AliflixCorners.Chrome,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Rounded.MarkEmailRead, null, Modifier.size(38.dp), tint = AliflixAccentSecondary)
                            Text("Get in touch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Have questions about Aliflix, want to suggest a feature, report a bug, or share feedback? We're here to help.",
                                style = MaterialTheme.typography.bodyMedium, color = AliflixContentSecondary)
                            FilledTonalButton(onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO,
                                    android.net.Uri.parse("mailto:Aliflixapp@proton.me?subject=Aliflix%20feedback"))
                                try { context.startActivity(intent) }
                                catch (_: android.content.ActivityNotFoundException) {
                                    android.widget.Toast.makeText(context, "No email app is installed. Email Aliflixapp@proton.me", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }, shape = androidx.compose.foundation.shape.RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.Email, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                                Text("Aliflixapp@proton.me", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    var sourcesExpanded by remember { mutableStateOf(false) }
                    SettingsGroup("STREAMING SOURCES") {
                        SettingsRow(Icons.Rounded.Dns, "Streaming sources", "", onClick = { sourcesExpanded = !sourcesExpanded }) {
                            Icon(if (sourcesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = AliflixAccentSecondary)
                        }
                        androidx.compose.animation.AnimatedVisibility(sourcesExpanded) {
                            Column {
                                val sources = (mobileGeneralPlaybackProviders() + MobilePlaybackProvider.entries.filter { it.isAnimeNative } + PlaybackProviderId.entries.filter { it.isAnimeNative }).distinctBy { it.name }
                                sources.forEach { provider ->
                                    SettingsDivider()
                                    SettingsRow(Icons.Rounded.PlayCircleOutline, provider.displayName, "") { }
                                }
                            }
                        }
                    }
                    Text("ALIFLIX  ·  ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(bottom = AliflixSpacing.Panel), color = AliflixContentTertiary,
                        fontSize = 10.sp, letterSpacing = 1.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = AliflixContentTertiary, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp))
        AliflixSurface(shape = AliflixCorners.Card,
            level = AliflixSurfaceLevel.Content,
        ) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AliflixContentPrimary)
                if (title == "App updates") com.aliflix.app.ui.common.UpdateAvailableDot(LocalUpdateAvailable.current)
            }
            if (detail.isNotBlank()) Text(detail, fontSize = 11.sp, lineHeight = 14.sp, color = AliflixContentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
@Composable private fun SettingsToggle(icon: ImageVector, title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Box(Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        SettingsRow(icon, title, detail) {
            com.aliflix.app.ui.common.AliflixLiquidToggle(checked, null, Modifier.testTag(tag))
        }
    }
}
@Composable private fun <T> SettingsMenu(expanded: Boolean, dismiss: () -> Unit, items: List<T>, label: (T) -> String, selected: T, onSelect: (T) -> Unit) {
    if (!expanded) return
    ModalBottomSheet(onDismissRequest = dismiss,
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated).copy(alpha = 1f),
        contentColor = AliflixContentPrimary, shape = AliflixCorners.Chrome,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                val active = item == selected
                Row(Modifier.fillMaxWidth().aliflixSelection(active, AliflixCorners.Card)
                    .selectable(active, role = Role.RadioButton, onClick = { onSelect(item) })
                    .then(if(item is PreferredVideoQuality) Modifier.testTag("settings-quality-${item.name.lowercase()}") else Modifier)
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label(item), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    AliflixChoiceMark(active)
                }
            }
        }
    }
}

private val LocalUpdateAvailable = androidx.compose.runtime.staticCompositionLocalOf { false }
