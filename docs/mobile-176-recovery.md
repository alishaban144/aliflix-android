# Mobile 3.1.176 recovery validation

Candidate publication is pending final live acceptance. Public baseline: v3.1.174 (6306c33e4ff413f0b7597ea425b725b2f0fe2031). TV is unchanged and no TV task is run.

## Playback repairs

VOE's current canonical page delegates Interstellar's same file to derricknationtop.com, then a tokenized /access document. The old fixed redirect list rejected this valid handoff. The new adapter accepts only a canonical VOE declaration preserving the exact opaque file path and audio query, with HTTPS/public-host checks. Subsequent redirects stay on the delegated origin. Cookies remain origin-scoped; no browser bridge is granted to arbitrary hosts. The complete HLS master and original audio/subtitle metadata are retained.

Filmo attempts now have an 18-second overall budget and eight seconds per mirror, followed by automatic source recovery. The adapter checks the actual document language after cookie expiry instead of trusting a process-lifetime English flag. Cached native routes must match the configured source, including its origin.

Audio confirmation previously compared the entire declared and decoded Format fingerprints. Decoder-enriched IDs/codec/channel fields caused a valid change to roll back. Confirmation now checks compatible known decoder attributes and soundtrack identity while still requiring the selected override and decoded evidence.

Actual Pixel evidence: Interstellar cold startup 6,984 ms; later playback 4,329 ms with English and German tracks and successful German selection. The Shining 7,695 ms; Green Book 7,400 ms; Frozen 7,420 ms; Serienstream Dark 4,244 ms. All had decoded video and loaded captions. Filmo's actual Shining page lists German only; its English alternative loaded through CineJoy. A real DNS-outage injection for Filmo recovered through CineJoy with decoded Matrix video in 7,565 ms, without a server tap. The final bidirectional Interstellar regression passed: 4,192 ms startup, decoded video/captions, English → German → English confirmation (5.383 s test).

## Talk repair

The previous client put scheduling inside the model-visible tool output. Google's authoritative [FunctionResponse protobuf](https://github.com/googleapis/googleapis/blob/master/google/ai/generativelanguage/v1beta/content.proto) defines it on the protocol envelope. One encoder now sends real SILENT local controls and immediate asynchronous episode evidence. Gemini 3.8 Live and the explicitly selected voice remain unchanged.

The previously failing Dark S2E1 timeline regression passed on the Pixel in 36.419 s after this wire correction. A stricter run passed in 31.848 s after name preservation: its German answer describes the sourced 1921 tunnel, 2020 investigation and 2053 black substance, without the later Claudia meeting or invented dates. Exact Arabic/Persian-digit and Spanish episode boundaries are parsed; a newly requested earlier episode overrides older spoiler grants before any future-plot cache can be read. This is concrete regression evidence, not a zero-hallucination guarantee.

The full physical sequence passed in 210.793 s: ten continuous turns, actual Tavily execution, 24 verified recommendations, English/German/Arabic responses, exact Dark S2E1 evidence, Shining ending/final-photo follow-up, retained film context after forced reconnection, owned PCM speech interruption, explicit Kore selection, same-voice reconnect and exit to MainActivity. Questions are injected as text; English interruption uses owned speech PCM. This does not certify multilingual human ASR or acoustic echo cancellation in every room.

First audio per turn (ms): 1836, 1887, 42904, 669, 1740, 671, 2574, 1852, 1365, 1368. The uncached research turn needed one bounded recovery; it remains materially slower than ordinary conversation. Native underrun counters include end-of-reply drain and reset across reconnects, so raw deltas are not an independently verified audible-gap metric.

Real Gemini audio interruption no longer cancels arbitrary pending tools: explicit toolCallCancellation IDs control that lifecycle. Empty generation-complete events cannot clear the unanswered-turn watchdog. A control-only turn resumes once, and a silent lookup response requests immediate output. Bounded user-owned dialogue is supplied again after reconnection, without changing engine or voice.

Portrait Latin/Arabic orb/halftone rendering passed on Pixel; reviewed actual portrait frame. The sampled synthetic-energy fixture rendered 1019 frames with 48 deadline misses (4.71%), median 19 ms, p95 32 ms. This is a debug rendering sample, not a universal 60 fps claim.

## Ask availability repair

