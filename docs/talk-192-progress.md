# Player Talk 3.1.192

## User acceptance scope

- Simple white ring/two-eyes Talk icon alongside subtitles, quality, rotation and More, using the existing optical grid/stroke and touch targets.
- Remove the square during actual orb press/drag/active voice, preserving the moving halo.
- Replace frame-only/circular answers and unnecessary unknown-name refusals with character- and plot-aware explanations using sources beyond subtitles.
- Review Disclosure Day at the supplied 01:04:12 example and new movie/episode questions, including names, prior causes, false premises, follow-ups and spoiler boundaries.
- Physical connected Pixel 7a, production signer/data preservation, public GitHub release; TV unchanged.

## Current implementation

- Toolbar action uses the existing PlayerLineAction 24-unit grid, 1.65 rounded stroke, 26dp glyph and 48dp target. More no longer duplicates it. Entry toggles Talk.
- Parent orb interaction is clipped to CircleShape. Previous clipping covered only the halo Canvas, allowing rectangular pressed indication outside it.
- Versioned v16 `scene_story` and authenticated `/v1/talk/story` capability retrieve canonical credits and independent film/exact-episode research. Wikipedia/TVmaze are tried with existing web research as a fallback. Only a title/episode research query is sent to search, never the user's frames or transcript.
- The normal answer uses **one Gemini Live multimodal pass**: actual temporal images, independently retrieved story references/credits, and relevant passages retrieved from up to 120,000 characters of same-edition earlier dialogue retained locally. Independent story entities expand vague questions into meaningful earlier-dialogue searches. Compact source-relative timestamps preserve every included line without repeated JSON start/end numbers. Exact cue intervals and omitted passages remain available through recall.
- The older separate Live observer is skipped for v16. Full-scene interpretation belongs to the active conversation, avoiding additional concurrent Gemini inference sessions and a blocking analysis dependency.
- Research is explicitly marked untimed, with its own source IDs/URLs. It is not falsely called an observed scene or a certified face binding. Earlier transcript/images and the pause boundary control what may be revealed. Character names need not be spoken in the current shot.
- Source cache is separate from user/session/source/pause-scoped context. Requests validate title/episode ownership, input size and temporal bounds. Re-seeking invalidates current scene ownership.
- An optional separate structured analysis API remains available for controlled analysis callers, but the mobile conversation uses `mode=sources`; it does not wait on another generation provider or replace Live speech with a different engine.
- Gemini Embedding 2 remains the recommendation embedding setting. No new scene-data route to Groq was deployed.

## Research findings and design rationale

The UI skill's database search was not specific enough; its accessibility/stroke guidance plus the actual native components govern the implementation. New web UI libraries are inappropriate here.

