package com.aliflix.app.ui.discover

import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary

@Composable
internal fun AskAliflixSeriesStatusSelector(
    selectedStatus: String?,
    onStatusSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AliflixSpacing.Content, vertical = 6.dp),
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
            AliflixSurface(
                modifier = Modifier.clickable { onStatusSelected(value) },
                shape = AliflixCorners.Small,
                level = if (selected) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content,
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