The live account's exact GPT-OSS model returned HTTP 200 on a tiny health request while the app still reported AI_QUOTA. The account was independently confirmed to own equable-equipment.workers.dev. The persistent local availability flag had no recovery path before midnight. A bounded same-model health probe can now reopen a stale circuit, at most once per hour, using a metered reservation. It removes only the availability flag and never resets real daily/monthly/neuron counters. Blocked requests no longer consume unattempted inference-call reservations.

Empty first installations schedule a bounded Durable Object bootstrap; read requests remain fast. Both movie and series collections publish atomically only after at least 20 verified entries each. Real failures retain last-good data. Scheduled refresh is 00:07 UTC, with bounded quota recovery. Approved result caches last 24 hours. Android polls a warming bootstrap for a bounded period, retains valid atomic cache data and handles quota timing without a retry-only loop. The research pipeline keeps its required models; the independent catalogue availability path described below is explicitly separate from query matching.

The editorial model was assigning later-season years to older shows and inventing sequel identities. Scheduled research now adds a bounded, date-verified TMDB identity pool with actual premiere dates and IDs. TMDB editorial prose is stripped; Tavily/licensed evidence, hard filters and independent Clef evaluation remain mandatory. No fabricated or unevaluated entries bypass publication. A regression covers wrong premiere years, unknown dates, duplicates and excluded prose.

## Verification status

Final mobile unit/assembly/lint passed: 688 cases, zero failures/errors, three skipped (685 executed), 123 suites. Worker typecheck and 214 tests passed, plus six publication-gate tests. Evidence is recorded in .validation/v176b. Live editorial bootstrap and public production APK/signature/updater/ABI/native parity and signed-payload Pixel checks remain publication gates. No permanent credentials, private transcripts or stream URLs are included in this report.

## Empty-page recovery (20 October)

Daily browsing now has independent catalogue availability. A bounded TMDB fetch verifies actual identities, release dates (excluding future releases), poster availability, votes and genres. Both catalogue collections publish atomically and retain last-good data. This path does not consume AI or Tavily quota, does not include TMDB editorial prose, and never fabricates an AI score or a match to an unanswered query. Publication checks validate canonical identity links and zero artificial scores for this provenance.

Mobile retains valid cache data and falls back to a 22,531-byte metadata-only snapshot of 24 real movies and 24 real series if the cache/network is unavailable, including a first offline launch. Catalogue suggestions remain visible beneath quota or no-result states. Malformed rows do not erase valid entries. Fresh server suggestions replace the fallback when available. No new bundled model or native binary was added.

Background editorial research now has separate bounded stage deadlines, and identity batches use bounded concurrency. The actual complete GPT-OSS/Tavily/TMDB/Clef refresh succeeded: **24 movies and 22 series**, published atomically in **61,796 ms**. Reads then took **88 ms / 61 ms**. The catalogue fallback was separately verified with 24/24, including no additional model calls. A failed AI refresh no longer determines whether the landing page has content.

Final mobile fallback tests and signed public-artifact checks are recorded below once completed.

## Interactive latency refinement

The 61,796 ms figure measures a background refresh of both daily collections, not a daily-read latency. A separate real fresh custom request still took 48,809 ms and returned 18 verified matches; another timed out after 50,372 ms. These failed the requested target and are retained as baseline evidence.

Public verified title identities and licensed narrative evidence now share a bounded one-day, 128-entry cache per media type. New requests recheck hard filters, exclusions and taste boundaries, perform GPT intent planning and Tavily research (or reuse valid cached findings), and independently rerank with Clef. Cached relevance scores are never treated as new assessments. Direct Wikimedia and independently identity/year-matched indexed Wikipedia/TVmaze evidence can be reused; arbitrary publisher text is not added to this licensed knowledge pool. Partially populated pools supplement smaller fresh candidate batches instead of being discarded.

The actual connected Pixel passed all four unified Ask UI cases in 6.298 s, including quota-error suggestions and visible browsing content during research. Actual Movies and Series landing pages both had populated poster carousels and were visually reviewed. Offline first-launch metadata, corrupt cache recovery, valid cache retention and malformed-row isolation are covered by mobile unit tests. No test pretends catalogue browsing suggestions answer an unfinished custom query.

## Final physical source and typography checks (20 October)

A final cold-resume pass exposed an additional validation deadlock: Interstellar at 69,053 ms had 2,947 ms buffered, six track groups and no player error, but remained BUFFERING until the provider deadline. The 2-second startup validation cap could stop loading before a usable sparse video keyframe. The validator now allows a bounded 12-second loading window while preserving the 250 ms normal ready threshold. Host discovery and actual Media3 validation also have separate deadlines within the existing total source budget. This changes startup buffering only, not model binaries or subtitle alignment algorithms.

