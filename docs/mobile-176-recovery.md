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

Empty first installations schedule a bounded Durable Object bootstrap; read requests remain fast. Both movie and series collections publish atomically only after at least 20 verified entries each. Real failures retain last-good data. Scheduled refresh is 00:07 UTC, with bounded quota recovery. Approved result caches last 24 hours. Android polls a warming bootstrap for a bounded period, retains valid atomic cache data and handles quota timing without a retry-only loop. No unverified catalogue or alternate model replaces the required pipeline.

The editorial model was assigning later-season years to older shows and inventing sequel identities. Scheduled research now adds a bounded, date-verified TMDB identity pool with actual premiere dates and IDs. TMDB editorial prose is stripped; Tavily/licensed evidence, hard filters and independent Clef evaluation remain mandatory. No fabricated or unevaluated entries bypass publication. A regression covers wrong premiere years, unknown dates, duplicates and excluded prose.

## Verification status

Mobile unit/assembly/lint passed: 684 cases, zero failures/errors, three skipped (681 executed), 122 suites. Worker typecheck and 209 tests passed, plus five publication-gate tests. Evidence is recorded in .validation/v176b. Live editorial bootstrap and public production APK/signature/updater/ABI/native parity and signed-payload Pixel checks remain publication gates. No permanent credentials, private transcripts or stream URLs are included in this report.
