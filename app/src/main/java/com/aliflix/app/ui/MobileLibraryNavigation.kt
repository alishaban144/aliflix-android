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
