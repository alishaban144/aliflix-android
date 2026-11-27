# Mobile 3.1.187

Player Talk now receives the paused video surface, a bounded recent frame sequence, selected timed captions and already-played audio. Seeking, source changes and soundtrack changes fence the evidence. Capture stays in memory; opening Talk submits only the recent scene. Microphone audio and film audio have separate ownership.

An independent observer receives no title, episode, cast or plot. Played-audio transcription uses the existing Whisper provider and rejects unsupported word timings. The observer returns a validated timeline with frame/dialogue references. A bounded second observation recovers from a stalled inference; losing requests are cancelled. Current-scene explanations use these observations without a web-retrieval detour and retain the pause timestamp as their spoiler ceiling. Missing evidence remains uncertain; universal accuracy and network availability are not guaranteed.

The player orb retains its halo and motion without an enclosing ring. Its separate palette provides scene explanation, dialogue explanation, measured exchange replay, microphone, typing, conversation expansion, stop response and task cancellation. Draft and cursor survive expansion. Submitted text opens the conversation so its reply is visible. Palette placement respects safe areas, the keyboard and both orientations; controls have 48dp targets.

Dedicated validation replaces the unchanged broad suites for this release: nine new native host cases, fifteen new Worker cases, physical Pixel 7a frame/seek isolation, actual replay, portrait/landscape controls and composer, and draft/visible-reply checks. Two independently grounded Dark S1E1 scenes passed at approximately 3:00 and 15:00, with first rendered text at 3.7s and 9.0s. Earlier failed observer/latency experiments were rejected. Additional final phone results are recorded in the release pull request. TV sources and version remain unchanged.

Implementation references:

- [Android PixelCopy](https://developer.android.com/reference/android/view/PixelCopy): copy only the native video surface.
- [Gemini client-content ordering](https://ai.google.dev/gemini-api/docs/live-api/capabilities) and [Live tools](https://ai.google.dev/gemini-api/docs/live-api/tools): ordered evidence updates and separate tool results.
- [Structured output](https://ai.google.dev/gemini-api/docs/structured-output): bounded, independently validated observations.
- [Groq speech-to-text](https://console.groq.com/docs/speech-to-text): multilingual PCM/WAV transcription with segment and word quality metadata.
- [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults): actual minimum touch targets.
- [NExT-GQA, CVPR 2024](https://openaccess.thecvf.com/content/CVPR2024/html/Xiao_Can_I_Trust_Your_Answer_Visually_Grounded_Video_Question_Answering_CVPR_2024_paper.html): grounding evidence separately from apparently plausible answers.

The Big Bang Theory S1E1 stairwell explanation was grounded and accurate, but its 15.3s first response exceeded the dedicated test's 12s latency target. This remains a latency limitation. Film frames, audio clips and private device traces are excluded from the repository and release assets.
