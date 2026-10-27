# Aliflix mobile 3.1.182

Android mobile, version code 272. This document describes the full release, including the reassessment after the first testing build. The earlier Pixel testing build and cancelled GitHub build are not the final release candidate.

## Talk audio and conversation

1. **Speech starts independently of microphone input.** A dedicated output pump starts, drains and checks AudioTrack every 20 ms. A blocked microphone read cannot hold playback until a second utterance. Short replies use the platform's minimum start threshold; older supported Android versions receive bounded silent tail padding.
2. **Assistant output instead of phone-call output.** Speech and audio focus use Android's assistant-response stream, and physical volume controls derive from the same audio attributes. Ordinary speaker/headphone playback follows Android's assistant routing without forcing call mode. Only an explicitly selected SCO/LE communication headset retains its microphone route. Talk does not raise system volume. This replaces the earlier candidate's call-stream speaker-selection approach.
3. **Hardware-progress recovery.** Written bytes are not treated as audible output. If the playback head stops with outstanding PCM, Talk retries playback/routing after 1.5 seconds, then uses bounded session recovery if progress still fails. Intentional interruption suppresses this recovery.
4. **Echo-aware interruption.** Neural voice detection is combined with recent output correlation, sustained near-end confirmation and a 1.2-second pre-roll. Speaker echo alone must not take the floor; genuine interruption stops the old answer and fences stale work.
5. **Natural spoken clause handling.** The new client protocol retains 500 ms before speech detection and tolerates a 1.5-second clause pause, protecting soft leading words such as “avoid.” Older clients retain their previous wire settings.
6. **Tool answers wait for speech.** Research and native-action results use non-interrupting delivery. Lookup completion waits for queued and currently written PCM as well as the speaking flag. A progress bridge cannot complete the real answer.
7. **Developed film discussion.** Discussion instructions call for concrete filmmaking observations and their effects. “And?”, “go on,” German and Arabic continuations retain the subject. Short recommendation presentations do not restrict later discussion to a script.
8. **Correct decisions and corrections.** Closed comparisons stay with the supplied choices and check real runtimes. Excluded alternatives no longer trigger an A/B decision loop. A new genre request replaces the old task, while an actual refinement retains its constraints.
9. **Consistent cards and speech.** Final recommendations commit exact catalogue identities and approved speech together. Same-title follow-ups preserve cards; new topics replace stale ones. Science-fiction bridge selection now handles hyphenated wording and negated comedy clauses.
10. **Player context.** Player > More > Talk pauses playback and supplies the actual title, episode and position. Returning to the same media can resume only after the spoken acknowledgement drains and audio focus is released.

## The persistent agent

The useful difference is that work is connected to Aliflix's actual state and continues after the conversation. It is not a claim that an LLM cannot reason about films or that Aliflix reproduces Dots or Muse. The Tasks icon opens retained work directly; concise Catch up, Plan nights and Track releases entries start real workflows and ask for missing constraints.

Examples of substantial composed outcomes:

| Request/problem | What the implementation does |
| --- | --- |
| “Get me caught up by Sunday, 75 minutes each day.” | Reads real released episodes and recorded completions, keeps episode order, includes breaks, and packs dated budgets. Reading refreshed app state replans affected episode/calendar stages when progress changes or a day is missed. |
| “Build a double bill that fits exactly into my evening.” | Resolves canonical film runtimes, film count and breaks; solves the budget instead of estimating from prose. An impossible plan requests adjustment. |
| “Programme several nights for these competing tastes.” | Composes separate discovery branches, catalogue identity intersection and dated festival packing; preserves hard constraints and avoids repeated films. |
| “Investigate this half-remembered film.” | Retains a research/discovery task, public evidence and canonical candidates; the same task can be refined without restarting all completed work. This is not a new independent fact-checking model. |
| “Build a route through this filmmaker.” | Uses the actual creator identity and chronological credits, with explicit pagination and truncation. |
| “Work through this franchise.” | Traverses verified collection membership rather than inventing sequels from title similarity. |
| “Keep this collection growing.” | Explicit recurrence appends newly qualifying canonical titles without reshuffling existing membership. |
| “Watch for this release.” | Explicit recurring checks admit real dated catalogue releases and notify changed output. Catalogue release is distinct from provider availability. |
| “Track dated news about this project.” | Retains public evidence and checks for changed research on an explicitly requested schedule. Unchanged output does not repeatedly notify. |
| “Compare two separately researched routes.” | Dependency stages can combine/intersect verified discovery branches before assembling a programme. An empty bounded intersection is not proof that no film exists anywhere. |
| “Change the deadline, keep the research.” | Revision-aware updates retain unaffected outputs and invalidate dependent work. Old revisions and late cancelled work cannot overwrite the new goal. |
| “Refresh this finished investigation.” | Refresh actually reruns completed stages against current facts; Resume continues checkpoints. Adjust handles an infeasible task without silently relaxing it. |
| “Remember this preference for future calls.” | Explicit memory survives calls and identity-token renewal, is isolated by owner, and supports exact-key forget. No inferred permanent memory is written. |
| “Put the finished selection into my list, then undo that.” | Native batch edits validate all exact identities first, apply idempotently and guard whole-batch undo against intervening manual changes. A research task alone does not authorize library edits. |
| “Keep working while I leave Talk.” | Durable backend stages continue without the microphone. A periodic Android network job retrieves changed results; Android controls its scheduling. Pause and Cancel remain available. |

