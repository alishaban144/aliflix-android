# Mobile 3.1.179 validation

This release addresses conversation continuity and the complete discovery journey. TV UI and version are unchanged.

## Root causes and changes

- Voice cards previously came only from replies after the newest user message. A conversation-owned card context now survives speech, interruption, follow-ups and mode changes. Selecting a known title focuses it immediately; the group cannot collapse merely because the assistant mentions one recommendation. New chats, cleared history and account changes clear card context.
- The capable mobile client exposes a real `show_title_cards` action. Known cards are reused locally; new titles use bounded TMDB identity lookup. The selected card centers while other cards rotate, fade and contract away. Groups show all three choices together. Chat keeps the active cards beside its orb and changing topic.
- Talk answers greetings and general conversation. Microphone readiness includes paced remote VAD priming. Voice activity arms a bounded response deadline even before transcription; a silent control-only completion cannot finish an unanswered user turn. Search continues to use Tavily and contextual spoken bridges.
- Series matches were discarded by a final conversion using movie `title`/`release_date` fields instead of TV `name`/`first_air_date`. The regression now exercises both media types through the complete selection/conversion/filter path.
- Similar resolves canonical anchor identity, release year, original title, creators and keywords, then uses one Tavily search, one Gemini selection and direct TMDB matching. Strong familiar connections take priority over novelty. Watched/favorited titles remain eligible for Similar; explicit dislikes, anchors and filters remain excluded. No Clef stage was added.
- Submitting Similar after Describe no longer reuses the old Describe request. Ask now has one scrolling content column, a two-column discovery grid, title years, and stable top positioning while batches arrive. A deliberate user scroll preserves reading position.

## Acceptance checks

On the authenticated Pixel 7a (`41301JEHN02777`), exercise a continuous conversation: spoken Hi, spoken Hello, a general question, movies about gentlemen, selecting the third title, a pronoun follow-up, explicitly showing the same card, showing a new series card, and changing to an unrelated subject. Also exercise typed chat greetings and card requests. Verify rendered audio for voice turns and retained card identity for same-title follow-ups.

Verify all three series requests through the authenticated Android client and live service:

| Anchor | Required result |
| --- | --- |
| Breaking Bad (2008), TMDB 1396 | Better Call Saul |
| The Killing / Forbrydelsen (2007), TMDB 32368 | The Chestnut Man |
| The Killing (2011), TMDB 34415 | The Chestnut Man |

The catalogue has no exact 2017 series named The Killing; both established series identities are covered. The live host checks returned 24 titles for each, including the required result. Better Call Saul remained present when supplied as watched. Search findings were cached on these repeats; each used one fresh Gemini selection, no GPT/Clef calls, and real TMDB identities. First results arrived in 0.79–0.94 seconds and completion in 5.55–6.23 seconds on these measured runs; these are observations, not latency guarantees.

Physical UI regressions cover speaking without losing cards, focus/centering, same-title persistence, chat card placement, new search positioning, batch reordering, intentional scrolling, switching Describe to Similar, Surprise Me and immediate in-card dislike. Instrumentation fixtures are distinguished from the live service checks.

## Build and publication gates

Local Android unit results: 716 tests, zero failures/errors, three skipped. Mobile lint, debug and device-test assembly pass; shared code also compiles for TV. Worker: 237 tests and typecheck pass. The release workflow repeats unit/lint, phone regressions, production signing, ZIP checks and native/model parity checks before creating draft artifacts.

Before public publication verify the downloaded APK, manifest hash/size/version/ABI, production certificate and compatibility bridge. Verify the app's actual `UPDATE_MANIFEST_URL`, which targets `update-mobile-arm64.json`; the legacy manifest intentionally retains the universal compatibility bridge.

The phone's existing signer is the debug certificate (`519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`). Preserve its data. A locally re-signed comparison copy can validate the published minified payload only after non-META-INF entry equality is checked. That is separate from production-signer installation/upgrade proof. Exact public artifact evidence is recorded after publication.
