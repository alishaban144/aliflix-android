# Phone startup and subtitle matching — v3.1.72

## Changes

- Race up to two muted provider resolvers at once. The first successful request wins; cancel and join the other jobs and destroy every temporary WebView before native playback takes ownership. Failures do not win the race. Explicit manual server selection remains specific to that server.
- Search for subtitles immediately alongside stream resolution. Prepare native video paused, inspect its embedded text tracks, complete subtitle preparation, and then play. Remove the former 1.5-second subtitle grace period. Retry an empty or missing-preferred-language search, and try ranked downloads in pairs within a bounded deadline. A completed unsuccessful search is shown as unavailable in the native subtitle sheet; it is not represented as successfully synchronized captions.
- Group language aliases such as en, ENG and English under one display name while retaining distinct release choices. Phone search adds preferred-language episode and season queries; combines provider results before trimming; and exposes up to 240 options. TV retains its prior search limits and behavior.
- For range-capable whole-file streams, calculate the OpenSubtitles checksum from the first/last 64 KiB and file length and pass it with the filename to the subtitle provider. HLS/DASH are not given fabricated file hashes. A provider must explicitly confirm a hash match before it is labeled as such.
- Select an episode's exact filename inside season-pack ZIPs. Reject missing/ambiguous episode files instead of choosing the nearest common filename prefix. Keep legacy decoding and damaged-language rejection from v3.1.71.

## Evidence

- 240 Android unit tests passed, including bounded racing, cancellation cleanup, alias normalization, exact archive episode choice, hash arithmetic, encoding repair and release identity.
- 119 Worker tests passed, including an English result beyond the former first-35 cutoff, targeted episode queries and fingerprint forwarding without a false exact-match claim. Typecheck and Wrangler dry-run passed.
- Updated Worker deployed as version `ce532b61-8943-415a-8f52-20bb16d1e3bd`. A live phone-style query for Game of Thrones S1E1 returned 161 tracks, including 48 English releases grouped as English.
- API37 live Game of Thrones S1E1 startup passed twice with automatic subtitles enabled, no provider-screen interaction, decoded video, a selected audio track and continued playback after seeking. The stronger test measured two simultaneous resolver views, zero remaining at handoff, an automatically selected English episode release, and an actual cue rendered at its timestamp. Logs/screenshots remain in the workspace validation folder.
- The deterministic release device suite also covers first/late caption rendering, hold/drag persistence, Back pausing, embedded subtitle preference, background playback, notification controls, surface recovery, UI controls and home/settings behavior.
- TV stays v3.1.69/code159; its published artifact is reused unchanged.

## Practical limits

Video timestamps can be matched directly for embedded subtitles or provider-confirmed file matches. An external subtitle for an unidentified stream may still belong to a different cut. No arbitrary offset or frame-rate conversion is applied without evidence. Physical Nothing Phone/TV screen-lock mirroring was not available for hardware validation in this release.

References: [SubDL search API](https://subdl.com/api-doc), [Stremio subtitle request hints](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineSubtitlesHandler.md), [OpenSubtitles hash algorithm](https://trac.opensubtitles.org/opensubtitles/wiki/HashSourceCodes).
