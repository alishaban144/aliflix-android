package com.aliflix.app.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

@Composable
internal fun AskAliflixSeriesStatusSelector(
    selectedStatus: String?,
    onStatusSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Series status",
            color = AliflixContentSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        listOf(
            "Any" to null,
            "Returning" to "Returning Series",
            "Ended" to "Ended",
        ).forEach { (label, value) ->
            val selected = selectedStatus == value
            Surface(
                modifier = Modifier.clickable { onStatusSelected(value) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) AliflixAccentPrimary.copy(alpha = .18f) else AliflixSurfaceElevated,
                border = BorderStroke(1.dp, if (selected) AliflixAccentPrimary else AliflixBorderSubtle),
            ) {
                Text(
                    text = label,
                    color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}
