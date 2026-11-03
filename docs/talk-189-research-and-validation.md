# Talk 3.1.189: context, motion and answer completeness

## Purpose

Talk should explain the viewer's actual question using this exact title through the pause. It must connect a current event to its earlier cause, distinguish an account or belief from an objective event, correct a false premise, and cover every requested part. Describing the paused frame does not satisfy a causal or story question. Seeking forward permits earlier-story retrieval; rewinding reduces the disclosure boundary. The latest question selects the answer language.

## Research decisions

| Finding | Engineering decision | Primary source |
|---|---|---|
| Long-film answers frequently omit necessary claims even when fluent. | Evaluate required-point recall separately from supported-claim precision. The answer contract now requires every requested point and its causal link. | [LF-VideoQA](https://arxiv.org/abs/2611.00383) |
| Several separated intervals may be jointly necessary. | An observed current scene is not proof of question sufficiency. Retrieve a missing earlier cause/reference with native scene recall; retain exact timestamps and provenance. | [TRACE](https://arxiv.org/abs/2608.22516) |
| Physical causes, motives and knowledge acquisition need different evidence. | Dialogue, belief and agreement questions use earlier story directly; physical action questions retain temporal observation. | [NExT-QA](https://openaccess.thecvf.com/content/CVPR2021/papers/Xiao_NExT-QA_Next_Phase_of_Question-Answering_to_Explaining_Temporal_Actions_CVPR_2021_paper.pdf) |
| Characters do not automatically know what the audience saw. | Preserve character-relative knowledge, assertions, lies and uncertainty; do not turn dialogue into objective truth. | [STAGE](https://arxiv.org/abs/2601.08510) |
| Valid JSON does not prove semantic correctness. | Keep manual actor/action/location and causal review. Index existence and schema validation remain structural checks. | [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output) |
| Live sessions and accumulated context consume limited capacity. | Remove duplicated transcript passages already present in story evidence. Normal independent observation uses the configured structured generation model and does not open a competing Live session. | [Live session management](https://ai.google.dev/gemini-api/docs/live-api/session-management) |
| Agentic video can explore a timeline, but requires actual supplied video. | A future bounded-clip implementation may use adaptive exploration. Existing JPEGs do not constitute that integration, and future footage must be excluded before inference. | [Gemini video understanding](https://ai.google.dev/gemini-api/docs/video-understanding), [Google launch](https://blog.google/innovation-and-ai/models-and-research/gemini-models/introducing-agentic-video-in-gemini/) |
| Semantic vectors retrieve candidates; they do not prove a physical cause. | Retain Gemini Embedding 2 and model/version namespaces. A persistent scene index remains future ingestion work, with separate dialogue/audio and visual evidence. | [Gemini embeddings](https://ai.google.dev/gemini-api/docs/embeddings.md), [Vectorize filters](https://developers.cloudflare.com/vectorize/reference/metadata-filtering/) |

## Real retrieval defect and resource recovery

The unchanged Martian test uses the 9,080.654-second extended source at 51:40. Initially it had no earlier transcript. The existing subtitle catalogue returned extended-edition candidates, but direct file downloads were challenged and the v2 single-file fallback returned `409 not_a_single_file`. Official SubDL documentation specifies `format=zip` for this response. Read-only probes recovered the exact named extended member from that documented archive route. The Worker now supports this recovery, and native extraction requires the selected member or a sole unambiguous alternative; ambiguous and forced-only alternatives are rejected. [SubDL API documentation](https://subdl.com/api-doc), [v2 developer documentation](https://subdl.com/developers)

The configured production API key subsequently reported its **daily** `api_download_limit_exceeded`. That is a separate provider-capacity condition. The Worker preserves a rate-limit status and the native loader avoids repeatedly trying the same limited origin. No provider account, credential or subscription was changed, and no anonymous request was added to evade the configured cap.

The app already contains independent original Flixer subtitle catalogues. Both were checked for this title; their accessible extended VTTs agree around 51:40 within approximately 0.23 seconds. Talk now tries supplied source captions, an independent catalogue, then the primary catalogue. This works independently of the viewer's displayed subtitle selection. CineJoy's parser also now retains captions belonging to the actual selected playlist instead of discarding that metadata.

Runtime/cadence and edition labels rank candidates; they do not certify alignment. Automatic movie selection checks beyond a first complete-looking theatrical file when the longer source suggests another edition. Long-credits candidates remain available if no better edition is found. Different cuts can require piecewise alignment; runtime proximity alone must never be described as proof of every timestamp.

The phone test also exposed two cache paths: a transcript selected before runtime was known, and a shorter cut already restored as selected captions. The first is re-evaluated when duration becomes available. The second triggers independent Talk comparison; a materially closer retrieved edition supplies both current dialogue and earlier-story evidence without changing visible subtitles. This comparison is a ranking heuristic, not verified piecewise alignment.

The final trace confirmed successful independent retrieval but revealed a delivery defect: the scene cache compared the retrieved evidence edition to the displayed caption edition, then rejected the prepared scene. Re-reading the same transcript also cleared that cache. Cache ownership now checks the displayed edition separately, and identical transcript reads preserve prepared evidence. A regression checks that retrieved dialogue and the scene remain deliverable across repeated recall.

## Motion and interaction

Applied the installed Motion skill's universal guidance and UI/UX Pro Max's native Compose guidance. The implementation uses native Compose APIs. Motion represents actual state, retains continuity across interruption, uses short physical transitions, and respects Android's animation preference. Rendering work is deferred to draw/layer phases rather than recomposing the full conversation for every audio-level update. [Compose animation guidance](https://developer.android.com/develop/ui/compose/animation/quick-guide), [Android performance reference](https://github.com/android/skills/blob/main/jetpack-compose/jetpack-compose-performance/references/animation.md)

- One mouthless pearl/lavender identity with distinct listening, reading, earlier-story recall, scene checking, thinking, text replying, speech, muted and unavailable expressions.
- Actual evidence work drives attention and light; no invented film emotion or fake numerical progress.
- Retained spatial handoff from the player orb into the full conversation, stable control hitboxes, faster critically damped travel, card-aware attention and preserved typed drafts.
- A quieter backdrop, readable activity labels, and the active title/pause shown in the player conversation.
- Reused eye paths and geometry, cached gradient, hoisted trigonometry, and stopped hidden duplicate face animation.
- Audio capture, interruption and routing configuration remain unchanged. Typed checks do not establish microphone/echo performance.

Seven physical-device UI checks passed in the optimized pass, including palette/composer interaction, card attention, semantic states and reduced motion. Frame metrics from that debug-device run: 1,112 samples, median 7.52 ms, 95th percentile 13.45 ms, 12 samples above 33.34 ms. The first pass before rendering refinement measured median 18.03 ms and 95th percentile 24.40 ms. These measurements describe the recorded workload, not a universal frame-rate guarantee.

## Answer acceptance

Before reading Talk's answer, inspect the actual footage and allowed timed dialogue. Record two to five essential answer points. Require the correct event/referent, all essential points, supported claims, correct language and no future disclosure. Reject a fluent object/frame description, circular explanation, reversed relationship or unsupported motive. Retain failed attempts; do not replace difficult titles or pause points with easier ones.

Additional unchanged scene cases:

| Title / pause | Different genuine question | Required substance |
|---|---|---|
| The Martian / 51:40 | What makes this way of contacting NASA different from a normal conversation, and how is he trying to make it work? | Recovered Pathfinder contact, still-camera constraints and rotating/sign-based communication; no yet-unrevealed solution. |
| Knives Out / 45:00 | What did Marta tell the detectives about Harlan's medication, and what makes lying risky for her? | Her stated pain/sleep medication account, and vomiting when she lies; distinguish testimony from objective events. |
| The Good Place S1E1 / 13:00 | Has Chidi agreed to keep Eleanor's secret, and what is the conflict between them? | He has not agreed; Eleanor wants to conceal the mistaken placement, while his ethical objections make him want to tell Michael. |
| Andor S1E3 / 23:00 | How is their escape plan changing, and why do they need another way out? | Closing perimeter/reinforcements, original exit plan lost and vehicle far away, replacement speeder/old-furnace route. |

Earlier failed attempts included provider quota/socket failures and an Andor answer that blurred a vehicle being forty klicks away into the distance a ride would take, while omitting the concrete obstacle. These were rejected rather than counted as plot-quality success. Final measured outcomes are appended after review.

Reviewed English responses on the physical Pixel:

| Case | Reviewed outcome | First useful / completed |
|---|---|---|
| Knives Out | Correctly reports Marta's stated Toradol/morphine doses and her childhood vomiting response to dishonesty. An earlier answer incorrectly compressed the drugs' purposes and was rejected. | 1.526 / 3.561 s |
| The Good Place | Correctly says Chidi has not agreed, explains his ethics conflict and wish to tell Michael about Eleanor's mistaken placement. | 2.473 / 3.635 s |
| Andor | Correctly connects the lost plan, vehicle forty klicks away, surrounding Corpos and the west/old-furnace route; preserves the distance relationship. | 1.657 / 3.691 s |

These are typed live questions with actual playback, retrieved timed story and current frames. They do not establish microphone, echo cancellation or universal plot accuracy. The earlier Dark, Inception, Truman Show and Severance checks are documented separately in [3.1.188 validation](talk-188-validation.md). The fuller source comparison and staged ingestion/indexing plan are in [the scene research plan](talk-188-scene-research-and-plan.md).

## Remaining limits

No implementation can infer unavailable footage or prove universal scene accuracy from a small benchmark. Sparse frames can miss brief actions; captions can lack speaker attribution or differ by edition. A persistent semantic event index, verified alignment intervals, dense question-adaptive clips, character epistemic state and a larger held-out human-scored benchmark remain the next layer. Suggested evaluation expansion: 24 questions across four further films and four episodes, including physical causes, distant setup, false premises, multilingual dialogue and rewind consistency. This is an engineering proposal, not a claimed research-derived sufficiency threshold.
