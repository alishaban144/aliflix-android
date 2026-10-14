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

/** Bounded text regions; the score is measured on its own line, never after genre chips. */
@Composable
internal fun MobileAskResultCard(
    item: RecommendationCandidate, onClick: () -> Unit, inMyList: Boolean,
    onToggleMyList: () -> Unit, editorial: Boolean, modifier: Modifier = Modifier,
) {
    AliflixSurface(modifier.fillMaxWidth().testTag("ask-result-${item.media.key}")
        .clickable(role = Role.Button, onClick = onClick),
        shape = AliflixCorners.Panel, level = AliflixSurfaceLevel.Content) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(11.dp)) {
            val posterWidth = (maxWidth * .25f).coerceIn(54.dp, 86.dp)
            val score = if (editorial) item.matchScore else item.media.rating
            val scoreFullWidth = maxWidth-posterWidth-12.dp < 85.dp*LocalDensity.current.fontScale
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AskAliflixPoster(item.media, Modifier.width(posterWidth).aspectRatio(2f/3f), cornerRadius = 12.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(item.media.title, color = AliflixContentPrimary, fontSize = 17.sp,
                            lineHeight = 21.sp, fontWeight = FontWeight.ExtraBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().testTag("ask-result-title"))
                        Text(askResultMetadata(item.media), color = AliflixContentSecondary,
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth())
                        if (item.media.genres.isNotEmpty()) Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            item.media.genres.take(2).forEach { genre ->
                                Text(genre, color = AliflixContentSecondary, fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).aliflixSurface(shape = AliflixCorners.Small)
                                        .padding(horizontal = 7.dp, vertical = 4.dp).testTag("ask-result-genre"))
                            }
                        }
                        if (!scoreFullWidth) MobileResultScore(score, editorial)
                    }
                }
                if (scoreFullWidth) MobileResultScore(score, editorial)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AliflixIconButton(onClick = onToggleMyList, modifier = Modifier.size(48.dp).testTag("ask-result-bookmark")) {
                        Icon(if (inMyList) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (inMyList) "Remove ${item.media.title} from My List" else "Add ${item.media.title} to My List",
                            tint = if (inMyList) AliflixAccentSecondary else AliflixContentSecondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("ask-result-details"),
                        contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Text("View details", color = AliflixAccentSecondary, fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,tint = AliflixAccentSecondary,modifier = Modifier.size(15.dp))
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