- [TMDB movie credits](https://developer.themoviedb.org/reference/movie-credits) and [exact episode credits](https://developer.themoviedb.org/reference/tv-episode-credits) supply canonical fictional-character spelling/roles. They are not a face binding.
- [StreamCast](https://arxiv.org/abs/2610.37579), [StoryTeller](https://arxiv.org/abs/2411.07076) and [MovieGraphs](https://openaccess.thecvf.com/content_cvpr_2018/papers/Vicol_MovieGraphs_Towards_Understanding_CVPR_2018_paper.pdf) motivate joint dialogue/video identity and causal relationship memory.
- [STAGE](https://arxiv.org/abs/2601.08510) motivates distinguishing an earlier event from the later point when it is revealed. A full plot paragraph is not timestamp-safe evidence.
- [Official Peacock credits](https://www.peacocktv.com/stream-movies/disclosure-day) and [NBCUniversal featurettes](https://www.nbcuniversal.com/article/disclosure-day-only-theaters-june-12) establish Daniel Kellner/Jane Blankenship canonically. Exact scene binding and earlier causes must still be checked against the selected footage/transcript.
- [Universal edition facts](https://www.universalpicturesathome.com/press-release/disclosure-day-fact-sheet) list SDH and a 02:25:27 edition, but runtime alone cannot certify matching cuts. External caption selection remains alignment-qualified.
- Persistently cached source records use direct independent sources, not harvested Google Search-grounded results. [Gemini grounding terms](https://ai.google.dev/gemini-api/terms#grounding-with-google-search) impose a separate retention boundary.

## Current checkpoint

Branch `codex/talk-192-character-context`, based on merged v191 main. Pixel 7a 41301JEHN02777 connected and awake; production remains v191/281 until signed upgrade verification. Worker typecheck passed before the latest new tests. Next: Worker tests/typecheck, compile Android, physical UI/new scenes, improve on observed failures, deploy/test backend, signed release and public-byte verification.

## Test-driven corrections

- Exact Disclosure Day footage and selected timed captions were inspected at 01:04:12 on the Pixel. The former 12,000-character page contained only 402 of 1,248 eligible cues and stopped at about 20:20. The complete earlier dialogue remains locally searchable up to a 120,000-character budget, with question-relevant passages supplied to Live.
- The pressed-orb corner test and nine existing physical bubble/voice/motion checks passed. Four corners remained the exact surrounding colour during a held press; the whole-screen image was reviewed.
- 872 Android host tests passed (three existing skips); mobile lint and the unchanged TV assembly passed before the final recovery edit. Backend full suite: 324 tests.
- Actual testing exposed a Gemini Live quota close and a slow independent inference path. The slow model was removed. Scene analysis retries Gemini 3.8 Flash first as explicitly requested; current Cloudflare Qwen 3.8 27B is a secondary path only for invalid structured output. Explicit HTTP refusals are reported promptly in the test receipt rather than hidden behind another slow inference. Both use the same evidence validator. The existing Gemini Embedding 2 recommendation setting is unchanged.
- No new Groq scene-image/dialogue path is present. Automatic approval review rejected that proposed destination; it was removed, not bypassed. Shared production log export was also declined; diagnosis uses only the test phone's own conversation receipts.
- Both typed and spoken normal player answers use Gemini Live. Source retrieval is independent of its inference capacity; no alternate provider is silently used to fabricate a failed answer.
- Initial Get Out and The Good Place answers were reviewed in full. Names and earlier setup improved, but a latency gate and a vague yes/no follow-up failed. The checks remain strict. New runs use immediate seeks and test the revised model/instructions.
- [Current Cloudflare vision model and response contract](https://developers.cloudflare.com/workers-ai/models/qwen3.8-27b/) were checked rather than selecting a deprecated vision model from memory.

## Limits to preserve in release reporting

No free universal same-cut scene index was found. This implementation builds bounded, on-demand scene/character understanding from the active source and independent references; it does not claim every title is fully indexed. Cast metadata can contain mistakes or future aliases, captions can be a different edition, and models can misinterpret evidence. References and time ceilings reject invalid structured output but do not mathematically prove semantic entailment. Unknown extras or genuinely ambiguous speakers must stay uncertain. Never equate a passing build, a fluent reply or a matched keyword with a verified plot answer.

## Final-path correction

The explicit Gemini retry returned Generate Content HTTP 429 and a separate Live quota close for the tested request path. This was recorded as a request/project refusal, not a global Gemini outage. Testing then exposed redundant observer/inference calls and excessive repeated timestamp tokens. The final path removes both overheads and retries the same Gemini conversation with the retrieved sources and compact full earlier context. Revalidation is in progress; failed attempts are retained locally rather than overwritten as successes.

## Additional defects found by the genuine cases

- “escaping”/“comforting” were missing from temporal capture detection. After an immediate movie seek this could attach one still. These actions now trigger backfill of the actual preceding sequence.
- A completed typed answer could still have sending=true before the late turnComplete message; the next follow-up unnecessarily replaced its socket. The replacement condition now distinguishes completed output, with a captured-order replay checking that an older completion cannot finish a new question.
- A provider appended an LMDX UI sideband to a real answer. Display projection now suppresses complete and partially streamed LMDX tags while preserving ordinary comparison text.
- Duplicate object-form cue windows consumed substantial context. The compact projection now preserves all included window dialogue with short source timestamps. The full transcript stays local, and source-supported names/entities select relevant earlier passages instead of filling every Live turn with the entire raw prefix. Player output budget is bounded separately from catalogue conversations.
- Research-source cache ownership does not depend on which newly decoded images are present: refreshing temporal frames at the same source/revision/pause can reuse independent research without certifying a face binding.

The standard Gemini 3.8 Live model is retained. Google's current [Live thinking guidance](https://ai.google.dev/gemini-api/docs/live-api/thinking) says it must not receive thinkingConfig; the Extended Thinking model has a different interaction-status lifecycle and intermediate utterances. A rushed model switch would risk the interruption/continuity behavior this task is meant to protect.

## Cold-seek performance correction

The Disclosure Day reply now named Daniel/Jane and explained the Archive/artifact pursuit, but first text took 24.5 seconds. Two independent preparation paths decoded the same paused sequence, and tiny timestamp drift missed the millisecond-indexed cache. The player now tests the actual temporal journal even while its assembled scene cache is invalidated, reuses already-decoded images within 100 ms only when their real presentation time is at/before the current boundary, and requests three earlier temporal positions plus the actual pause capture. Dedicated tests cover pause drift, distant-cache rejection and a rewind's future-frame exclusion. Android host tests: 878 total, no failures/errors, three existing skips; final local lint and assembly pass.

## Final genuine recheck

The real toolbar button was tapped on the connected Pixel. Disclosure Day at 01:04:12 returned named characters, the theft, kidnapping and Wardex pursuit in 3,647 ms; the identity follow-up correctly identified Daniel as Jane's boyfriend in 2,993 ms without changing conversation. Get Out's recheck named Chris, Rose and Missy, explained the earlier hypnosis and preserved its unrevealed purpose, with first text in 1,498 ms. The episode case identified Eleanor/Chidi, the mistaken admission, his ethics job and the wallet-theft inference. Its follow-up exposed a routing defect: indirect questions beginning with "does that mean" could bypass the native scene contract and become generic catalogue commentary. Indirect interrogative follow-ups now retain the same evidence/answer contract and suppress irrelevant title-card work; the new regression also keeps direct playback commands fast. Final reruns follow.

Two controlled spoken-English attempts transcribed the question correctly but received a Gemini Live 1011 quota close before output. The other phone works inside Aliflix per the user; these results are specific request failures, not proof of a global service outage. No failed spoken attempt is counted as a success.

The fresh spoken retry succeeded: correct English transcription, Chris Washington/Rose Armitage/Missy identified, earlier hypnosis and the Sunken Place explained, 409,440 audio samples rendered and drained, English and the same conversation retained, no speaker-echo barge-in. First visible text was 16,853 ms from the start of the 7.59-second controlled utterance; answer generation completed at 19,038 ms. An expired provider session was recovered before the answer; this successful turn does not erase the earlier quota refusals.

The final episode follow-up now answers that Tahani's superiority is only assumed, not objective proof, without later-series commentary. Manual review also caught a false test rejection of the synonym "fraud"; the required dishonest-act check now accepts that equivalent word while retaining the same question and required plot points. Another genuine identity follow-up completed a scene lookup but returned only `<no speech>{pause}`. Such a placeholder is now hidden and receives one evidence-preserving continuation, without a new conversation or infinite retry; a captured-order device replay covers this exact failure.
