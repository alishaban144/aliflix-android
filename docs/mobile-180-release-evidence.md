# v3.1.180 release evidence

Published **2026-10-24 16:40:04 UTC**. Mobile only. TV UI, version and release assets remain unchanged.

- Tested Android/Worker source: `f165d1e56d701965171d46a5ef9fd687c7fa8c81`.
- [Full signed-release workflow](https://github.com/alishaban144/aliflix-android/actions/runs/40110252587), [release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.180), [implementation and acceptance record](mobile-180-validation.md).
- Final CI-deployed Worker: `964d79b4-4454-43c4-ad7f-25b2a80832bf`. Watch Party protocol and five live genre shelves pass after deployment.

## Artifacts

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| ARM64 mobile, code 270 / 3.1.180 | 26,626,538 | `1dde69b3767dcbdc1e934664361e3e75cc8f583164e1f64380c9af71071f8c20` |
| Universal compatibility bridge, code 265 / 3.1.175 | 67,005,928 | `75d3c1d4190da61c010abb09f9adb99d58a3a1c6c77e0f8df0306eb1dcdea73c` |

Both APKs pass ZIP integrity and production signature verification with SHA-256 `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. Independent native/model comparison uses the hash-pinned v3.1.164 APK: five ARM64 libraries in the main APK, twenty libraries across four ABIs in the compatibility bridge, and the unchanged Silero model. Both public APK downloads are byte-identical to the verified draft. The public ARM64 hash/size match the canonical updater manifest; the compatibility APK matches its legacy manifest.

## Automated checks and physical phone

- The exact final source passes the full local Android gate: 733 unit tests, zero failures/errors, three skipped, mobile lint and shared TV compilation. Mobile app/test APK assembly also passes; the phone's final five-test suite uses those APKs. The hosted API 35 regression suite passes **92 tests**, including the three Talk wire replays. CI repeats the complete unit/lint/build/signing/parity gate on the same source.
- Worker typecheck and all 244 tests in 27 files pass locally and in final CI; six publication-readiness script tests also pass. A CI-only test-counter collision from another fixture's bootstrap alarm was isolated by fixture credentials, preserving the strict one-search/one-selection and cache assertions.
- Authenticated physical Pixel 7a `41301JEHN02777`: the final suite passes **five tests in 206.075 seconds**, including **16 live conversation turns**. Three deterministic wire replays cover interrupted/flushed output, late duplicate presentations, and held unapproved output incorrectly clearing the response deadline.
- The four-turn discovery check starts with owned PCM saying **movies about gentlemen**, without Hi or Hello. The actual spoken and displayed choices are The Gentlemen, Kingsman: The Secret Service and Casino Royale. It then requests two less violent stylish titles, explicitly searches the web for two recent science-fiction identity films, and forces the app-owned discovery path for two thoughtful mysteries. Every turn names all displayed choices, delivers physical AudioTrack output and completes without a presentation/connection issue; zero delivery corrections.
- First audio after input for those four turns is 0, 1.103, 2.335 and 0.053 seconds. The first value overlaps the owned input/connection interval; its connection-to-audio measurement is 5.145 seconds. These include contextual bridges, not complete recommendation latency. Whole turns including speech take 32.074, 22.803, 27.925 and 23.703 seconds; no latency guarantee is implied.
- The twelve-turn journey covers Hi, three researched science-fiction films, actual loaded poster images, third-choice focus, pronouns, restoring the group, Alex Garland's Annihilation (2018), repeated details/card requests, real saved-state lookup, save, undo, and Hello. Arrival, Ex Machina and Blade Runner 2049 have matching real posters; asking about the third one leaves only Blade Runner 2049 (TMDB 335984) focused. Annihilation remains TMDB 300668 through follow-ups. No screen-visibility announcement is accepted. Save/undo restores the original library state.
- Physical wave rendering passes with Latin and Arabic topics. The recorded 1,050-frame interval has zero modern janky frames, missed Vsyncs and missed frame deadlines; GPU P95 is 6 ms. The separate legacy jank counter remains high and is not reported as zero. Continuous 60/90/120 Hz motion and reduced-motion behavior have focused host checks.
- A separate normal-UI conversation answers “Why is the sky blue? Answer in one sentence.” correctly, then returns to voice with the retained topic lettering and orb. It does not refuse the general question or require a greeting.

## Live recommendation service

Fresh Similar result generation on the final production code uses cached Tavily findings plus one new Gemini selection, zero GPT/Clef calls, and parallel structured TMDB creative relationships:

| Anchor | Returned | Expected title | First results | Completion |
|---|---:|---|---:|---:|
| Breaking Bad | 24 | Better Call Saul, first | 1.502 s | 8.967 s |
| The Killing, Danish (2007) | 24 | The Chestnut Man, first | 1.451 s | 7.075 s |
| The Killing, US (2011) | 20 | The Chestnut Man, first | 1.527 s | 5.378 s |

The US request records an actual Gemini generation failure and completes through the Tavily/TMDB fallback. The relationship path is generic; no acceptance title pairs are hardcoded. The catalogue has no exact series named The Killing (2017), so both real catalogue versions are covered.

A fresh uncached narrow winter-mystery Describe request returns ten real films, including Fargo, The Girl with the Dragon Tattoo, A Simple Plan, Wind River, Insomnia and Winter's Bone. First titles arrive in 5.658 seconds; completion is 9.264 seconds. Metrics show one Tavily credit, one Gemini selection and zero GPT/Clef calls. The bounded complete result has no pagination cursor. Timings describe these requests, not a service guarantee.

## Installed minified payload and updater

The verified minified comparison payload installs successfully as code 270 / 3.1.180, without uninstalling or clearing data. Through the normal UI, a fresh two-film science-fiction request returns Arrival and Ex Machina with matching reply text and loaded posters. Asking about the second film leaves Ex Machina focused and returns a relevant one-sentence description. Switching to voice retains its card, orb and animated EX MACHINA lettering. The card opens the actual native Ex Machina (2015) details screen; Back preserves the same focused card. Talk is then closed. The normal Settings Check control reports **Aliflix 3.1.180 is up to date.**

The phone's existing debug signer is `519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`. A local comparison copy retains that signer to preserve app data. All 1,062 non-signature ZIP entries, including META-INF services and other metadata, match the production download byte-for-byte; the production-signed original is unchanged. This is minified production-payload runtime evidence, not an official production-signer upgrade over a debug installation.

The app's actual default update URL, [`latest/download/update-mobile-arm64.json`](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile-arm64.json), advertises code 270 / 3.1.180, ARM64, minimum SDK 29, the verified APK URL, 26,626,538 bytes and SHA-256 `1dde69b3767dcbdc1e934664361e3e75cc8f583164e1f64380c9af71071f8c20`. The legacy [`update-mobile.json`](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile.json) deliberately retains the four-ABI bridge at code 265 / 3.1.175, 67,005,928 bytes and SHA-256 `75d3c1d4190da61c010abb09f9adb99d58a3a1c6c77e0f8df0306eb1dcdea73c`. The published tag resolves to the exact tested source above. The publication-readiness gate passes with 24 movies and 24 series published atomically at `2026-10-23T16:52:53.715Z`.

Owned PCM and typed prompts exercise the real live session, ASR/VAD path and physical AudioTrack. They do not establish all human acoustic microphone/accent conditions. Host tests, hosted API 35 tests, functional phone checks, minified-payload checks and public artifact verification are separate evidence. No app-data wipe, uninstall or account replacement is performed.
