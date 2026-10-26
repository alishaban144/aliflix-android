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
| [Lumaform Orb source](https://github.com/abektes/lumaform-orb/blob/main/packages/orb/src/engines/regard-engine.js) and [live Regard reference](https://orb.lumaform.xyz/?engine=regard) | Adapt its authored minimum-jerk attention, retained critically damped body-follow dynamics into native spatial motion. One face survives voice/results/chat layout changes; include the full MIT notice. Rive and Motion references were also inspected; paid source was not used. |
| [Netflix natural-language search](https://help.netflix.com/en/node/349963919363172), [Plex Universal Watchlist](https://support.plex.tv/articles/universal-watchlist/) and [Trakt product update](https://roadmap.trakt.tv/changelog/rocket-trakt-product-update-october-2026) | Mood search, aggregated lists and progress calendars already exist. The useful Aliflix work must connect actual local progress, verified catalogue constraints, durable execution and adaptive replanning; ordinary search/control is not presented as a research breakthrough. |

The [full release changelist](mobile-182-changelog.md) describes concrete workflows, implementation changes and bounds. Research informed sustained-goal architecture; it does not establish parity with general-purpose agents or prove that another LLM cannot reason about the same problem.

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

### Earlier candidate evidence (before the user's silence report)

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

### Reassessment after the Pixel testing-build report

The user reported Speaking animation and text/cards while audio remained silent until “Hi?”. That report supersedes any inference that earlier typed-input success fully resolved their case. The original intermittent acoustic occurrence was not reproduced on demand. Additional fixes address observed routing races and output stalls, and additional tests enter the production audio/VAD path.

- Routing now explicitly selects the speaker when a communication-mode transition temporarily reports no device; external communication routes remain respected. Hardware playback-head progress has bounded retry/reconnect recovery, including streams whose generation has already completed. Volume buttons control the voice output stream without changing volume automatically.
- Owned speech PCM enters the real production input/DSP/VAD/endpoint path, without typed input or forced turn boundaries. Actual microphone capture resumes during output. The second three-request physical run rendered 240,961, 265,681 and 114,241 samples on the built-in speaker without a second utterance; routes and hardware heads were recorded. First-audio measurements of 11.239/10.327/9.370 seconds include speaking the input fixture. This is controlled PCM injection, not a human-in-room acoustic test.
- Manual review rejected a shallow Beehive response despite that run's audio pass. The test now requires settled completion, no pending tool answer, concrete visual observations and a developed same-topic continuation. Its final result is recorded separately; an audio pass alone is not a discussion-quality pass.
- Soft leading negation was lost in the first spoken run. The new protocol's 500 ms prefix and one-second endpoint retained “Avoid the obvious classics” in the second run. Legacy endpoints remain unchanged.
- Physical orb regression passed with exactly one face: measured widths 839 px in voice, 376 px with results and 111 px in chat. A phone screen recording captures continuous travel; static screenshots are not a fluidity benchmark. Reduced motion, mid-flight retargeting and bounded frame-hitch behavior also have pure-engine checks.
- A real Scavengers Reign task used catalogue dependency output, released episode inventory, 75-minute daily limits and five-minute breaks. Updating completed progress reduced remaining episodes from ten to eight, increased revision 1 to 2, retained catalogue work and produced a changed-output signal. All calendar entries stayed future/current and within budget. Guest renewal retained ownership, another guest was isolated, and explicit Refresh reran work. Test fixtures were cleaned up.
- A daily identity-renewal defect would have hidden persistent work. Stable private installation credentials and canonical authenticated account ownership now survive token/session renewal. No additional AI endpoint or payload was introduced.
- Final source test counts, deployment, phone results and signed/public artifacts are added to the release evidence after their gates complete. The earlier GitHub draft run was cancelled before tagging or publishing; only the reassessed source is eligible for release.


### Assistant-stream audio reassessment

The user reported the issue again before publication. Publication was held while the replacement output path was checked. Preserved Pixel system diagnostics showed phone-call output at 1/15 (minimum), media at 7/25 and assistant at 10/15, with hardware frames advancing. This is evidence of a wrong output domain, not proof that every reported intermittent occurrence has the same cause.

[Android AudioAttributes](https://developer.android.com/reference/android/media/AudioAttributes) defines USAGE_ASSISTANT for spoken responses to user queries and USAGE_VOICE_COMMUNICATION for telephony/VoIP. The replacement uses assistant speech attributes consistently for output, focus and volume controls, and avoids call mode for ordinary speaker/headphone playback. Explicit SCO/LE communication headsets retain their route. System volume is never raised automatically.

The physical acoustic probe uses a two-second owned 997 Hz signal through the production player, with an unprocessed microphone and input effects disabled only for that debug test. Raw microphone audio is neither saved nor transmitted. Detected spectral magnitude was 0.01192465 against baseline 0.00001096 (over 1,000 times baseline), output usage 16, actual speaker route 2, output volume 10, mode NORMAL. This proves actual speaker sound for that controlled path, beyond an advancing playback counter. Cold-call speech, echo and interruption validation is a separate gate recorded below.

The developed-answer regression passed before this output replacement: a substantive Beehive answer discussed palette, lighting, composition and isolation; its “And?” continuation discussed camera movement and spatial scale. This establishes the sampled discussion behavior, not an acoustic claim for that previous call-stream build.


Orb attention update: large movements now use deterministic conversational destinations, not random positional wandering. While speaking, the most recently named visible film selects the corresponding side; thinking shrinks and lifts the face out of reading space; listening recenters toward the viewer. Screen mode, keyboard and panels still provide measured safe destinations. Regard's minimum-jerk attention and retained spring dynamics connect these targets continuously. Pointer pursuit and rendered-syllable response provide restrained detail without arbitrary routes.


### Final source qualification

- Android full gate passed in 7m09: **763 unit tests, zero failures/errors, three skips**, mobile lint, mobile/test assembly and TV compatibility assembly. Source remained frozen during this gate. A subsequent test-only vocabulary correction recognizes valid continuation observations about interiors, contrast and close-ups; the actual answer had been on topic. Its test APK was rebuilt successfully (47 seconds).
- Worker final gate: **270 tests passed**, typecheck and dry-run passed. Final deployment `8f909792-18eb-41f6-a482-d61d433ff4b3`. An additional date regression ensures “more recent” cannot broaden an already newer minimum-year restriction.
- The corrected input endpoint retains a natural 923 ms pause in the owned speech fixture. Final cold calls retained “Avoid the obvious classics” and all science-fiction constraints; actual assistant-stream output began without another utterance. The expanded final regression run records its automated result separately.
- Assistant-stream developed-conversation journey passed: substantive 3-Iron answer, on-topic “And?”, exact Primer/Vast of Night runtime decision, zero self-interruptions during tested speaker/microphone exposure, and genuine near-end interruption through the production duplex gate. The louder output path required capping the speech-floor test and retaining 1.2 seconds of pre-roll so a human need not overpower the assistant.
- Contextual orb phone test passed (14.538 seconds), exactly one bounded face across eight states. First-title and second-title centers moved from x=296 to x=783; Thinking moved up and shrank from 341 to 261 px; Listening returned to center; chat docked to 111 px. The actual 1080x2400 recording contains 1,261 variable-rate frames over 17.915 seconds. This is visual evidence, not a controlled frame-time benchmark.
- The final physical regression run passed all **20 tests** in 184.563 seconds: native UI/player/wire/short-output cases, the durable task and corrected cold-call discussion/continuation assertions. Signed release artifacts and the public canonical updater are independently verified after GitHub finishes; they are not inferred from debug-device results.


A further physical acoustic check passed at the phone's lower selected assistant volume 2/15 (3.724 seconds), without changing that volume: known-speaker magnitude 0.00093548 against baseline 0.00007157, roughly 13 times baseline. Output remained USAGE_ASSISTANT, actual speaker route 2 and MODE_NORMAL. The phone was left at Home with the microphone stopped and existing data intact.
