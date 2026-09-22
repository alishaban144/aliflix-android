from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    found = text.count(old)
    if found != 1:
        raise RuntimeError(f'{description}: expected 1 matching block; found {found}')
    return text.replace(old, new, 1)

app = Path('app/src/main/java/com/aliflix/app/ui/AliflixApp.kt')
src = app.read_text(encoding='utf-8')

old_scroll = '''    var chromeVisible by remember { mutableStateOf(true) }
    val chromeScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private var travel = 0f
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    if (consumed.y * travel < 0) travel = 0f
                    travel += consumed.y
                    if (travel < -48f) chromeVisible = false
                    if (travel > 32f || available.y > 12f) chromeVisible = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(pagerState.settledPage) { chromeVisible = true }'''
new_scroll = '''    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTravel by remember { mutableFloatStateOf(0f) }
    val maxFlingVelocity = with(LocalDensity.current) { 4600.dp.toPx() }
    val chromeScroll = remember(maxFlingVelocity) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    if (consumed.y * chromeTravel < 0f) chromeTravel = 0f
                    chromeTravel = (chromeTravel + consumed.y).coerceIn(-72f, 72f)
                    if (chromeTravel < -48f) chromeVisible = false
                    if (chromeTravel > 32f || available.y > 12f) chromeVisible = true
                }
                // Only observe drag distance; never eat pointer movement.
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                // Retain native fling physics except for extreme vertical impulses.
                // Consume ONLY the excess, never horizontal swipes between tabs.
                if (kotlin.math.abs(available.y) <= maxFlingVelocity ||
                    kotlin.math.abs(available.x) >= kotlin.math.abs(available.y)) {
                    return androidx.compose.ui.unit.Velocity.Zero
                }
                return androidx.compose.ui.unit.Velocity(
                    0f,
                    available.y - available.y.coerceIn(-maxFlingVelocity, maxFlingVelocity),
                )
            }
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        chromeTravel = 0f
        chromeVisible = true
    }'''
src = replace_once(src, old_scroll, new_scroll, 'My Space fling')

old_badges = '''            if (showLibraryMetadata) {
                Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOfNotNull(item.rating.takeIf { it > 0 }?.let { "%.1f".format(Locale.ROOT, it) },
                        item.runtime.takeIf(String::isNotBlank)).forEach { value ->
                        Text(value, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .72f))
                                .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                }
            }'''
new_badges = '''            if (showLibraryMetadata) {
                val posterFacts = listOfNotNull(
                    item.rating.takeIf { it > 0.0 }?.let { "★ %.1f".format(Locale.ROOT, it) },
                    item.runtime.trim().takeIf(String::isNotBlank),
                )
                if (posterFacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .padding(start = 7.dp, end = 7.dp, bottom = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        posterFacts.forEachIndexed { index, value ->
                            Text(
                                text = value,
                                color = if (index == 0 && item.rating > 0.0) AliflixAccentSecondary else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .then(if (index == posterFacts.lastIndex) Modifier.weight(1f, fill = false) else Modifier)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color.Black.copy(alpha = .79f))
                                    .border(1.dp, AliflixAccentSecondary.copy(alpha = .24f), RoundedCornerShape(7.dp))
                                    .padding(horizontal = 5.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }'''
src = replace_once(src, old_badges, new_badges, 'poster metadata pills')

old_title = '''        Text(
            text = item.title,
            color = AliflixContentPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (showLibraryMetadata) Modifier else Modifier.height(34.dp),
        )
        if (item.year.isNotBlank()) {
            Text(
                text = item.year,
                color = AliflixMuted,
                fontSize = 11.sp,
            )
        }'''
new_title = '''        Text(
            text = item.title + if (showLibraryMetadata && item.year.isNotBlank()) "  ·  ${item.year}" else "",
            color = AliflixContentPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (showLibraryMetadata) Modifier else Modifier.height(34.dp),
        )
        if (item.year.isNotBlank() && !showLibraryMetadata) {
            Text(
                text = item.year,
                color = AliflixMuted,
                fontSize = 11.sp,
            )
        }'''
src = replace_once(src, old_title, new_title, 'adjacent library year')
app.write_text(src, encoding='utf-8')

picker = Path('app/src/mobile/java/com/aliflix/app/downloads/DownloadUi.kt')
src = picker.read_text(encoding='utf-8')
src = replace_once(src, 'LaunchedEffect(media.key, season, language, retry, episodes) {\n        if (!activity.hasInternetConnection())',
                   'LaunchedEffect(media.key, season, language, retry, episodes, selectedKeys) {\n        if (!activity.hasInternetConnection())', 'selected-preparation keys')
src = replace_once(src, 'session.prepare(store, selections.filter { it.first in eligible }.map { (key, raw) -> key to raw.copy(source = prefs.sourceFor(media)) }, language)',
                   'session.prepare(store, selections.filter { it.first in selectedKeys }.map { (key, raw) -> key to raw.copy(source = prefs.sourceFor(media)) }, language)',
                   'prepare only selected episodes')
src = replace_once(src, 'val ready = allValidated && !preparing && !listLoading',
                   'val ready = allValidated && !preparing', 'independent download readiness')
picker.write_text(src, encoding='utf-8')
print('My Space velocity, library metadata and selected-only download readiness updated.')