The exact 69,053 ms regression then passed on Pixel: actual Filmo, 5,208 ms startup (first decoded frame 3,519 ms), English/German metadata, loaded captions, and English to German to English confirmation. Final actual Serienstream Dark startup was 2,777 ms; AniWorld Black Clover 2,497 ms, Japanese audio and all three real editions. A deliberately unreachable primary origin recovered automatically through CineJoy in 5,557 ms. The earlier failed runs are retained as failures. These are sampled live proofs, not a guarantee that third-party sites can never change or fail.

Only the Talk topic typeface was replaced: Fredoka Bold plus Baloo Bhaijaan 2 ExtraBold for Arabic, with original SIL Open Font Licenses. Font binaries are pinned to Google Fonts commit 47d54ca1ebb14fd864695f1d39dc734f39017c33. Particle timing, springs, transitions, dot sampling, layout and orb motion are unchanged. The actual Pixel Latin/Arabic render fixture passed in 15.019 s and both frames were reviewed. Unused Nunito assets were removed.

## Under-20-second acceptance status

The requested target applies to Describe, Similar and Surprise Me results, not the browsing carousel. The verified-evidence implementation produced a fresh Describe response with 21 evaluated results in 6,143 ms; its repeat took 183 ms. Native Surprise Me opened a title from the already fully researched pool within a 3,143 ms click-to-UI-dump upper bound, including automation overhead. Catalogue-only browsing data is explicitly ineligible for this researched Surprise shortcut.

Uncached Similar to Inception still took 28,818 ms and returned 12 independently accepted matches. A combined-planning experiment took 41,667 ms and returned 13; it was withdrawn, not shipped as a claimed improvement. The under-20-second target for all modes, and 20 genuine matches for this Similar case, are NOT passed. The bounded research ledger has one inference left today, insufficient for another complete live run; no quota reset, increase or model substitution was performed. The next full daily call budget starts 21 October at 00:01 UTC.

The user subsequently authorized a holistic implementation and publication with engineering judgment. The progressive delivery below addresses time spent waiting for a complete batch; it does not turn the earlier failed cold-latency measurements into passes. Public asset and updater verification is performed after publication.


## Progressive verified research delivery

Describe, Similar and uncached Surprise Me now use authenticated NDJSON snapshots from the same Durable Object and the same GPT-OSS/Tavily/TMDB/Clef protocol. Each independent Clef batch publishes accepted, source-backed, hard-filtered identities immediately. Later batches append without moving earlier posters. The client can open a title, refine or cancel while replenishment continues. Cached researched Surprise remains immediate; catalogue-only suggestions never count as researched matches.

The stream has bounded backpressure, versioned events, request identity validation, duplicate/out-of-order rejection and cancellation of the caller's waiter. Shared research survives cancellation by one caller while another still waits. A later failure retains actual evaluated matches for the same request instead of clearing them. Positive partial caches retain valid evidence for one day; no quota is reset or model substituted. Existing JSON clients remain supported. Mobile networking runs off the playback thread.

The new backend suite passes 220 tests, including actual byte-stream RPC transfer, verified partial publication, slow consumers, cancellation, quotas, retention and cache migration. Android adds seven parser regressions and an incremental-result interaction test. Their final execution and physical-device results are recorded in the publication evidence. Fresh first-batch timing is distinct from completion of all replenishment; a universal cold under-20-second result guarantee is not established by cached measurements or simulated delays.


The first real native-client pass exposed a cache fragmentation bug: Android sends legacy model aliases while the host benchmark omitted them. Research models are pinned server-side, so cache identity now omits these aliases while preserving every filter, anchor, query, refinement and taste entry. Legacy cache rows remain readable. The failed phone pass is retained; it is not counted as a successful end-to-end run.

The deployed stream independently returned 21 researched Describe matches in 792 ms and 13 Similar matches in 516 ms, with zero new GPT, Tavily or Clef calls. These are cached HTTPS timings, not fresh-model benchmarks. The mobile gate passes 695 unit cases with zero failures/errors and three skipped, plus lint and both debug APK assemblies. Five actual Pixel UI cases passed, including opening a verified poster while later batches are pending and appending without replacing earlier titles. The authenticated native-client check is repeated after deploying the cache identity repair.
