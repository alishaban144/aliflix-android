package com.aliflix.app.ui.discover

import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.AliflixLogoMark
import com.aliflix.app.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val discoverQuickStarters = listOf(
    "Mind-bending sci-fi" to "Mind-bending sci-fi films",
    "Hidden gem thrillers" to "Hidden gem thrillers",
    "Cozy comedy" to "Feel-good movies under 100 min",
    "Series like Dark" to "Ended mystery series like Dark",
    "Korean thrillers" to "Best Korean thrillers",
    "Surprise me" to "Surprise me",
)

/** A compact orbital treatment around the canonical brand asset. */
@Composable
internal fun DiscoverAskCard(touchTracker: DiscoverTouchTracker = remember { DiscoverTouchTracker() }, onMode: (Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    AliflixSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AliflixSpacing.Content, vertical = AliflixSpacing.Small)
            .then(Modifier.clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                openAskConversation(context)
            }),
        shape = AliflixCorners.Panel,
        level = AliflixSurfaceLevel.Content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(84.dp)
                    .then(Modifier.clip(RoundedCornerShape(22.dp)).discoverSmokeBackground()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(start = 18.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "Ask Aliflix",
                        maxLines = 1,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = AliflixContentPrimary,
                    )
                    Text(
                        text = "Instant Hydrogen picks & live voice",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = AliflixContentSecondary,
                    )
                }
                DiscoverLiveOrb(touchTracker, Modifier.size(76.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (listOf("Ask", "Talk")).forEachIndexed { index, label ->
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (index == 1) context.startActivity(android.content.Intent().setClassName(context, "com.aliflix.app.talk.TalkVoiceActivity"))
                            else openAskConversation(context)
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = AliflixCorners.Small,
                        contentPadding = PaddingValues(horizontal = 9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (index == 0) AliflixAccentSecondary else AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
                            contentColor = if (index == 0) com.aliflix.app.ui.theme.AliflixContentInverse else Color.White,
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(label, Modifier.padding(horizontal = 27.dp), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Box(Modifier.align(Alignment.CenterEnd).size(24.dp), contentAlignment = Alignment.Center) {
                                if (index == 1) AliflixCompanionIcon(Modifier.size(21.dp), Color.White)
                                else Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(discoverQuickStarters, key = { it.first }) { (label, prompt) ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color.White.copy(alpha = 0.055f))
                            .border(0.75.dp, AliflixAccentSecondary.copy(alpha = 0.22f), RoundedCornerShape(100.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                openAskConversation(context, initialPrompt = prompt)
                            }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = AliflixAccentSecondary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = label,
                            color = AliflixContentPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
