# Aliflix mobile 3.1.176 validation

**Historical candidate report.** Later repairs and final validation are recorded in [mobile-176-recovery.md](mobile-176-recovery.md). The failures and unreleased status below describe the earlier candidate.

## Scope and baseline

Inspected the exact v3.1.174 public APK (code 264, 26,238,353 bytes) and source `6306c33e4ff413f0b7597ea425b725b2f0fe2031`, together with the deployed Worker. Candidate: mobile code 266/version 3.1.176; universal migration bridge code 265/version 3.1.175. TV stays at code 159/version 3.1.69. No TV source, build or release task is included.

## Implemented

- One native Ask composer with Movies/Series, up to four Similar poster anchors, contextual Filters and Surprise Me. Series has inline All/Running/Ended selection, enforced as a server constraint. White fine-edged actions, larger centered Discover labels, a minimal companion icon, and a larger update dot to the right of the My Space gear.
- Shared authenticated GPT-OSS 120B planning/candidates, Tavily research, exact TMDB identity/hard-filter checks, independently licensed narrative evidence, and Clef-flash relevance classification. Favorites, saved intent, playback and explicit rejection remain distinct. AI probabilities are never displayed as verified match percentages.
- Separate SQLite AskResearch Durable Object: bounded persistent caches, coalescing, owner-fenced cancellation, strict search/inference budgets, and atomic movie/series editorial publication. Scheduled refresh: `17 5 * * *` UTC. Last-good collections survive failure. Empty bootstrap retries have a five-minute backoff; populated collections use the longer backoff.
- Talk remains exclusively Gemini 3.8 Live, with Aoede by default and explicit Aoede/Kore/Puck/Charon selection. Same-model reconnects preserve the selected voice. General web research and recommendations are real asynchronous authenticated tools. No permanent API key enters Android.
- Native communication audio attributes, AEC/noise suppression, bounded priming, nonblocking writes, notification ducking and immediate interruption flushing. A consumed token without a usable resume handle is renewed inside the same authenticated Worker conversation, retaining voice, language, spoiler grants and search budgets. At most six renewals are allowed within the original 20-minute expiry; renewal cannot extend it. Native generationComplete is recognized before turnComplete to avoid unnecessary continuation prompts.
- Rounded fine-dot semantic topics; continuous independently rotating feathered light fields with a 45 ms attack/270 ms release energy envelope; restrained returning glances and pause-sensitive blinks. Existing particles, reduced-motion support and native surfaces remain.
- Language changes require an actual explicit user instruction, rather than trusting the model's explicitRequest flag. Foreign film titles and references to German cinema cannot unlock English.
- Exact episode facts use deterministic title variants, Wikidata identity/premiere dates, revision-attributed Wikipedia episode rows and TVMaze fallback. Only the requested row enters model context. Public licensed facts are cached separately from private conversations, keyed by canonical identity and episode. Short fallback entries expire sooner than verified detailed evidence.

## Passed checks

- Mobile JVM: 677 tests across 119 suites, zero failures.
- Mobile unit/lint/debug APK/instrumentation assembly passed together in 3 min 23 s (`release-candidate-gates.log`). Generation-completion changes passed unit/assembly in 1 min 4 s and lint in 2 min 15 s. Later bounded renewal changes passed unit/assembly in 1 min 15 s; final renewal lint is recorded below when complete.
- Worker typecheck and 195 tests across 23 files passed. GitHub backend-only workflows repeat these checks before deployment.
- Pixel 7a: inline status serialization, shared composer/anchors/filters and filtered Surprise Me passed in 4.252 s. Latest complete UI check includes the quota state and explicit voice picker; final receipt is recorded below.
- Real earlier four-turn Talk/exit test passed in 46.532 s, returning to the existing MainActivity. Closing the experience no longer shuts down OkHttp's executor under active callbacks.
- Pixel render fixture passed in 14.751 s. The fixture exercised Latin and Arabic; the captured Latin frames were reviewed. Arabic screenshot review is recorded separately if completed. 1,046 frames, 13 modern frame-deadline misses (1.24%), legacy classifier 1,014/1,046 (96.94%), six missed vsyncs; frame p50/p90/p95/p99: 19/20/24/29 ms; GPU: 6/6/6/7 ms. Debug-device evidence, not a universal 60 fps claim.
- All six ARM64 native/model entries in the latest debug candidate match the original v3.1.164 release byte-for-byte. Production APK parity remains a release gate.

## Real research measurements

The query “Science fiction films about memory, identity and unreliable reality” returned **22 distinct, TMDB-verified Science Fiction films** with independent narrative evidence and Clef acceptance in **33.143 s**. The pipeline evaluated 105 hypotheses and 41 evidence-backed candidates, using three GPT calls, five Clef calls, and one new basic Tavily credit with reusable prior findings. The identical cached request returned all 22 in **36 ms**, with zero new model calls or Tavily credits.