Additional correctness work: stable guest ownership across token renewal; canonical account ownership despite changing session nonces; local-calendar dates across time zones/DST; dependency-derived TV identities; duplicate episode removal; incomplete inventories and unknown runtimes block a complete-plan claim; changed calendar signatures trigger delivery; bounded summaries preserve full final outputs; More expands episode results as well as title results.

Limits are explicit: 16 active tasks per owner, ten stages per task, at most 14 festival nights and 90 catch-up days, bounded catalogue inventories and collections, and a minimum six-hour explicit monitoring interval. External apps, automatic background microphone use, new AI providers, unverified download/provider availability and arbitrary streaming URLs are outside this release.

## Orb and discovery

11. **One continuous face.** Voice, results and chat share one retained orb. Position, size and velocity persist across retargeting rather than mounting separate faces. Context and available screen space determine its measured destination; keyboard and panels reclaim space.
12. **Researched motion implementation.** The spatial motion adapts the existing MIT-licensed Lumaform Regard engine: minimum-jerk attention, critically damped body-follow dynamics. The current Aliflix face is retained. Attention adapts to listening, speaking and thinking; pointer pursuit, signal-driven lift and size changes retain velocity through transitions. A bounded amplitude timeline follows rendered syllables rather than the last queued audio chunk, independently of microphone reads. Attribution ships in mobile assets. Reduced-motion settings settle directly; drawing size respects the viewport.
13. **Actual personalized For you.** Ask receives Aliflix's enriched MobileTasteModel/TastePicks output from favourites/history. The unrelated rotating daily pool is removed. No personal-match explanation is displayed.
14. **Stable Describe results.** Published cards remain an append-only prefix during streaming and continuation. Signed owner-bound pages exclude previous identities, have immutable retries and expire explicitly rather than silently regenerating the visible list.
15. **Effective refinements.** Date, recency and runtime constraints are enforced against catalogue facts, including German/Arabic recent wording, numeric titles and negated year expressions. The latest refinement replaces its own dimension while retaining the original premise and exclusions.
16. **Stricter discovery quality.** Uncurated web-title fallback cannot bypass joint constraints when the curator is unavailable. Fewer supported matches replace unrelated padding. Curator/resolution deadlines were adjusted after actual difficult-query failures.
17. **One loading state.** The requested large centered Uiverse path/dot loader replaces duplicate body/composer animations. For you stays hidden after a search starts, including errors and empty results. More and near-bottom pagination call the real continuation path.

## Evidence

See [research, validation and coverage boundaries](mobile-182-validation.md). Signed-artifact and public-updater verification is recorded separately after the final GitHub build. Phone validation preserves existing data and uses a debug signer; it does not establish a production-signer upgrade installation. TV source, version and release are unchanged.


Orb attention update: large movements now use deterministic conversational destinations, not random positional wandering. While speaking, the most recently named visible film selects the corresponding side; thinking shrinks and lifts the face out of reading space; listening recenters toward the viewer. Screen mode, keyboard and panels still provide measured safe destinations. Regard's minimum-jerk attention and retained spring dynamics connect these targets continuously. Pointer pursuit and rendered-syllable response provide restrained detail without arbitrary routes.
