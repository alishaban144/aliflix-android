# Mobile v3.1.177

Baseline: published v3.1.176 (`5aaa915f`). Mobile version 267. TV version/source behavior and native dependencies are unchanged.

## Behavior

- Talk supports voice and native text conversation, a top typing/mode icon, stop/retry, selectable/copyable replies, reading-aware scrolling, source links, fresh/resumed chats, and icon-only recent chats. Archives hold up to 30 conversations and 64 messages, bounded to 21 KB per conversation, with two references per message and 90-day retention. Turning off future history preserves existing chats; Delete history is explicit. Account archives use the verified account hash shared across device tokens; guest archives stay local. Deletes use tombstones and a full-clear cutoff, offline changes retry, and archive actions have separate rate keys from live tools.
- New questions cancel earlier lookups. Typed replacement uses a fresh transport boundary because Live completions have no client question ID. Current prompts outrank history. Recommendations require Tavily-backed research, verified identities and independent evaluation, default to the current year and preceding three years, and preserve expressly requested older eras. Talk asks for three choices from a smaller research pool to reduce quota/latency cost. On quota/outage, simple genre discovery can reuse matching released titles from a recently evaluated research collection, excluding watched/favorite/rejected/recently suggested titles. Unsupported premises and older-era constraints are never silently widened.
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
| Background preparation | Undone prepared silently; Play transferred the pending request, reached visible playback in 2.3 s, then resumed after a three-minute seek. |
| Talk | Four UI tests passed, including the focused composer. A settled keyboard screenshot verifies the header stays visible and the composer sits directly above the keys. Replacing an episode explanation with a sci-fi request returned three researched 2026 identities and a relevant answer in 21.7 s, without old episode output. New AI research was quota-limited; retained Tavily research recovery was verified live. |
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

Local mobile build and 708 unit tests completed with zero failures/errors (three pre-existing skips). Worker typecheck, 231 tests across 24 files, and Wrangler deployment dry-run passed. The authenticated route regression proves two device tokens from one account see the same archive while another account cannot, and live-tool capacity is not consumed by archive requests. Cache recovery tests reject wrong eras, future releases, old collections, unscored results and unsupported narrative/exclusion constraints.

The release workflow runs the full API 35 suite, mobile unit tests, lint, signed APK ZIP/certificate checks, version/tag assertions, and byte-for-byte native/model parity against the original mobile binaries. Local lint stalled in the Kotlin UAST scanner; successful CI lint is required before publication. Public APK hashes, sizes, ABIs and signer are independently checked after release.

## October 21 continuation: startup sync and conversation reliability

Mobile owns account services and shared repositories for the application lifetime. Initialization runs alongside startup without awaiting network hydration or changing the loading animation. Screen recreation and direct player/Talk entry retain synchronization. Remote listeners attach before uploading the initial backlog. Actions publish immediately; regular playing-position checkpoints remain bounded at five seconds, with urgent pause/seek/completion updates. Offline changes persist and reconcile on reconnect/startup.

Favorites, My List, recent history, resume/completion, player and subtitle preferences/payloads/corrections, remembered audio choices, activity shelves, rejected recommendations and Talk preferences are account scoped. Library/progress/history removals leave revisioned deletion records; startup merging cannot revive stale removed entries. Metadata enrichment cannot re-add a removed favorite. Large preference payload compression/decompression runs away from the UI thread. Local download files, authentication tokens, server credentials and device performance caches remain device-local.

Talk's outbox now belongs to the process, rather than a closing activity. A private Firestore signal refreshes archives on another signed-in device. Uploads are serialized; a new message or deletion during an upload survives an older server summary. Account changes fence transcript writes, opening a saved chat is latest-selection-owned, and stopping/backgrounding invalidates pending work. Stable conversation identity preserves drafts while the transcript changes. Text mode uses a quiet background and keeps the header/composer above the keyboard. History and voice controls remain accessible through the compact settings menu.

Physical Pixel checks: five Talk UI cases plus the shared-service lifecycle case passed. The settled keyboard screenshot was visually inspected. Rapid typed replacement, Stop and the next message passed live. An opt-in two-client Firebase test uses isolated stores and a disposable account, preserving the viewer's default account and app data. It verified library propagation (1.66 s in the initial successful run), remote removal, resume/completion, audio choice, Talk preferences, dislikes, activity shelves, caption payloads, history/progress deletion and rejection of an older offline add after a newer removal. The disposable account was deleted. These are two independent clients on one physical phone, not two physical-device measurements.

The signed-in test caught an initialization-order crash before release; account context now initializes before the immediate sync coroutine. The initial live genre retry also exposed HTTP 422 `NO_RESEARCH`: fresh inference had no usable verified evidence despite a valid recent researched collection. Retained simple-genre recovery now handles that specific response and transient service failures, with the captured current question authoritative; unsupported premises still fail closed. Relative follow-ups retain the conversation-grounded expansion. The final deployed discovery path is rechecked before publication.

Additional limit: an isolated CineJoy audio/seek probe verified the first two audio choices; a later third-track fragment returned upstream HTTP 502 and the sustained all-track soak did not pass. Actual PCM audio-selection regressions passed separately. This does not establish universal file/rendition availability.

Final dialogue hardening: a live stop/next-message test exposed an abandoned Arrival sentence. Stopped and superseded turns now retain a visible stopped state in local/account history while being excluded from restored model context. Empty continuity payloads and abandoned automatic-continuation flags are suppressed. The regression explicitly rejects that prior title/topic in the next answer. The refreshed provider audit validated ten providers on the standard titles plus Filmo/Interstellar in 2.9 s; AniKuro remains an unreachable origin.

Terminal-action checks: history deletion cancels the pending sender before clearing the archive; the live-session close does not race another account-wide clear against future messages. An isolated phone fixture verifies turning history off preserves saved chats and deletion cannot revive a pending reply. Saved unanswered prompts are marked stopped when reopened. HTTP 429 keeps its quota state and stops reconnect retries immediately; voice changes and terminal suspension retire unfinished work. Account changes clear owner-specific legacy suggestion caches.

First-sync import protection: missing local library items and recent history enter durable outboxes before remote listeners attach. Create-only seeds cannot overwrite an existing remote item or deletion. Pending writes retire only when an authoritative watch snapshot observes the committed version, avoiding commit/watch ordering gaps. The live seeded test held 32 favorites throughout (observed counts: [32]), shared those favorites and four history entries, and passed the rest of the two-client/offline/deletion checks; a later library change propagated in 603 ms. An unchanged reader no longer saves an older transcript over a newer answer received from another client.
