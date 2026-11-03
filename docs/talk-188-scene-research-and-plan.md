# Player Talk: scene understanding and earlier-story recall

Research and repository inspection: 3 November 2026. Validation results are recorded separately in `talk-188-validation.md`.

## Purpose and behavior contract

Player Talk is a companion that understands the film at the viewer's current position. It should answer the question the viewer actually asks, connect current actions to earlier causes, and retain that ability after a seek. A fluent description of the paused image is insufficient.

The default knowledge boundary is **everything in this exact film or episode from its beginning through the current playhead**. Seeking forward does not prohibit earlier context. Seeking backward reduces the answer boundary again, even if the session has already inspected later material. A flashback's presentation position, rather than the fictional calendar date of its events, controls disclosure. Other episodes need their own verified viewing boundaries.

An English question gets an English answer, including after German audio, Arabic subtitles, an earlier answer in another language, or reconnect. Quoted dialogue and internal observation text do not choose the language.

For “Why did she fall down?”, Talk should resolve which person and action the question refers to, inspect the surrounding action sequence, retrieve any earlier setup needed to explain it, and answer the causal question directly. If she was pushed, identify that supported action; if the footage only establishes that she loses balance, do not invent a diagnosis. If the premise is false, correct it. Explain facts first, then clearly distinguish an interpretation when helpful.

“What happened immediately before this?” should produce the preceding action/exchange and its connection to the present moment. “How did he know about the letter?” may require the opening scene even at the midpoint. “Recap from the beginning” needs a coherent causal timeline. These are retrieval tasks, not permission to invent remembered plot.

No available model or public API guarantees every answer about every title. The engineering goal is broad evidence coverage, successful retrieval, accurate causal reasoning and measurable failure handling. Deleting an uncertainty sentence without supplying the missing evidence would hide the defect.

## Current release and implementation findings

- Authenticated GitHub Releases inspection, including drafts: latest `v3.1.187`, published 31 October 2026, commit `5dfa32fc5c2a6f4b25340646e89c60f54fa9d9fb`; no drafts returned. PR #12 was still open at inspection. Release publication and branch merge are distinct states.
- The connected Pixel 7a has production `3.1.187`, version code 277. ADB access requires execution outside this session's sandbox; the phone itself is connected and authorized.
- The three upper shortcuts (scene explanation, dialogue explanation and replay) are already absent from the latest release source. The remaining palette provides microphone/connection, typing, expansion and close. Device verification should cover this exact state before making another UI change.
- `PlayerSceneMemory` captures a recent video window and stores a visual journal. `TalkEpisodeMemory` groups timed captions into approximate dialogue windows. These are not verified cinematic scene boundaries.
- `NativePlaybackService.episodeCaptionCues()` returns only external caption cues; embedded captions return an empty list. Disabling subtitles can clear external cues. Consequently “full episode recall” can have no full transcript.
- Named earlier-event search ranks literal text matches. A causal paraphrase or English question over German dialogue can miss the relevant beginning. The previous implementation sent selected windows, not the chronological earlier story.
- Earlier visual journal entries were represented by timestamps in recall results, without their actual images. A timestamp cannot substantiate a visual claim.
- After a forward seek, surface capture knows the new still and recently played audio; it does not automatically know the skipped action sequence. This requires a separate decoder or an indexed video source.
- The independent observer deliberately excludes title/cast/plot. That remains useful against plausible plot hallucination, but its current-scene observations must be combined with separately supported earlier narrative evidence.

## Resources and what each can actually provide

