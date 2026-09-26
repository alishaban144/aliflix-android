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

Animepower returns HLS masters on third-party CDN hosts that reject requests without a matching `Referer`; a request without it is answered with `403`. Its JSON carries an empty `headers` object and no per-source origin, so the parser defaults every stream's referer to `https://anikuro.to/`, and a provider-supplied `headers.Referer` or `upstreamReferer` only ever replaces that default when it is itself a valid `https` origin. `NativePlaybackService` derives `Origin` from the same referer. `streamUrlRules` stays empty because AniKuro already publishes resolved playlist URLs, so `resolveStreamUrl` returns the URL unchanged.

## Health checks never discard a stream

`StartupStreamCache.awaitPlayable` is a fast probe used to decide which source starts first, not an admission test. A candidate that resolved a playable URL but did not pass the probe is still returned to the player, and a stream is only skipped when the provider reported no source at all. The player's own `awaitNativeReady` gate and the surrounding retry loop then decide the outcome, so a single slow or flaky probe can no longer turn an available episode into "We couldn't prepare this title".

## Racing

`NativePlayerActivity` builds one batch of every anime-native candidate (`MIRURO` and `ANIKURO`) and runs them through `firstSuccessful` with parallelism 2. Both candidates resolve with `validateSingle = true`, so a source that passes the probe wins, and one that does not still gets handed to the player. Resolve budgets are 45s for Miruro, 60s for AniKuro and 16s for the general web sources.

If every anime-native source reports no source at all, the general sources (Ramoflix, Doraby, Moviepire) are attempted as before.

## Validation

Unit tests and Android lint passed for this release (306 unit tests), covering AniKuro payload parsing, origin defaulting, origin precedence, legacy payload shapes, asset decompression and anime fallback ordering. Emulator device playback was explicitly waived for this release, so on-device playback remains unverified by automation. The live catalogue was verified by hand for One Piece season 8 and season 11 through to MPEG-TS segments.

