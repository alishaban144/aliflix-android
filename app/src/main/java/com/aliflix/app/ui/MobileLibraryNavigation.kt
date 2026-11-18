package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.theme.*
import com.aliflix.app.ui.common.AliflixSegmentedControl
import com.aliflix.app.ui.common.aliflixSelection
import com.aliflix.app.ui.common.AliflixSpacing
import com.aliflix.app.ui.common.AliflixCorners

/** Restore the original collection tabs; the My List genre navigation is independent. */
@Composable
internal fun MobileLibraryTabs(tabs: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    AliflixSegmentedControl(
        modifier = Modifier.padding(horizontal = AliflixSpacing.Content, vertical = AliflixSpacing.Small)
            .fillMaxWidth().selectableGroup().testTag("library-tabs"),
        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
    ) {
        tabs.forEachIndexed { index, (label, count) ->
            val active = selected == index
            Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(AliflixCorners.Small).aliflixSelection(active)
                .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                .testTag("library-tab-$index").padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, color = if (active) AliflixContentPrimary else AliflixContentSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count.toString(), color = if (active) AliflixAccentSecondary else AliflixContentTertiary,
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun MobileLibraryGenre(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) AliflixAccentSecondary.copy(alpha = .13f) else androidx.compose.ui.graphics.Color.Transparent)
        .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 7.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.width(2.dp).height(26.dp)
            .background(if (selected) AliflixAccentSecondary else AliflixContentTertiary.copy(alpha = .15f), RoundedCornerShape(1.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (selected) AliflixAccentSecondary else AliflixContentSecondary,
                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected) Text(count.toString(), color = AliflixContentSecondary, fontSize = 10.sp)
        }
    }
}
