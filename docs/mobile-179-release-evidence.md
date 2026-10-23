# v3.1.179 public release evidence

Published 2026-10-23 17:14:28 UTC. Mobile only; TV UI/version remain unchanged.

- Signed Android source/tag: `135c8813`, [full successful workflow](https://github.com/alishaban144/aliflix-android/actions/runs/39987908405).
- [Public release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.179), [native/model parity](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.179/native-parity.json), and [implementation/acceptance record](mobile-179-validation.md).
- Final production Worker source is `2279accf`, version `40976ed2-868e-4ced-99b3-4e83329d4fce`, [backend-only CI/deployment](https://github.com/alishaban144/aliflix-android/actions/runs/39990489194). Android production sources are byte-identical to `135c8813`; this follow-up corrects stale daily discovery recovery and adds a physical player fixture. CI checks live Watch Party protocol and genre browsing after deployment.

## Independently downloaded artifacts

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| ARM64 mobile, code 269 / 3.1.179 | 26,585,622 | `48055a3e2184645c9091d8131b546681d2afb761ce20902a388203351dc70492` |
| Universal compatibility bridge, code 265 / 3.1.175 | 66,964,744 | `b6362c5f14ac49bfbc1ce64e03a8873efb98078101dd19a6c1016f11141070ec` |

Both APKs pass ZIP integrity and production signature verification: SHA-256 `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. Public downloads are byte-identical to the verified draft artifacts. Native/model parity is checked independently against the pinned v3.1.164 baseline: five ARM64 libraries in the main APK, twenty libraries across four ABIs in the bridge, and the original Silero model.

## Checks and physical behavior

- Android: 720 local unit tests, zero failures/errors, three skipped; mobile lint, debug/device APK assembly and shared TV compilation pass. CI repeats unit/lint/signing/parity and its API 35 regression suite passes 89 tests. These hosted emulator checks are distinct from the physical phone evidence; no local emulator was used.
- Final Worker: 238 tests and typecheck pass locally and in CI. No voice/model fallback was introduced.
- Authenticated Pixel 7a `41301JEHN02777`: the final functional build passes 14 focused tests covering card persistence/centering, retained orb/topic, Ask layout and result positioning, intentional scrolling, switching Describe to Similar, in-card immediate dislike, three-choice Surprise Me, native Settings/Downloads/Ask destinations and three live Similar requests. The final one-line Surprise panel contrast change also passes its focused physical test and normal visual inspection.
- A separate physical fixture verifies Talk's actual Media3 player_state, pause, seek and resume commands against real local video, without provider-network dependence or viewer-library writes.
- One continuous live session passes all twelve turns: spoken Hi, Hello, a general question, movies about gentlemen, third-title focus, pronoun follow-up, explicit retrieval of the mentioned title, a newly requested Dark card, unrelated subject change, typed Hi, typed Dark retrieval, and a real native setting command using its existing value. Every voice turn renders AudioTrack output; zero stall recoveries. This caught and fixed an immediate-tool job-registration race that left Thinking active even after an answer.
- The final live choices include The King's Speech, Kingsman and The Gentlemen. The Gentlemen remains focused through follow-ups and the mentioned-title request. Dark resolves to the real 2017 series, TMDB 70523, in both modes. First audio is 6.51 seconds for the initial cold connection plus speech input, 2.04 seconds for Hello, 2.89 seconds for the recommendation bridge, and 0.64-1.91 seconds for the measured title follow-ups. These are observations, not latency guarantees. Typed native-setting completion takes 4.76 seconds and returns to an idle state.
- Through the normal UI, a new Dark card displays its poster/title/year beside the retained companion orb/topic; asking Talk to open app settings opens the real settings window. The three-choice Surprise panel is visually inspected after making its background opaque, so underlying page text no longer competes with the choices.
- Live Similar through the phone returns 24 real series for each anchor: Breaking Bad includes Better Call Saul; Danish The Killing (2007) and US The Killing (2011) both include The Chestnut Man. Their final cached request durations are 0.48, 0.45 and 2.62 seconds. The catalogue has no exact 2017 series named The Killing. Earlier host checks also retain Better Call Saul when marked watched, use one fresh Gemini selection over cached Tavily findings, and complete in 5.55-6.23 seconds.
- A fresh uncached narrow Describe request returns eleven titles, including Wind River, Fargo, Hold the Dark, Winter's Bone, Insomnia and A Simple Plan. Service metrics record one Tavily credit, one Gemini selection, zero GPT/Clef calls, and real TMDB matches. First titles arrive in 5.15 seconds; completion is 12.34 seconds. Cached and uncached timings are reported separately.

The release gate initially rejected research collections dated October 20. An incomplete refresh returned 18 movies and 10 series and correctly retained the previous researched pair. Stale daily research now uses the existing fresh atomic catalogue fallback, with canonical TMDB sources, zero invented match scores and unchanged refresh budgets. The unchanged readiness gate then passes with 24 movie and 24 series suggestions, both published at `2026-10-23T16:52:53.715Z`.

## Installed minified payload and updater

The Pixel's existing debug signer is `519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`. To preserve its data, a local comparison copy uses that same signer. All **958 non-`META-INF` ZIP entries are byte-identical** to the verified public APK; the production-signed download is unchanged. The minified comparison payload is installed as code 269 / 3.1.179. Through its normal UI, a newly requested Better Call Saul card appears, opens native series details, survives Back and a mentioned-title follow-up, and remains beside the voice orb and animated dotted topic. Asking Talk to open app settings opens the actual settings window. Talk is closed after validation, leaving MainActivity and no active voice capture.

The app's actual default update URL, [`latest/download/update-mobile-arm64.json`](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile-arm64.json), advertises code 269 / 3.1.179, ARM64, the verified public APK URL/hash/size. The legacy [`update-mobile.json`](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile.json) deliberately retains the four-ABI compatibility bridge at code 265 / 3.1.175. The installed minified payload's normal Check control reports **'Aliflix 3.1.179 is up to date.'**

Owned PCM speech fixtures exercise real live ASR/VAD and the physical AudioTrack, rather than human acoustic microphone recognition. Functional instrumentation uses matching debug builds. Final normal-UI minified-payload checks are stated separately; no official production-signer upgrade over a debug-signed installation is claimed. No app-data wipe, uninstall or account replacement was performed.
