# AniKuro native source

Added in v3.1.108 as a second Japanese-animation source alongside Miruro. AniKuro publishes an unauthenticated JSON catalogue, so the adapter talks to it directly over HTTPS with ordinary HTTP requests; no browser frame, WebView, or provider page is loaded. Native Media3 remains responsible for playback.

## Catalogue endpoints

All requests go to `https://anikuro.to` with a `Referer` of `https://anikuro.to/`. Responses are plain JSON and require no session, cookie, or Turnstile token.

| Purpose | Request |
| --- | --- |
| Primary stream variants | `GET /api/v1/animepower/video/{anilistId}/{episode}` |
| Secondary mirrors | `GET /api/v1/sources/{provider}/{anilistId}:{episode}` |
| Discovery (unused) | `GET /api/v1/discovery/search?q={query}` |

The primary endpoint answers with `data.normalized[]`, each entry carrying a `variant` (`sub` or `dub`), a `sources[]` list of `url`/`quality`/`type`/`isM3U8`, optional `subtitles[]`, and request `headers`. The animepower backends alternate `sub` and `dub` between requests, so the adapter retries until a Japanese-audio (`sub`) variant with a usable source arrives, and only falls back to `dub` when every attempt comes back `dub`.

Secondary mirrors use the site's own priority order and are raced only when animepower returns nothing for the episode. Each response is normalised the same way: prefer `data.normalized[]`, otherwise derive `sub` and `dub` variants from `data.raw`.

## Playback identity

AniKuro is keyed by AniList id and episode number. The adapter reuses the compressed mobile asset `anime-tmdb-anilist.json.gz` (TMDB-to-AniList edges from https://github.com/anibridge/anibridge-mappings/releases/download/v3/mappings.min.json, 9,177 title/season records) through the shared `AniListEpisodeMapping`, which both anime-native catalogues call. A unique exact title/type/year AniList match is used only for unmapped movies and first seasons. Ambiguous or split/merged episode mappings are rejected rather than playing the wrong episode.

## Origin handling

Animepower returns HLS masters on third-party CDN hosts that reject requests without a matching `Referer`. Every request therefore carries `referer = https://anikuro.to/` unless the source supplies its own `headers.Referer`, and `NativePlaybackService` derives `Origin` from that referer. `streamUrlRules` stays empty because AniKuro already publishes resolved playlist URLs, so `resolveStreamUrl` returns the URL unchanged.

## Racing

`NativePlayerActivity` builds one batch of every anime-native candidate (`MIRURO` and `ANIKURO`) and runs them through `firstSuccessful` with parallelism 2. Both candidates resolve with `validateSingle = true`, so each must pass `StartupStreamCache.awaitPlayable` — meaning the winner is the source whose video actually starts buffering first, not merely the one that returns metadata first. Resolve budgets are 45s for Miruro, 60s for AniKuro and 16s for the general web sources.

If every anime-native source fails, the general sources (Ramoflix, Doraby, Moviepire) are attempted as before.

## Validation

Unit tests and Android lint passed for this release (295 unit tests, including fallback ordering, anime-native availability and episode identity). Emulator device playback was explicitly waived for this release, so AniKuro provider availability and on-device playback remain unverified by automation.
