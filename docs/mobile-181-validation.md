# Mobile 3.1.181: conversation research and validation

Scope: Android mobile, version code 271. TV version and behavior are unchanged. This release builds on 3.1.180's Tavily/Gemini/TMDB recommendation pipeline and shared spoken/card selection. It does not change the voice model or introduce another AI provider.

## Research translated into behavior

Reviewed primary documentation on 25 October 2026 and inspected both requested animation references, including their available source views.

| Source | Finding and implementation |
| --- | --- |
| [Gemini Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities) and [wire protocol](https://ai.google.dev/api/live) | The provider cancels a response on interruption; the client must stop its own queued playback. `interrupted` now always pauses/flushes local output, including after committed recommendation delivery. Generation completion and playback/turn completion are distinct; chat stops showing Writing when deliverable generation finishes. |
| [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) and [AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler) | Immediate interruption uses pause/flush, rather than draining queued output. Optional sustained neural near-end detection can pause early only when platform echo cancellation is active. False interruption can resume; microphone PCM sent to Live is unchanged. |
| [LiveKit turn handling](https://docs.livekit.io/agents/logic/turns/) | Separate local playback pause from server turn ownership; distinguish intentional interruption from noise. Explicit Stop yields locally. Late stop tools cannot silence a new request. |
| [OpenAI live prompting](https://developers.openai.com/api/docs/guides/live-prompting) and [Google conversation design](https://developers.google.com/assistant/conversation-design/learn-about-conversation) | State when tools are and are not useful, answer the actual task, give short reasons, avoid forced follow-up questions and repetitive search filler. A closed A/B decision cannot be converted into open discovery. No model/provider migration is involved. |
| [Orbital letters reference](https://21st.dev/@arihantcodes_1f7b8c4d/components/orbital-letters) | Native adaptation: brief individual damped letter orbits, then a fixed baseline. Existing dotted font and Arabic shaping are retained. No web view or JavaScript animation is embedded. |
| [Animated shiny text reference](https://21st.dev/@anurag-mishra22/components/animated-shiny-text) | Native adaptation of the reversing gradient movement, using Aliflix lavender and pearl. The old wave is removed. Glyph masks and letter images are cached off the frame thread; the settled shine draws one mask. |
| [Android ComposeShader](https://developer.android.com/reference/android/graphics/ComposeShader) | A bitmap mask and translated gradient avoid retessellating thousands of dotted circles each frame. Reduced motion leaves lettering stationary; lifecycle clocks suspend offscreen. |
| [GitHub workflow triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow) | The validated draft pins its source tag with the workflow GITHUB_TOKEN. Publishing that existing tag does not trigger a second tag build and overwrite the verified assets. Existing tags must resolve to the exact source. |

## Structural changes

- Protocol 7 distinguishes closed decisions, discovery, factual lookups, capability questions, ordinary conversation and intentional silence. Older clients retain their existing protocol/tools.
- A production startup failure was traced to the app's shared 40-session-start daily cap, not the voice provider. Reconnects and mode changes consume starts. The bound is now 400 shared starts per UTC day, with the existing per-identity minute limiter, single-use tokens and session time limits retained. Daily capacity errors include the reset time and show an explicit message. This raises the bounded shared capacity; it does not remove quotas or change a provider billing plan.
- Exact `title_facts` reads canonical native catalogue details, including runtime, without another recommendation/research stage. Explicit supplied comparisons resolve both card identities in parallel even if the model omits a UI tool. Numeric titles such as Blade Runner 2049 retain their identity.
- Speech input owns a separate utterance accumulator from assistant output. Late ASR chunks update one user message even after response completion. Short intra-utterance pauses retain both clauses. Old `turnComplete` messages cannot trigger an answer nudge while VAD says the user is speaking; late output is suppressed during that interval. Tool calls wait until speech finishes and retain socket/turn/cancellation ownership.
- Same-title follow-ups keep their card; unrelated substantive questions clear stale artwork. Ordered comparison identity groups preserve the first and recent pairs within the bounded session. They contain catalogue keys only, not an additional raw-dialogue upload.
- Native library actions check the actual speech act. Quotes, hypotheticals and negated actions do not authorize mutations. Conditional changes read real state. Results distinguish a change from a no-op, and a no-op cannot accidentally expose an older undo target.
- The actual app capability snapshot describes connectivity, microphone lifecycle, bounded context/history, catalogue freshness and native-action limits. Technical questions must not be replaced with statements about emotions.
- Discover For you displays an hourly eight-title window over the existing cached pool, with an installation-local seed and daily remix. Resume refreshes the clock; rotation does not trigger additional AI research.
- My Space separates the type badge from centered rating/runtime pills. Full durations compact to forms such as `2h10m`; values stay on one line. Large-font instrumentation exercises the actual production poster component.

## Failures found by reviewing complete live outcomes

The audit examined full replies, tools and canonical card identities, not only keyword assertions. Earlier candidates exposed these defects and were not released:

1. A runtime question included an obscure same-name 2023 title alongside the intended 2015 Ex Machina. Full-title matching and dominant-feature selection were repaired; explicit alternative years still take priority.
2. Recalling the first comparison returned the latest comparison. Ordered identity groups and ordinal semantics were corrected. "First question" is not "first current card."
3. A late Stop acknowledgement swallowed the next greeting/math question. A new typed request after Stop gets a new socket ownership boundary, and stale wait tools cannot consume the current request.
4. A partial known-card match dropped the other, newly named comparison option. Both actual options now resolve, and the assistant's preference alone does not collapse the comparison.
5. Correct text answers left stale film artwork during unrelated technical or arithmetic questions. Native topic policy now dismisses it while preserving genuine title/pronoun follow-ups.
6. Large-font metadata and the first orbital rendering candidate failed quality checks. The former prompted explicit centered content bounds/line metrics; the latter prompted cached letter images and a single settled mask rather than per-frame paths.
7. Extended testing exhausted the shared daily start cap and normal Talk startup also failed. The native connection diagnostic confirmed `DAILY_LIMIT (HTTP 429)` before any provider socket opened. Capacity and error messaging were corrected and speech checks repeated afterward. Voice fixtures also explicitly select voice mode rather than depending on a previous test's mode.
8. A spoken correction passed the original choice/card assertions but its full receipt showed a fragmented user message and resumed old narration. An opt-in trace of owned synthetic speech confirmed an interrupted old `turnComplete` arrived before the new ASR, causing an erroneous silent-answer nudge; assistant output then split the independently arriving input transcript. Separate input ownership, suppression during user speech, utterance coalescing and deferred tools address that sequence. New wire regressions replay the captured ordering, and the live assertion now requires the complete choice transcript and no old-topic continuation.

## Real-service and physical-device coverage

Connected Pixel 7a, serial `41301JEHN02777`, authorized ADB. Installs use `-r`, preserving application data and the existing authenticated account. No local emulator is used for physical UI or speech claims.

The live 24-turn journey includes greetings; Arrival versus Ex Machina; neither fitting 100 minutes (116/108); both cards; second-option violence without spoilers; hypothetical and quoted save commands; concrete technical limits; other-app/microphone boundaries; hypothetical Stop; library distinctions; 13 × 17; German translation without changing English conversation; Moon versus Source Code; a real two-title Tavily recommendation; recall of the original pair; conditional save and undo; original Blade Runner versus Blade Runner 2049; search failure discussion; Annihilation 2018 and edition follow-up; intentional Stop; and a new greeting/math question afterward.

Separate tests interrupt real long-answer playback and already-committed recommendation playback with owned synthetic spoken Stop PCM. Another test replaces a long answer with an actual spoken A/B correction. These use real Live input/output on the phone. Injected owned PCM tests provider/ASR/turn behavior; it is not proof of every acoustic room, voice or microphone condition.

Deterministic wire tests cover delayed `turnComplete` after `generationComplete`, interruption after presentation commit, interrupted lookups, duplicate presentation and idle watchdog behavior. Physical rendering exercises Latin and shaped Arabic with the orb/background active. Large-font pill validation uses 132dp posters and 1.7 font scale. The release's final evidence record reports final outcomes and exact counts.

## Evidence boundaries

Passing these checks does not establish that a probabilistic voice system can never give a poor answer. Network/provider outages, ambiguous speech, bounded context and unsupported native operations remain real technical limits, and the app should explain the relevant one concretely.

The connected phone has a debug signer. Production artifacts are verified independently. If a debug-signed comparison of the minified release payload is installed, every non-signature ZIP entry is checked against the official APK first; that demonstrates payload behavior, not an official production-signer in-place upgrade. Published APK hashes, signer/ABI/native parity, canonical updater and CI are recorded separately after release.