| Resource | Useful evidence | Limit and recommended role |
|---|---|---|
| Exact stream / local media, independently decoded | Physical actions, silent events, props, locations and their timing | Primary visual evidence. Preserve provider stream adapters, credentials, cut identity and actual returned frame timestamps. Never move the viewer's player to retrieve evidence. |
| Existing source subtitles and SubDL integration | Timed dialogue available from the beginning; searchable even with subtitles hidden | Best immediately available narrative layer. Match title/season/episode, cut, language and timing; a subtitle does not describe all silent action or reliably identify every speaker. [Existing API resource](https://subdl.com/api-doc). |
| OpenSubtitles REST API | Alternative timed files with canonical IDs and movie hashes | Potential fallback, not currently an installed Aliflix backend. Requires API/account configuration and download limits. Prefer exact IDs and hashes over title-only search. [Official API](https://ai.opensubtitles.com/api-docs), [official migration notice](https://forum.opensubtitles.com/t/opensubtitles-org-api-final-shutdown-notice-for-non-vip-users/5045). |
| Existing Groq Whisper transcription | Speech from an actual supplied audio window | Useful when captions are unavailable or wrong. Transcribe film audio independently of microphone input. It cannot recover unprovided earlier audio or explain silent physical causes. [Official speech-to-text docs](https://console.groq.com/docs/speech-to-text). |
| Gemini video understanding | Combined video/audio reasoning, timestamp questions, configurable temporal inspection | Appropriate for clips and, where available, indexed/full-video reasoning. The video must actually be supplied. More frames and long context are not guarantees. Use bounded pre-playhead evidence and validate deployment/model support. [Video docs](https://ai.google.dev/gemini-api/docs/video-understanding), [long context limitations](https://ai.google.dev/gemini-api/docs/long-context). |
| TwelveLabs | Multimodal and semantic search returning relevant video intervals | Strong candidate for a reusable catalog scene index. It requires uploading and indexing the actual videos first; it is not an existing database of every commercial episode. Account, ingestion, media access and cost are additional work. [Search](https://docs.twelvelabs.io/docs/guides/search), [indexing workflow](https://docs.twelvelabs.io/docs/get-started/quickstart/search), [timed transcription](https://docs.twelvelabs.io/api-reference/videos/retrieve). |
| Licensed scripts, audio description and verified episode recaps | Speaker identities, actions omitted from subtitles, narrative links | Supplement exact media. Align descriptions to the actual cut before use; untimed recaps cannot safely locate a random playhead and often contain future revelations. Availability and reuse terms vary by title. |
| TMDB and web research | Canonical title, episode and edition identity; attributable outside facts | Supporting metadata and carefully scoped clarification. An overview is not a scene transcript. Do not import a full ending recap into a partially watched scene answer. [TMDB search/detail workflow](https://developer.themoviedb.org/docs/search-and-query-for-details). |
| Audio-description tracks | Narrated silent actions, visible text and scene changes | Inspect the exact edition for Media3's `ROLE_FLAG_DESCRIBES_VIDEO`, and transcribe with a separate decoder. Do not switch the viewer's soundtrack. Availability is title/provider dependent. [W3C](https://www.w3.org/WAI/WCAG21/Understanding/audio-description-prerecorded), [Media3 role flags](https://developer.android.com/reference/androidx/media3/common/C.RoleFlags). |
| Gemini Embedding 2 + Cloudflare Vectorize | Cross-language semantic scene retrieval in the existing stack | Recommended indexing layer. Use `gemini-embedding-2`, separate per-scene vectors, model-2 text task prefixes, and exact edition/playhead metadata filters before top-K. Re-embed older vectors. [Gemini API contract](https://aistudio.google.com/docs/embeddings), [Vectorize filtering](https://developers.cloudflare.com/vectorize/reference/metadata-filtering/). |
| VideoDB, Azure Video Indexer, AWS Bedrock Data Automation | Managed visual/transcript indexing, timed scene/shot/chapter insights | Alternatives for advance ingestion, not ready-made commercial film catalogs or a cold per-question operation. Azure explicitly describes prompt-generation jobs taking minutes. [VideoDB](https://docs.videodb.io/pages/core-concepts/indexes-and-search), [Azure insights](https://learn.microsoft.com/en-us/azure/azure-video-indexer/insights-overview), [Azure prompt jobs](https://learn.microsoft.com/en-us/azure/azure-video-indexer/prompt-overview), [AWS video output](https://docs.aws.amazon.com/bedrock/latest/userguide/bda-ouput-video.html). |

Native implementation resource: [Media3 frame extraction](https://developer.android.com/media/media3/inspector/extract-frames), [FrameExtractor builder and source factory](https://developer.android.com/reference/androidx/media3/inspector/frame/FrameExtractor.Builder). Media3 1.11's separate `media3-inspector-frame` artifact provides the actual API used here; the existing `inspector` dependency alone does not contain it.

Research benchmarks support combining temporal retrieval, narrative memory and visual evidence rather than judging fluent prose alone: [MovieQA](https://arxiv.org/abs/1512.02902), [MovieNet](https://arxiv.org/abs/2007.10937), [MovieChat](https://arxiv.org/abs/2307.16449), [LongVideoBench](https://arxiv.org/abs/2407.15754), [LVBench](https://arxiv.org/abs/2406.08035), and [temporally grounded NExT-GQA](https://openaccess.thecvf.com/content/CVPR2024/html/Xiao_Can_I_Trust_Your_Answer_Visually_Grounded_Video_Question_Answering_CVPR_2024_paper.html). These are research/evaluation resources, not comprehensive production catalogs.

## Implementation sequence

### Embedding model decision: Gemini Embedding 2

The user's requested model is the stable `gemini-embedding-2`. The shared Worker embedding provider, deployment config, test config and generated bindings are updated in this branch. The REST batch has one request per independent document, `embedContentConfig.outputDimensionality: 768`, and text query/document prefixes. It does not send the old `taskType` field, which Embedding 2 does not support. Malformed or wrong-dimensional results are rejected. Current recommendation search computes both sides of a comparison afresh; no persistent old-model vectors are reused there.

For the proposed persistent scene index, use a new model/dimension/schema namespace and re-embed every scene. Same-dimensional Embedding 001 and Embedding 2 vectors are incompatible. Store separate vectors for dialogue, audio description and visual action. Model-2 video embeddings do not process the video's audio track, so video alone cannot replace a dialogue/audio index. The current native Talk improvements supply chronological transcript and actual frames; they do not yet constitute a deployed semantic scene index.

### 1. Restore available evidence independently of UI state

Load an exact-title/episode timed edition for Talk without enabling subtitles or replacing the viewer's chosen track. Prefer the currently corrected timed edition when available. Otherwise retrieve source/backup captions and label their provenance and alignment uncertainty. Keep data in a source-scoped memory cache; cancel or discard a result when playback identity changes. Reuse a successful fetch rather than downloading on every question.

Supply chronological dialogue from the opening through the playhead alongside focused retrieval windows. This permits paraphrase and cross-language reasoning, including links to early scenes that do not repeat the question's words. Bound the payload, explicitly expose incomplete coverage, and allow timestamp retrieval of omitted windows. A long transcript must not silently become “complete knowledge.”

### 2. Inspect actual temporal footage

Use a separate read-only decoder for requested earlier timestamps and missing current-sequence frames after a seek. Keep the main player paused, source/audio/subtitle choices intact and playback position unchanged. Apply the same HLS/provider transforms and scoped request headers as normal playback. Accept actual decoded timestamps only at or before the anchor; discard frames after it. Retain a bounded journal and send relevant images with their own timestamps, not just a list of times.

Increase recent-frame temporal detail within a bounded request. A few sparse frames can still miss a brief fall, touch or gesture. The complete solution requires question-adaptive clip retrieval, including denser sampling around a located event and film-audio context where needed.

### 3. Separate observation, retrieval and explanation

Keep independent current-action observation to avoid title-based invented events. Retrieve earlier story separately, then combine both for the answer. The observer's uncertainty about earlier causes is not proof that those causes cannot be retrieved. Supported earlier context may explain a motive; unsupported remembered plot may not.

For insufficient evidence, attempt the relevant native retrieval before declaring a specific detail unavailable. Provide the supported answer and identify only the unresolved point. Neither a broad apology nor an invented causal story meets the contract.

### 4. Build a reusable multimodal scene index for broad coverage

Key an index by canonical identity **and actual media edition**. Store presentation-time scene/shot boundaries, timed dialogue with speaker evidence, audio descriptions, visual action descriptions, character links and evidence references. Maintain both fine event windows and coarser scene/act summaries. Preserve provenance and confidence for each claim.

Retrieve by semantic question, entities and temporal relation, not literal keywords alone. For a “why” question, retrieve the action plus candidate earlier causes; for “before this”, retrieve its adjacent preceding scene; for an opening reference, search the whole prefix. Verify candidate evidence by reading the relevant actual clip. A configurable ranking system should combine transcript search, visual embeddings and narrative links.

Filter evidence and summaries at the playhead **before** generation. A full-film summary stored in a cache is unsafe if it already reveals future connections. Reject later evidence, future-derived character identities and ending-dependent interpretations. Cache by edition, boundary and retrieval version, not title alone.

The preferred existing-stack implementation uses Gemini Embedding 2 and Vectorize. Create metadata indexes for `editionKey` and numeric `endSeconds`; apply `editionKey == currentEdition` and `endSeconds <= anchor` before top-K, and validate every returned interval again. Keep exact timestamp lookup and adjacent scene retrieval alongside semantic search. Store cause/effect links, character identity provenance, presentation-time disclosure position and evidence completeness. [MovieGraphs](https://arxiv.org/abs/1712.06761) provides a useful research schema for interactions, relationships and reasons behind actions.

The inspected resources do not document a comprehensive preindexed, exact-cut commercial film/episode scene API. Tools such as TwelveLabs, VideoDB, Azure and AWS require supplying and indexing the media. An audio-description availability directory is not a scene transcript service. This distinguishes a viable retrieval architecture from a promise that an external API already knows every scene.

The native transcript/journal work is an immediate improvement, not a substitute for this catalog indexing layer. It cannot guarantee arbitrary silent-event retrieval from every unindexed hour of a film.

### 5. Maintain language and conversation ownership

Derive response language from the latest viewer question and attach it at the turn boundary and to retrieval results. Retain uncertain short tokens in the established language, while a clear new-language question changes it. Reconnection and compression must retain this state. Keep film audio, quoted dialogue and internal English observations separate from the viewer's language.

Keep the existing interruption, microphone ownership and echo configuration. Typed tests with microphone capture muted verify text turns; they are not acoustic microphone/echo validation.

### 6. Keep narrative questions on a short path

Prepare transcripts as soon as a source becomes playable. Reuse source-scoped transcript and decoded-frame caches across questions. Earlier-dialogue, recap and timestamp questions must not wait for an independent observer of an unrelated current still. Retrieve older footage only where needed; avoid running full-video exploration on each turn. Measure warm narrative, warm visual and cold seek separately. Use offline/asynchronous batching for advance catalog ingestion, never on the interactive question path.

The native client negotiates the existing v14 backend when v15 is unavailable, including its four-frame observer request limit. This permits testing native story retrieval against the published service without publishing the new Worker. The v15 causal instructions and Embedding 2 provider/config changes still require a backend deployment. After explicit user approval, the tested Worker was deployed as version `a435b366-275d-4969-93fd-fc9d682522c9`. The live configuration uses `gemini-embedding-2`. The native client continues to support the older protocol for compatibility.

## Acceptance matrix

Use genuine questions without “answer in English” hints. Record exact title/edition, pause position, requested question, supplied evidence, answer, first-response time, completion time, tools and language. Store media/traces locally outside release assets.

| Case | Example question | Required evidence / outcome |
|---|---|---|
| Current causal action | “Why did she fall down?” | Actual preceding action sequence; correct cause or precise remaining uncertainty, no static-frame substitute |
| Earlier cause at midpoint | “How did he know about the letter?” | Relevant opening/earlier dialogue and current reference, even after a direct seek |
| Previous scene | “What happened immediately before this scene?” | Actual prior exchange/action and its connection; no generic missing-details refusal |
| Exact earlier moment | “What happened at 6:20?” | Timestamp-aligned evidence from this edition, including skipped material |
| Recap | “How did we get here from the beginning?” | Chronological supported causes through the pause; no ending/future information |
| Silent scene | “What made her stop?” | Visual sequence; subtitles being empty is not a sufficient answer |
| Incorrect premise | “Why did he hit her?” when he did not | Correct the premise from evidence; no invented explanation |
| Language | English questions over German audio after a German turn | English answer without an explicit language prompt |
| Hidden/embedded subtitles | Same earlier-context questions | Independent transcript retrieval; viewer's display selection stays unchanged |
| Rewind/source change | Repeat after rewind, new cut or next episode | No stale/future evidence; correct new identity and boundary |
| Playback isolation | Retrieve skipped earlier frames while paused | Actual older frames, unchanged viewer position and paused state |
| Unavailable provider | Same questions during a controlled fetch failure | Useful available evidence, a specific gap, no invented success |

Review event identity, causal correctness, evidence support, prior-context relevance, spoiler compliance and answer language separately. Keyword matches and a passing transport test do not prove an explanation is correct. Human inspection of the actual sequence is required for causal acceptance. Measure cold seek, warm follow-up and transcript-missing latency separately; reject generic refusals even when fast. Set deployment latency targets from measured device results rather than hiding a long wait in a generous timeout.

## Immediate work and remaining scope

The branch adds independent transcript retrieval, chronological story context, earlier-image delivery, bounded independent frame extraction, denser current-frame selection, turn-language updates and versioned causal instructions. Targeted tests exercise spoiler fencing, paraphrase/cross-language evidence inclusion and decoder isolation. Actual device results determine release acceptance. Large initial question contexts retain recent dialogue and matching earlier windows, with duplicate scene text removed and full-prefix retrieval still available. A sufficient captured sequence is reused rather than decoded again. The observer prefers structured inference, with a delayed Live hedge; a successful observation needs only two corroborating current frames in the conversational model. Reconnect continuation restores native evidence before replaying the question.

Catalog-wide licensed ingestion, semantic scene indexing, dense event-adaptive video/audio retrieval, definitive cinematic boundaries and a broad human-scored film/episode benchmark remain separate engineering work. They are required before making a claim approaching “knows every scene.”
