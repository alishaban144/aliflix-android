# Mobile player Talk validation

## Evidence and behavior

- Use the exact decoded pause for spatial references. Track the media request and pause through seeks, source changes, and reconnections. Earlier frames and dialogue can explain an event, but only the current frame supplies gaze coordinates.
- Identify people by visible features and scene context, then verify cast attribution separately. For motion or causality, require earlier or adjacent video evidence or dialogue that establishes the cause. For uncertain or out-of-frame references, clarify or state the missing evidence and keep the orb home.
- Tie each gaze shift to a distinct referent in the spoken answer. Let the eyes lead, start the body glide 150 ms later, and scale its travel time with distance. Arc around subtitles and the subject where space allows, settle beside the subject, and hold there until the next cue or the end of the reply. Respect reduced motion.
- Preserve the loading dashes, open-and-double-blink sequence, early microphone capture, and tap-only transcript. Interruptions, playback resume, and seek must stop stale motion. Keep additional UI copy out of the player.

## Phone rounds

| Round | Movies | Series episodes |
| --- | --- | --- |
| Baseline | Arrival, The Martian, Knives Out | Severance S1E1, Breaking Bad S1E1, The Office (US) S1E1 |
| Revised | Interstellar, The Matrix, The Truman Show | Better Call Saul S1E1, Stranger Things S1E1, Mr. Robot S1E1 |
| Transport reuse | Jurassic Park, The Dark Knight, Back to the Future | Lost S1E1, Sherlock S1E1, The Last of Us S1E1 |
| Visual detail | Blade Runner 2049, The Grand Budapest Hotel, The Devil Wears Prada | Chernobyl S1E1, The Bear S1E1, House M.D. S1E1 |
| Slower scene motion | Ocean’s Eleven, The Social Network, Whiplash | Parks and Recreation S1E1, Succession S1E1, Brooklyn Nine-Nine S1E1 |

Choose an exact playable pause on the Pixel and inspect the decoded frame and earlier evidence before writing each question. Replace an unplayable title with another unused title of the same type. Cover cast identity, small props or readable text, multi-target comparisons, action and cause, an earlier callback, and an absent or off-screen premise. Use new questions and titles for every round.

For each case, record the decoded frame, the expected answer and target order, recognized speech, first audible response after speech ends, and gaze coordinates with playback positions. Review screen captures for placement and expression. Count a wrong target, skipped required target, incorrect attribution, unsupported cause, or spoiler as a failure even if a reply is delivered. Use repeated opens to measure tap-to-microphone and tap-to-connection separately. Verify transcript reveal and collapse, interruption, seek, portrait and landscape, and reduced motion in the same build.

Fix observed failures between rounds and carry deterministic regression checks forward. The revised build must show the improvement on unfamiliar scenes as well as passing the regression checks. Hold the public mobile release until the phone rounds and signed draft checks are complete. End testing before publication.

Measure cold, prepared, and immediate-reopen transport readiness separately from microphone readiness. A prepared connection may complete setup during foreground playback while the player controls are available. It sends no microphone audio or frames before Talk opens, expires quickly, and must match the current playback request, account, voice, and language. Verify an idle close releases the audio lease, that a quick reopen reuses the provider session, and that a cold or expired open can still connect.

## Research

- Referential gaze can direct attention ahead of a spoken reference. The gaze must point to the intended object, especially when several objects are plausible. Use the phone's audible playback clock to evaluate lead and dwell. [Staudte and Crocker](https://www.amlap.org/~crocker/documents/staudte-hri09.pdf), [Andrist, Gleicher, and Mutlu](https://graphics.cs.wisc.edu/Papers/2017/AGM17/chi17-andrist-CAMERA.pdf)
- Use scene-specific referents in spoken order and validate bounding boxes against the actual image. Live transcripts can arrive independently of audio, so record both and assess synchronization from playout. [SemanticScanpath](https://arxiv.org/html/2503.16548v1), [Gemini image understanding](https://ai.google.dev/gemini-api/docs/image-understanding), [Live API reference](https://ai.google.dev/api/live)
- Ask long-context questions that require finding a specific earlier event. Use retrieved video and dialogue through the current viewing boundary to answer them. [LongVideoBench](https://longvideobench.github.io/)
- Measure position and animation in the rendering path, including at different aspect ratios. [Android animation performance guidance](https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/animation)
- Use smooth interpolation along a short, bounded curve and let gaze lead the body. Review turns to nearby and distant targets, and check the pause at each target while the reply continues. [Compose animation customization](https://developer.android.com/develop/ui/compose/animation/customize), [Staudte and Crocker](https://www.amlap.org/~masta/COGNIT_StaudteCrocker_2011.pdf)
- Use a higher-resolution exact still for small props and text. Check the box center against the actual referent in the frame, even when the spoken answer gets its name right. [Gemini visual resolution](https://ai.google.dev/gemini-api/docs/media-resolution)
