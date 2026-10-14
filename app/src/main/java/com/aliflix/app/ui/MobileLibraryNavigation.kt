package com.aliflix.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.theme.*

/** Content-sized navigation remains readable at large font sizes; no nested panel. */
@Composable
internal fun MobileLibraryTabs(tabs: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().selectableGroup().testTag("library-tabs")
        .drawBehind { drawLine(AliflixContentSecondary.copy(alpha = .10f), Offset(0f,size.height), Offset(size.width,size.height), 1.dp.toPx()) },
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        itemsIndexed(tabs) { index, (label, count) ->
            val active = selected == index
            val emphasis = animateFloatAsState(if (active) 1f else 0f, label = "library-tab")
            Column(Modifier.width(IntrinsicSize.Max).heightIn(min = 56.dp)
                .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                .testTag("library-tab-$index"), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(label, color = if (active) AliflixContentPrimary else AliflixContentSecondary,
                        fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    Text(count.toString(), color = if (active) AliflixAccentSecondary else AliflixContentTertiary,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = emphasis.value; scaleX = .4f + .6f * emphasis.value }
                    .background(AliflixAccentSecondary, RoundedCornerShape(1.dp)))
            }
        }
    }
}

@Composable
internal fun MobileLibraryGenre(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.widthIn(max = 240.dp).heightIn(min = 48.dp)
        .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 2.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = if (selected) AliflixAccentSecondary else AliflixContentSecondary,
                fontSize = 12.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(count.toString(), color = AliflixContentTertiary, fontSize = 10.sp)
        }
        Box(Modifier.width(if (selected) 20.dp else 8.dp).height(2.dp)
            .background(if (selected) AliflixAccentSecondary else AliflixContentTertiary.copy(alpha = .3f), RoundedCornerShape(1.dp)))
    }
}
