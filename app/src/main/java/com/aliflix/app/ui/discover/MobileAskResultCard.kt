package com.aliflix.app.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*
import java.util.Locale

/** One compact composition: poster, information and actions share the same row. */
@Composable
internal fun MobileAskResultCard(
    item: RecommendationCandidate, onClick: () -> Unit, inMyList: Boolean,
    onToggleMyList: () -> Unit, editorial: Boolean, modifier: Modifier = Modifier,
) {
    AliflixSurface(modifier.fillMaxWidth().testTag("ask-result-${item.media.key}")
        .clickable(role = Role.Button, onClick = onClick),
        shape = AliflixCorners.Panel, level = AliflixSurfaceLevel.Content) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val posterWidth = (maxWidth * .25f).coerceIn(54.dp, 82.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(posterWidth)) {
                    AskAliflixPoster(item.media, Modifier.fillMaxWidth().aspectRatio(2f/3f), cornerRadius = 12.dp)
                    AliflixIconButton(onClick = onToggleMyList,
                        modifier = Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("ask-result-bookmark"),
                        level = AliflixSurfaceLevel.Elevated) {
                        Icon(if (inMyList) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (inMyList) "Remove ${item.media.title} from My List" else "Add ${item.media.title} to My List",
                            tint = if (inMyList) AliflixAccentSecondary else AliflixContentPrimary, modifier = Modifier.size(19.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.media.title, color = AliflixContentPrimary, fontSize = 16.sp,
                        lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().testTag("ask-result-title"))
                    Text(askResultMetadata(item.media), color = AliflixContentSecondary,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.media.genres.isNotEmpty()) Text(item.media.genres.take(2).joinToString(" · "),
                        color = AliflixContentSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().testTag("ask-result-genre"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { MobileResultScore(if (editorial) item.matchScore else item.media.rating, editorial) }
                        IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag("ask-result-details")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "View details for ${item.media.title}",
                                tint = AliflixAccentSecondary, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileResultScore(score: Double, editorial: Boolean) {
    if (score <= 0.0) return
    Row(Modifier.fillMaxWidth().testTag("ask-result-score"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(Icons.Rounded.Star,null,tint = androidx.compose.ui.graphics.Color(0xFFFFC857),modifier = Modifier.size(13.dp))
        Text("${if (editorial) "AI" else "TMDB"} ${String.format(Locale.ROOT,"%.1f",score)}",
            color = AliflixContentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
