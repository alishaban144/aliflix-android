# Mobile v3.1.177

Baseline: published v3.1.176 (`5aaa915f`). Mobile version 267. TV version/source behavior and native dependencies are unchanged.

## Behavior

- Talk supports voice and native text conversation, source links, fresh/resumed chats, and an icon-only recent-chat action. Consent-controlled archives hold up to 30 conversations, twelve bounded messages per conversation and two references per message. Account archives use the verified account hash, shared across device tokens; guest archives stay local. Deletes use tombstones, offline changes retry, and archive actions have separate rate keys from live tools.
- New questions cancel earlier lookups. Typed replacement uses a fresh transport boundary because Live completions have no client question ID. Current prompts outrank history. Recommendations require Tavily-backed research, verified identities and independent evaluation, default to the current year and preceding three years, and preserve expressly requested older eras. Talk asks for three choices from a smaller research pool to reduce quota/latency cost.
- Details start muted preparation before Play, including an initial episode while episode metadata loads. Filmo, Serienstream and AniWorld keep priority launch order; alternatives race after 900 ms. Explicit server pins remain strict, while saved hints allow other mirrors. Host mirrors race, definite missing files skip browser retry, and AniKuro stops repeating shared-origin transport outages.
- Ratings do not gate episodes or playback. Exact IMDb identity supports re-releases; the verified original year can resolve the independent Rotten Tomatoes record. No critic score, parent-series score or remake score is invented.
- Picked for You compares stable taste identity and retains existing rail order during metadata enrichment. Play and first-frame artwork/loading transitions fade smoothly.

## Physical Pixel 7a evidence

Existing app data was preserved. The phone had a Debug-signed installation and was signed out of Firebase. Production signer integrity is a separate release workflow/public-artifact gate.

| Path | Observation |
| --- | --- |
| Minority Report (2002), TMDB 180 | First frame 3.7 s, native CineJoy; seek/playback passed. IMDb 7.6 and RT 89 independently verified. |
| Don't Hug Me I'm Scared (2011), TMDB 86328 S1E1 | First frame 4.7 s, native Moviepire; seek/playback passed. Six original web episodes loaded, beginning with Creativity. No 2022 TV-series rating substituted. |
| Incoherence, TMDB 67415 / IMDb tt1997399 | Catalog re-release year 2025; IMDb original year 1994 and score 6.7 verified. RT correctly reports no critic score. No playable provider file found in the live test. |
| Home details/back | Picked rail identities/order preserved on the phone. |
| Talk | Recent-chat, privacy, voice-picker and full-screen-touch tests passed. Replacing an episode explanation with a sci-fi request reached the correct recommendation tool without the old episode answer. Research returned HTTP 429; successful live recommendation quality/count is unverified. |
| Player regressions | Ten physical tests passed: muted preparation, resume across routes, audio-only progress, skip/up-next/caption layouts and Talk controls. |

Direct native-video preparation, without automatic provider substitution:

| Provider | Title | Result |
| --- | --- | --- |
| Flixer | Minority Report | Native buffer validated |
| Serienstream | Dark S1E1 | German VOE validated |
| Filmo | Interstellar | VOE validated; Minority Report files returned 404 |
| AniWorld | Black Clover S1E1 | Japanese / English subtitles VOE validated in 2.9 s after canonical identity fix |
| Seven Movies | Minority Report | Vidbolt validated |
| Movy | Minority Report | Native buffer validated |
| CineJoy | Minority Report | Nebula validated |
| RamoFlix | Minority Report | Native buffer validated |
| Doraby | Minority Report | Native buffer validated |
| Moviepire | Minority Report | Native buffer validated |
| Miruro | Black Clover S1E1 | Native buffer validated |
| AniKuro | Black Clover / One Piece | Public origin timed out on phone and host; both publisher-listed domains were unreachable |

Preparation validates actual Media3 video/audio selection and buffered data. It is distinct from first-frame/seek proof, which was exercised for the reported available titles. An unavailable external file or origin cannot be guaranteed by an adapter change.

## Automated gates

Local mobile build and 702 unit tests completed with zero failures/errors (three pre-existing skips). Worker typecheck, 227 tests across 24 files, and Wrangler deployment dry-run passed. The added authenticated route regression proves two device tokens from one account see the same archive while another account cannot, and live-tool capacity is not consumed by archive requests. Physical account transport remains unverified on the signed-out phone.

The release workflow runs the full API 35 suite, mobile unit tests, lint, signed APK ZIP/certificate checks, version/tag assertions, and byte-for-byte native/model parity against the original mobile binaries. Local lint stalled in the Kotlin UAST scanner; successful CI lint is required before publication. Public APK hashes, sizes, ABIs and signer are independently checked after release.