Provider-metered GPT usage: 292.858 neurons; token-derived total estimate including Clef: 370.858. Clef omitted neuron metering, so the full provider neuron total was reported as unavailable rather than invented. These timings reuse some research/evidence cache; they are not a fully cold infrastructure benchmark. Cold research is materially slower than cached delivery.

Actual Talk dispatch also returned 20 independently checked recommendations and retrieved seven or more web sources in physical-device sessions. A separate intermediate daily movie query produced 23 verified recent titles in 26.124 s, one Tavily credit and an estimated 213.790 neurons. The movie/series pair has **not** passed atomic publication.

The inspected older v3 response returned 24 but contained an incorrect The One I Love identity/year and non-Sci-Fi titles. Its matched-condition cold latency and resource usage were not captured, so no baseline speedup is claimed. A later intermediate 21-result candidate still included Memento despite a Science Fiction request; strict genre enforcement now excludes it. Narrow requests may return fewer genuine results rather than padding.

## Failures found and corrected

Early batched Wikipedia extracts omitted titles, full Wikidata claims were oversized, and candidate replenishment repeated previous identities. Extract/revision batching, property-only identity joins and explicit exclusions corrected those failures. Large repeated Clef question payloads timed out; smaller bounded batches retain independent classification with less repeated context. One generated year-zero identity discarded a complete editorial batch; individual invalid identities are now rejected without losing valid candidates.

TV candidate lookup fetched full aggregate casts, reviews and adjacent catalogues, causing seven-second identity timeouts. Research now fetches only structured details and external IDs; full app detail-page fetching is unchanged. Series generation explicitly uses the premiere year, not a later season's release year. The final current-series count after this fix is unverified because the provider allocation became unavailable.

Real Dark S2E1 replies included later-episode details. TVMaze's short summary also uses 2052, whereas the independently verified episode plot uses 2053. Detailed Wikipedia evidence takes precedence. Broad search could miss a short title when no year was supplied; deterministic title variants and canonical episode keys address that path. Earlier weak per-user caches were invalidated. This reduces concrete grounding failures; it does not establish zero hallucinations.

A model language-control call switched English to German without an instruction; local transcript validation now prevents that authorization. Forced reconnect tests exposed consumed-token recovery. A completed generation could also trigger an unnecessary stalled-answer continuation; native generation completion is now handled explicitly. Final continuous voice validation is tracked separately below, rather than claiming the earlier failed attempts passed.

## Publication blocker and configuration

The deployed provider returned Cloudflare error **4006**, explicitly reporting the daily free allocation of 10,000 neurons exhausted. Official GraphQL at the final audit showed 2,764.667 GPT neurons plus 392.514 Clef neurons for the queried UTC day (3,157.181 total); that analytics snapshot does not reconcile with the enforced allocation. The provider rejection is authoritative. No quota bypass, paid plan or model substitution was enabled.

The Worker now returns AI_QUOTA/HTTP 429 and stops queueing additional inference for that UTC day after this error. Cached verified results and ordinary Gemini conversation remain separate. Both daily collections are currently unpublished/empty. The new read-only GitHub publication gate correctly fails until each has at least 20 verified, distinct, evidence-backed identities under one atomic publication timestamp.

Existing Cloudflare bindings, secrets and deployment infrastructure are reused; no new secret is required. The external prerequisite is restoration of the provider's free allocation, followed by successful real daily refresh. Spoken episode accuracy and the remaining acceptance/publication gates must also pass before release. Cloudflare documents a daily 00:00 UTC reset, but the observed analytics/allocation mismatch prevents promising a specific recovery time.

## Evidence boundaries

The Pixel uses the existing local test signer, checked before data-preserving installation. No uninstall, app-data clearing or secure lock action was performed. Live tests use actual Gemini streaming, native AudioTrack and an owned PCM interruption fixture; they do not prove real-room echo cancellation or human-rated fluency. Final isolated sessions disable loading/writing saved Talk transcripts in memory only; Favorites, My List and playback context remain real and saved history is preserved. No claim of flawless future networks, human-equivalent gaze or zero hallucinations is made.

Public artifact ZIP/SHA-256/signer/updater/native-parity verification, final APK size and a production-payload Pixel mirror are pending because publication is blocked. The exact ABI-only size difference must be measured on the signed main APK versus its signed universal bridge when they exist.

## Latest exact-source verification

The deployed no-year request for Dark S2E1 returned its detailed revision-attributed Wikipedia row in 604 ms. Deterministic title variants avoid broad-search ranking misses, while canonical IMDb/page identity keys share the same facts across title/year spelling variants. Generic concepts and ambiguous TV-name matches are excluded. This is a cached-network measurement, not a fully cold-source guarantee.

The earlier strict voice fixture demanded Danny or Wendy in a three-sentence ending answer. Gemini instead accurately explained Jack in the 1921 photograph and its ambiguity. The semantic assertion now accepts supported final events or the photograph while still rejecting empty/refusal/meta-continuation responses. Actual transcript review remains separate from these automated checks.

## Final continuity hardening

