# Mobile 3.1.97

Includes collapsible My Space chrome without changing `LibraryScrolling`, overlapping home-tab transitions including the hero, shared translucent navigation colors, compact metadata, expandable episode summaries, dismissible history actions, and a smaller trailer card. The YouTube player implementation is unchanged.

Discover adds a reactive search action, confirmed history deletion, persistent clicked-title shelves, bounded spelling recovery, and keyword browsing. Account shelves include My List, trailers explicitly opened for playback, and title-level deduplication of progress records at least 90% complete. Existing trailer views cannot be reconstructed retroactively.

Categories uses live TMDB movie/TV genre lists. Each selected genre offers popular, top-rated, latest, genre intersections, and six decade refinements (20+ definitions). Requests start after selection and shelves load as they become visible, with three concurrent calls maximum. Pages retain TMDB ordering, fetch up to four pages to fill sparse shelves, and support further pagination. A filter with fewer than 20 real titles cannot supply 20 cards; no padding or unrelated results are added.

Personal matching removes the previous 52% floor, format/era boosts, and title-word overlap. It compares favorites using normalized genre, keyword, creator, cast, and plot features with inverse document frequency and nearest-favorite aggregation. Sparse profiles suppress the score. This is content affinity, not an empirically calibrated probability or a guarantee of enjoyment. Explicit favorites score 100; other scores remain below 100.

Saved-server playback avoids a duplicate preflight player, keeps readiness/fallback handling, and gives the saved provider a short head start rather than waiting for its entire timeout. Next-episode preparation uses a separate muted resolver and byte cache before the end prompt, retries while the current episode plays, and briefly reuses an in-flight preparation on selection. Provider availability, URL expiry, and network throughput still determine startup latency.

Downloads explicitly use foreground service starts, retain their Media3 index/cache, acquire CPU/Wi-Fi locks only during active transfers, and allow more transient retries. Android force-stop, data-sync service time limits, unavailable sources, and storage exhaustion remain platform/resource limits.

Sources informing implementation:

- [TMDB Discover](https://developer.themoviedb.org/reference/discover-movie)
- [TMDB genres](https://developer.themoviedb.org/reference/genre-movie-list)
- [TMDB keywords](https://developer.themoviedb.org/reference/movie-keywords)
- [Content-based recommendations](https://developers.google.com/machine-learning/recommendation/content-based/basics)
- [Media3 download lifecycle](https://developer.android.com/media/media3/exoplayer/downloading-media)

At the user's request, no unit tests, lint, emulator, device, or live functional probes were run. Kotlin compilation and Worker TypeScript compilation passed. Release publication additionally requires the signed CI build and public APK/manifest hash and size verification; compilation alone does not establish runtime behavior.
