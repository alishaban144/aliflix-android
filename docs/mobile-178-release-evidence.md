# v3.1.178 public release evidence

Published 2026-10-22 21:50 UTC. Mobile only; TV version/UI are unchanged.

- Android source/tag: `fcb211acd5d33931876f354e9368d86c7eb0630a`, [full successful workflow](https://github.com/alishaban144/aliflix-android/actions/runs/39857881558).
- Production Worker source: `42c9fcaee50db676a0e8678b15c35d3da7726f54`, [successful backend-only workflow](https://github.com/alishaban144/aliflix-android/actions/runs/39862041338). Android sources are identical to the signed release. This follow-up removes obsolete per-network daily session counters; the global 40-session daily budget and existing per-minute identity limiter remain.
- [Public release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.178) and [native/model parity report](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.178/native-parity.json).

## Independently downloaded artifacts

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| ARM64 main APK, versionCode 268 / 3.1.178 | 26,540,634 | `a4d7886f98450b998e4105aea12db8809728bcce2ea3592ac18659fd90a36fc5` |
| Universal compatibility APK, versionCode 265 / 3.1.175 | 66,919,780 | `73e132595961febebed64b58c25bf7c1fbbe20b1346e002e411f332171920ca3` |

Both APKs pass ZIP integrity and use production signer SHA-256 `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. Public downloads are byte-identical to the verified signed draft files.

The main APK contains five original `arm64-v8a` native libraries. The compatibility APK retains all 20 original libraries across `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. Native/model parity passes against the pinned v3.1.164 baseline. The original `assets/silero-vad-8k.onnx` remains SHA-256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`.

## Checks and measured behavior

- Local Android: 713 unit tests, zero failures/errors, three skipped; mobile lint and app/test APK assembly pass. Shared TV debug assembly passes. CI repeats unit/lint/signing/parity gates; its API 35 phone suite passes **85 tests**, including the new cards and Surprise/dislike checks.
- Final Worker: typecheck and **235 tests** pass locally and in CI. The new regression proves obsolete shared-network counters cannot block normal starts and the project-wide daily cap still applies. Shared cancellation is checked for its exact query independently of scheduled daily refreshes.
- Physical Pixel 7a: authenticated ADB, 14 focused UI tests pass. Live speech, typed subject replacement, isolated-history cancellation, rapid Stop/replacement, completed Describe/Similar streaming and native-details return pass across focused runs. The optional cross-account archive fixture was skipped; server archive isolation/card persistence and local serialization have regression coverage.
- The final speech fixture asks “movies about gentlemen” without a greeting or `audioStreamEnd`, then requests less violent stylish titles and recent science fiction about identity. Every turn returns at least two real identities, renders native audio and names a returned title. First audio: 6.83 s including initial connection and speech input, then 1.05 s and 1.19 s for follow-ups. Choices include Kingsman/The Gentlemen, A Single Man/Phantom Thread and The Creator/Foe. No stall recovery is needed. Model phrasing can still repeat an introductory sentence; a bridge alone cannot pass acceptance.
- The authenticated phone client receives 24 completed Describe results in 7.01 s, first results 0.82 s; Similar returns 24 in 4.33 s, first results 0.78 s. A fresh public query records one Tavily credit, one Gemini curation and zero GPT/Clef calls, returning 12 titles in 9.34 s. Warm public probes return 24/24 titles and three Surprise choices. These are measured requests, not fixed latency promises.
- Stress testing initially exhausted the old per-network session cap, producing an explicit HTTP 429 `DAILY_LIMIT`. After removing that redundant cap, the same physical network receives HTTP 200 with `gemini-3.8-live`. The overall project daily budget remains bounded; upstream availability and that budget still affect service access.

## Installed release payload and updater

The phone originally used debug signer `519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`. To preserve its app data, the public APK was copied locally and signed with that same existing debug key. **All 958 non-`META-INF` entries are byte-identical to the public APK.** This minified release-payload comparison copy is installed as versionCode 268 / 3.1.178. It is not proof of an official production-signer upgrade over the debug installation. No uninstall, app-data wipe or account replacement was performed.

Through the installed minified payload's normal UI, a typed gentlemen request returns The Gentlemen (2020) and Kingsman (2015) with real posters and compact cards. A card opens native details, and Back preserves the same reply. Switching to voice retains the orb, animated topic and card rail together. Talk is closed after the check, leaving MainActivity resumed and capture stopped.

The original v3.1.177 phone build displays the real update indicator and Download offer. The app's actual default URL, [`latest/download/update-mobile-arm64.json`](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile-arm64.json), advertises versionCode 268, ARM64, the correct public APK URL, size and SHA-256. After restoring v3.1.178, its explicit in-app check reports **“Aliflix 3.1.178 is up to date.”** The legacy [`update-mobile.json`](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile.json) retains the universal compatibility bridge for older clients.

Owned speech tests exercise real ASR/VAD and physical AudioTrack playback, rather than a human acoustic microphone conversation. Earlier functional instrumentation uses the debug build; final normal-UI chat/cards/details/updater checks use the payload-identical minified comparison copy. These evidence types are deliberately distinguished.