A fresh Google connection can issue a new resume handle before Android processes setupComplete. Testing exposed a race where that new handle incorrectly suppressed dialogue replay. Replay now depends on whether a handle was sent on the connection, rather than the mutable latest handle. Explicit user language instructions are recognized locally from the actual transcript, so returning to English does not depend on an optional model control call. Topic identities are retained safely across callback threads. These changes are unit/assembly checked; final physical acceptance is recorded below.

The bounded recommendation taste snapshot now reserves space for Favorites, saved interest, explicit rejection and playback separately. A long rejection history previously displaced every Favorite from the first eight metadata lookups. Favorites now lead, while a rejected duplicate cannot also appear as a positive preference. The final personalized live run remains pending provider allocation recovery.

## Final Pixel and lint receipts

The final real voice protocol scenario passed in **173.413 seconds**: ten continuous Aoede turns; real web research; explicit English/German/Arabic changes; exact Dark S2E1 evidence with the correct 2053 setting; Shining context retained after forced reconnect; native PCM barge-in; explicit Kore selection, real output and same-voice reconnection; return to MainActivity. There were **zero automatic stall recoveries** in that scenario. The final run deliberately omitted a new recommendation inference because of the confirmed Cloudflare allocation block; earlier actual Talk dispatch returned 20 results, and the separate shared-pipeline query returned 22.

First rendered audio uses AudioTrack playback-head advancement, not merely arrival of network bytes. For the first nine final turns: [1599, 5819, 909, 691, 2558, 646, 1737, 2691, 690] ms; median 1599 ms; range 646–5819 ms. Completion includes audible answer duration and is not the same as response-start latency. The tenth turn includes an interruption and is not directly comparable to ordinary first-audio timing. Raw per-track underrun counters reset on reconnection and must not be summed across replaced tracks.

The owned PCM interruption successfully cancelled output and invoked the app-context tool, but the response requested clarification. This fixture does not establish that every complex spoken app question is understood, nor does it prove real-room echo cancellation or human-level fluency. Those limits remain explicit. No repeat-clarification-free guarantee is claimed.

The final mobile unit/assembly gate passed in 36 seconds; final mobile lint passed in **2 min 49 s**. Worker typecheck and **195 tests across 23 files** passed, including successful atomic editorial publication in fixtures, last-good retention, owner/session security, cancellation, quota exhaustion, canonical episode identity and bounded renewal. Fixture publication is distinct from the live daily publication, which is still blocked.

## Manual factual review after protocol acceptance

Manual review of the ten-turn receipt found an unsupported Claudia meeting in 1987 in the Dark S2E1 answer. That detail is outside the retrieved episode row. The protocol PASS therefore does not constitute a pass for all spoken plot claims. Cached episode responses previously omitted the fresh response's boundary metadata. Both paths now return identical strict episode grounding instructions, including the distinction between a person being mentioned as an object's owner and that person performing an action. A separate targeted physical regression checks the observed future-timeline leakage; its final result is recorded below.


## Targeted episode acceptance

The dedicated Pixel Dark S2E1 regression failed after cached grounding metadata was repaired: Gemini introduced 1954/1987 events outside the exact retrieved source. Immediate INTERRUPT scheduling alone also failed and said 2052 despite the tool returning 2053. These are actual spoken factual failures, separate from the ten-turn protocol pass. The failing tests were retained rather than relaxed.

The next change adds a generic episodeScope with the exact requested title/season/episode and years derived only from that episode's text, not publication dates or adjacent episodes. The tool description and session instructions explicitly require waiting for evidence and paraphrasing it. Retrieval remains NON_BLOCKING, with immediate result application. No alternate model, voice engine or paid quota was enabled. Source-anchor Worker typecheck and all 195 tests passed; backend-only deployment [39188472393](https://github.com/alishaban144/aliflix-android/actions/runs/39188472393) succeeded. Final physical results follow below.


The final source-anchor Pixel run failed in **27.439 seconds**: its spoken answer introduced unsupported 2018/2019 dates and an older-Claudia encounter. The detailed source was applied **834 ms before first rendered audio**, so source delivery latency does not explain this failure. This remains an unresolved model-grounding acceptance failure; stronger instructions and structured anchors did not establish reliable spoken episode accuracy. The Android client continues using the requested single Gemini model and selected voice.

Final mobile unit/debug/instrumentation assembly and lint passed in **3 min 26 s**, with 677 JVM tests unchanged. A further synthetic-energy render fixture passed in 14.737 s. A captured Arabic fixture frame was landscape in MainActivity and is not accepted as portrait Talk layout proof; production TalkVoiceActivity is portrait. Previously reviewed Latin portrait frames and the separate UI/voice-picker receipts remain valid for their tested scope.

The read-only publication gate was rerun after deployment: movie=0 and TV=0, publishedAt=0. Latest public GitHub release was independently rechecked as [v3.1.174](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.174), published 2026-10-18T15:08:34Z. Backend: https://aliflix-recommendations.equable-equipment.workers.dev. Candidate source is on codex/ask-unified-v3.1.176. No v3.1.176 APK was published. No TV build or release was run.
