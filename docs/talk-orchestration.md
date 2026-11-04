# Aliflix Talk
## Product and technical orchestration
**Mobile architecture · version 3.1.191 · reviewed 4 November 2026**

Aliflix Talk combines a conversational film companion with native player control. Ask Aliflix handles discovery, questions and app tasks. Talk in Player adds evidence from the selected movie or episode at the paused playhead. The same conversation can continue across input modes and follow-up questions.

### Read this walkthrough
1. [System overview](https://whimsical.com/L7GWbKpBuqBbsjMTRm1Y3T) — the complete product and service boundary.
2. [Player scene retrieval](https://whimsical.com/DfrbBpLmVixzNcZapDyJ5F) — how current and earlier evidence becomes an explanation.
3. [Research, recommendations and actions](https://whimsical.com/Pd5849r73Cbvh6dHhsBFiv) — the three tool paths.
4. This document — data contracts, runtime behavior, engineering evidence and limitations.

## 1. One conversation, two entry points
**Ask Aliflix:** the viewer speaks or types a question, requests recommendations or gives an explicit app command. The conversation orchestrator selects the relevant capability and uses existing context when it is sufficient.

**Talk in Player:** opening Talk pauses playback, binds the selected title, episode, media source and timestamp, and prepares recent scene evidence. Voice is the default. A single button switches between voice and typing. Selecting voice again reconnects microphone capture without creating a new chat.

The orb communicates activity through motion rather than status prose. The floating bubble grows with streamed text and supports retained history in both scroll directions. Reopening starts at the current interaction; earlier messages remain available above it.

Voice responses stay in English by default. Established conversation language changes only after a clear request. Noise, a foreign title, quoted dialogue or an incidental greeting does not authorize a language switch or a fresh conversation. Intentional typed questions retain language-aware handling.

## 2. Runtime and service boundaries
| Component | Responsibility |
|---|---|
| Android / Jetpack Compose | Native UI, chat state, touch-aware orb, input mode, microphone/speaker ownership and action confirmation |
| Media3 player integration | Active playable source, paused timestamp, current frames, played audio, captions and same-source recall |
| Cloudflare Worker | Authenticated scoped sessions, tools, source retrieval and background orchestration |
| Gemini 3.8 Live | Streaming conversation, speech and image understanding; capability selection and evidence-based explanation |
| Cloudflare Durable Objects / SQLite | Expiring session and research state, account chat archives, agent missions and recommendation continuation |
| External evidence and catalogue APIs | Supported title/episode facts, research, canonical identity, artwork and ranking inputs |

The app obtains scoped temporary Live authorization through the backend. **Live audio, text and images then travel directly between Android and Google's Live WebSocket.** They are not relayed through the Cloudflare Worker. Worker tool requests use the authenticated session separately. Long-lived AI service credentials remain on the backend.

## 3. How Player Talk retrieves a scene
1. **Anchor the request.** Record the canonical title/episode, selected media source, source revision and paused timestamp. Cancel or reject stale work after source changes or a seek.
2. **Capture temporal evidence.** Prepare a recent sequence of timestamped images, played audio and dialogue. A sequence helps explain action; a single static frame is insufficient for a causal answer.
3. **Prepare earlier context.** When a timed transcript is available, index its cues locally into retrievable windows from the beginning through the pause. The system compares relevant source/track information and flags alignment uncertainty. Already observed captions remain useful when full transcript retrieval is unavailable.
4. **Retrieve for the question.** Supply bounded current dialogue and earlier-story context. The full available prefix remains locally retrievable; it is not all pasted into every model request. The local scene_recall capability retrieves additional passages or timestamps when needed.
5. **Retrieve earlier visuals selectively.** For a question about a prior visual action or a specific timestamp, decode relevant frames from the same active playable source, bounded by the paused moment.
6. **Explain the cause.** Combine current action, earlier dialogue and setup. Resolve pronouns and visual referents. Correct a false premise, preserve exact details and distinguish observation, a character's claim and inference.
7. **Deliver coherently.** Stream the explanation to speech or the growing bubble, retain the conversation, and keep the film paused.

An optional independent scene observer receives images/audio without title and cast hints to reduce plot-based guessing. Its output is auxiliary evidence. A slow or failed observer backs off; it must not block the direct frames/transcript path indefinitely.

**Example:** “Could that radio voice just be a traffic update? Why does it say extras?” requires the current radio dialogue and earlier setup. The useful answer explains production coordination, relates earlier suspicious events and avoids claiming that one line alone proves the entire conspiracy.

## 4. Research and recommendation paths
**Film facts:** resolve the exact title and episode; reuse eligible evidence; retrieve supported Wikipedia/Wikidata, TVmaze and episode-specific material; use Tavily when permitted and necessary. Preserve source attribution, licensing information and spoiler scope. TMDB supplies canonical catalogue identity and metadata; its overview is not treated as independent proof of a scene.

**Recommendations:** research candidate titles, resolve/hydrate canonical TMDB records, apply filters and rank eligible results. Gemini Embedding 2 computes semantic relevance using catalogue descriptions, genres and keywords. Ranking includes deterministic signals and has a deterministic fallback when embedding service capacity is unavailable. The exact selection passes the presentation contract before native speech and cards are committed together.

**Important distinction:** Embedding 2 is used for recommendation relevance. The current app does not contain a universal precomputed vector index of every movie scene. Player recall uses available timed transcripts, captured evidence and on-demand decoding of the selected source.

## 5. Native action orchestration
An explicit command becomes a task with canonical title IDs and ordered dependencies. For example, starting an episode may require choosing a source, selecting available audio/captions and then seeking or resuming. Android executes supported capabilities and observes actual player/library/download state.

“Queued” is not reported as “completed.” The assistant distinguishes completed, pending, partially available and unavailable outcomes. Follow-ups retain task identity. Required account or destructive-action confirmations remain native.

## 6. Conversation and audio reliability
Local voice activity detection and platform echo processing govern input ownership. Audio leases prevent competing microphone/speaker owners. Epoch and turn checks reject stale network callbacks, delayed transcriptions and obsolete responses. A genuine interruption can stop stale narration while preserving conversation identity and already authorized background work.

Switching input modes changes the active transport/capture behavior, not the chat. Retained context is replayed when a fresh Live connection is required. Provider outages and quota failures remain separate from user intent.

## 7. Data and trust
| Data | Handling and purpose |
|---|---|
| Scene frames, short played-audio context and dialogue | Supplied for the active conversation and bounded scene analysis; third-party AI processing applies |
| Source/title/episode/timestamp/revision | Binds evidence to the current playback context and rejects stale work |
| Available transcript windows | Local retrieval through the pause; broader evidence is fetched only when relevant |
| Public episode facts | Eligible shared evidence cache; user questions and spoiler boundaries are kept separate |
| Conversation archives | Account-scoped; history preference applies; server archive retention is bounded |
| Session/tool state | Expiring Durable Object records, scoped grants and bounded tool/rate budgets |

This diagram describes implemented boundaries, not an independent security or privacy certification. No private credentials, user identifiers or signed playback URLs are exposed in the walkthrough.

## 8. Engineering evidence
Validation combines code checks with physical-phone UI and audio evidence. For this iteration: **163 focused Android Talk tests, 312 Worker tests, 21 focused physical-device checks, six large-font/reduced-motion checks, and a real microphone mode-return test**. CI separately checks playback behavior, Worker types/tests, release integrity and signed artifact creation.

Reviewed scene cases cover The Truman Show, Parasite, Severance S1E1 and Better Call Saul S1E1. Questions test false visual premises, exact dialogue details, earlier setup, semantic versus autobiographical memory and allegations versus proven events. Review includes complete responses, timing, rendered speech and surrounding UI behavior.

The [validation report](https://github.com/alishaban144/aliflix-android/blob/codex/talk-191-voice-scene-bubble/docs/talk-191-validation.md) and [complete scene results](https://github.com/alishaban144/aliflix-android/blob/codex/talk-191-voice-scene-bubble/docs/talk-191-scene-results.json) preserve the evidence and scope. Release publication additionally requires signer, updater, public-byte and physical in-place-upgrade checks.

## 9. Honest limits and the next architectural step
A useful causal answer depends on available evidence. External captions can be incomplete or belong to a different cut. Sparse images can miss a brief action. Some sources cannot be decoded reliably. Research sources may not provide scene-level detail. AI services have latency, outage and quota constraints. These are reasons to retrieve better evidence or ask a precise clarification, never to invent a scene.

Earlier context can be retrieved after a seek when the relevant transcript/source is available. This is not a guarantee of exhaustive knowledge of every title or every visible action.

A genuine catalogue-wide scene index would require lawful access to matching audiovisual cuts and transcripts; source/edition identifiers; timestamp alignment; ingestion and scene segmentation; multimodal indexing; attribution and rights management; storage/update budgets; and a measured evaluation set. No free online service has been represented here as providing that complete catalogue.

The current design instead prioritizes grounded, bounded retrieval from the selected source, with fast prepared context and targeted recall.

---
**Implementation reference:** [Aliflix Android repository](https://github.com/alishaban144/aliflix-android) · mobile source baseline 221594f7. TV is outside this change.

## Release verification · 4 November 2026

[v3.1.191 is public](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.191). Its production-signed ARM64 APK installed in place on the connected phone with the original install timestamp and baseline recently-played data preserved. Anonymous public APK and updater downloads match the verified draft bytes. TV is unchanged.

The final real microphone test passed two typing-to-voice reconnections in the same English conversation. Additional noise/scene attempts retained English and chat identity, but could not complete the scene reply: the last Live close explicitly reported exceeded Google quota. These attempts are not counted as successful end-to-end scene answers. The earlier six reviewed scene turns remain the bounded scene-quality evidence; provider capacity is a current operational limitation.

