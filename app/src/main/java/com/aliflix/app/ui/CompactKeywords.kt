package com.aliflix.app.ui

import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaKeyword
import com.aliflix.app.ui.theme.*

/** Two compact rows keep even a large keyword catalogue within one small section. */
@Composable
internal fun CompactKeywords(keywords: List<MediaKeyword>, onOpen: (MediaKeyword) -> Unit) {
    val rows = remember(keywords) {
        val rowCount = if (keywords.size > 4) 2 else 1
        List(rowCount) { row -> keywords.filterIndexed { index, _ -> index % rowCount == row } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
        rows.forEach { row ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
                items(row, key = { it.id }) { keyword ->
                    AliflixSurface(
                        onClick = { onOpen(keyword) },
                        shape = AliflixCorners.Small,
                        level = AliflixSurfaceLevel.Content,
                    ) {
                        Text(
                            keyword.name,
                            modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = AliflixSpacing.Medium, vertical = 10.dp),
                            fontSize = 12.sp,
                            color = AliflixContentSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
