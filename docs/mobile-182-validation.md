# Mobile 3.1.182: persistent work, voice and discovery

Scope: Android mobile, version code 272. No TV source or version changes. Existing Gemini Live, Tavily and TMDB paths are retained. The user explicitly declined a separate Gemini verification call; none was added. Personal-match explanations and descriptive interface banners are excluded.

## Research and engineering decisions

| Primary source | Applied decision |
| --- | --- |
| [OpenAI Dots](https://openai.com/index/introducing-dots/) and [Meta Muse](https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/) | Sustained work needs durable goals, explicit memory, background execution and user steering. Aliflix implements these within its own catalogue/library/player boundary. These references inform the architecture; they are not claims of product equivalence. |
| [Meta agent safety architecture](https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse) | Separate retrieved data from instructions; bind mutations to the current request; fence late results after cancellation/revision. |
| [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) | Streaming playback normally waits for the platform start threshold. Lower it for short replies and drive output/drain independently of blocking microphone capture. API 29/30 use bounded tail padding. |
| [Gemini Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities) and [tools](https://ai.google.dev/gemini-api/docs/live-api/tools) | Distinguish generation completion from audible drain. Async final tool results use WHEN_IDLE. Correlate far-end output with captured audio before classifying genuine near-end interruption. |
| [Media3 controller](https://developer.android.com/media/media3/session/control-playback) | Read actual player state on its thread. Bind deferred resume to the same media identity and acknowledge before returning to playback. |
| [Cloudflare Durable Objects alarms](https://developers.cloudflare.com/durable-objects/api/alarms/) | Persist each completed dependency stage, run one stage per alarm, recover leases, and reject stale revisions. No background microphone is involved. |
| [TMDB reference](https://developer.themoviedb.org/reference/intro/getting-started) | Resolve canonical identities, actual runtime/release dates, creators, collection membership and released episodes. Missing metadata never becomes an invented runtime. |
| [Material progress](https://m3.material.io/components/progress-indicators/guidelines) and [requested loader](https://uiverse.io/mobinkakei/proud-ladybug-46) | One task-owned progress area, centered and large; pagination uses a smaller tail indicator. Native path/dot adaptation, with the original MIT notice included in mobile assets. |

## Substantial task outcomes

These are compositions of a durable dependency engine, not independent generic chat buttons. Voice creates and steers tasks; the Tasks sheet exposes persisted outputs. Native library/player actions remain separately scoped.

| Outcome | Actual execution and bounds |
| --- | --- |
| Research expeditions | Public evidence research feeds verified catalogue discovery and a saved task output. Sources remain accessible. |
| Clue investigations | A user's film clues can drive a research/discovery graph; uncertain or missing matches are not treated as verified identities. No separate verifier/model was added. |
| Exact double bills | Canonical runtimes, exact film count and inter-film breaks are packed into a total budget; infeasible requests become needs_input. |
| Multi-evening festivals | Dated programmes allocate films without repetition and enforce each evening's runtime. At most 14 nights per plan; longer spans are rejected. |
| Episode catch-up calendars | Real released, unwatched episodes retain order across dated daily budgets. Missing durations, incomplete episode pages or insufficient time prevent a complete-plan claim. |
| Group common ground | Independent discovery branches intersect actual catalogue identities; an empty intersection requires revised constraints. |
| Franchise traversal | Read actual collection membership, chronological identities and explicit truncation/next offset. No inferred sequel list. |
| Filmmaker traversal | Use a verified creator person ID, read director/creator credits and paginate chronological results. |
| Living collections | Explicit recurrence appends new verified membership without reshuffling previously published members. Bounded to 72 titles. |
| Exact release watches | Explicit recurring catalogue checks with onlyReleased admit titles only when actual release dates arrive. This is catalogue release, not a streaming-provider availability claim. |
| News/research watches | Explicit recurring public research tracks changed evidence; unchanged results do not repeatedly notify. Minimum recurrence is six hours. |
| Persistent preference memory | Only explicitly requested preferences are stored; exact-key forget and owner isolation are implemented. No inferred personal profile is written. |
| Revision-aware replanning | Preserve unaffected completed stages, invalidate changed dependencies and reject old expectedRevision values. Late work cannot overwrite a newer goal. |
| Reversible batch library work | Preflight all canonical identities before mutation, apply idempotently, roll back failures and guard whole-batch undo against intervening edits. |
| Background continuation and delivery | Persisted backend work survives closing Talk. A network job refreshes results and notifies meaningful changes; Android controls its timing. Tasks can be paused, resumed and cancelled. |
| Player-aware conversation | Player > More > Talk carries exact media/episode/position context, pauses playback and defers same-media resume until spoken output drains. |

Task limits: 16 active tasks per owner, ten dependency stages, bounded catalogue pages, explicit recurrence only. Research remains subject to existing provider capacity and shared quotas. Results cannot guarantee that an upstream provider will play or download a title. No external-app action agent was introduced.

## Voice and discovery fixes

- Independent AudioTrack output pump fixes micro-replies waiting for another microphone read. Near-end neural VAD plus bounded far-end correlation suppress echo while preserving sustained human interruption and pre-roll.
- Substantive film discussion preserves elliptical follow-ups. A discovered intent bug incorrectly interpreted excluded alternatives ("Do not choose Brazil or Office Space") as a closed A/B decision; exclusions no longer trigger repeated discovery corrections.
- For you receives the actual enriched MobileTasteModel/TastePicks ranking from favourites/history; the unrelated hourly/daily pool is removed. Search, results and error states do not redisplay For you.
- Published research cards are append-only on both server and client. Signed, owner-bound continuation pages exclude prior identities, retry immutably, expire after 30 minutes and never silently replace visible results.
- Recency/year/runtime refinements are enforced against catalogue facts. Curator input retains the original premise and exclusions. Search-page mentions cannot bypass curation during an AI outage; fewer verified matches are preferred to unrelated fallback titles.
- One centered native loader replaces simultaneous body/composer progress. Near-bottom pagination and the More action use the real continuation path.

## Validation record

Evidence is maintained in the local .validation directory; no authentication tokens are included in receipts. Final source, physical/live results and public-artifact verification are recorded below as each gate finishes.

### Completed before final release build

- Android broad gate: 750 unit tests, zero failures/errors, three skips; mobile lint, mobile/test APK builds and TV compatibility build passed. Later final-source gate recorded separately below.
- Worker: 259 tests, typecheck and deployment dry run passed before final additional date-span tests.
- Physical Pixel 7a: 80 ms PCM rendered through AudioTrack and drained with capture stopped, without a wake-up utterance.
- Physical Pixel: ten Ask UI/native Media3 tests passed, including one loader and no For you during searches/errors.
- Physical live film conversation: developed answer, contextual "And?", actual speaker/microphone echo exposure and genuine near-end interruption passed. The genuine near-end test injects an owned PCM fixture through the production duplex gate; it is not a human speaking in the room.
- Live backend: stable stream prefix, nonoverlapping continuation, immutable cursor retry, recent refinement and impossible-to-feasible exact-runtime replanning passed. Quality review rejected an earlier uncurated fallback and caused its removal. Transport success alone is not a quality assertion.

### Release evidence

- Final Android broad gate passed in 8m01: 751 tests, zero failures/errors, three skips; lint, mobile/test assembly and TV compatibility assembly passed. A subsequent bridge-specific change passed focused intent/bridge tests and a fresh mobile/test build (48s).
- Final Worker gate: 261 tests passed, typecheck passed and dry-run passed. Deployment version c1c73149-a31b-4554-8917-2b0b6ae6791e.
- Physical two-turn recommendations passed after intent/bridge corrections: exactly two institutional comedies, then two distinct post-2018 science-fiction films. Audible output arrived after 4.304s and 1.297s without another utterance. Final speech/card identities agreed.
- Physical developed conversation, elliptical follow-up and exact 90-minute comparison passed. Speaker output caused zero local/remote interruptions in the tested turns; an owned near-end PCM fixture through the production gate caused one genuine interruption.
- Physical Live-created durable programme passed: canonical Primer and The Vast of Night plus a five-minute break totaled 173 minutes. Its result was read with Talk suspended. Input was injected text through the normal Live session/tool route; this is not a claim of a human-spoken microphone test.
- Earlier failing live task tests exposed ignored direct-title inputs and an inappropriate six-film default. Both executor defects were repaired, regression-tested and repeated successfully on the phone. An earlier cancelled test fixture was also excluded from subsequent fixture selection.
- Live publication readiness passed: 24 movie and 24 series entries, atomic valid publication. This compatibility gate is separate from the personalized mobile For you ranking.
- Signed release build retains all unit/lint/signing/native-parity gates. Emulator execution is explicitly skipped because physical-device validation was used. Public artifact hashes, sizes, signer, canonical updater and release status are independently checked after the draft build; the resulting verification record is attached to the release.
- Phone tests use a debug-signed installation. The public APK must retain the production signer. A same-signer installation preserves phone data; a production-upgrade claim requires matching installed and incoming certificates.

Coverage boundaries: the live/device checks exercise representative composed goals and voice cases, not every possible graph or acoustic environment. Full player-to-Talk navigation, every recurrence interval and every supported provider were not all exercised on hardware. Backend graph/runtime/revision/cancellation/owner-isolation tests cover additional failure paths. Provider availability and model interpretation remain external limits; the implementation reports unknown/infeasible results rather than inventing success.
