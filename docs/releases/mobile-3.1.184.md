# Aliflix mobile 3.1.184

## Ask and Talk

- Discover’s Ask action opens text conversation directly. The separate mobile Describe, Similar and Surprise Me entry points are retired. Movie or series intent comes from the conversation.
- Structured filters move to Discover beside All, Movies and Series. Applying or dismissing the sheet is transactional; dismissing does not change results. Filters retain their state when revisiting Discover.
- Chat requests twelve catalogue matches by default and supports explicitly requested collections up to twenty-four. Voice retains its small spoken selection. A shortage of genuine matches must be reported rather than padded.
- Similarity requests resolve actual anchor identities and use the existing Aliflix similarity research path. Selected titles carry their catalogue identity, including remakes and television anchors. Refinements preserve prior constraints, including recency and series status. Running and Ended are contextual chat actions.
- Each reply owns its result collection. Later replies and additional collections preserve earlier cards. Verified cards appear as soon as they are available, independently of the model's final prose. History retains larger collections and their identities, including ordinal follow-ups beyond the third result.
- Supported year, runtime and series-status refinements can filter the verified collection directly. A new premise or additional batch still requires research; the retained subset is not presented as an exhaustive new search.
- The response indicator uses the requested Twin Orbit animation with Aliflix colors and the label “Thinking..”. History/sync and paused banners and the redundant retry action are removed.
- Sources collapse into small publisher icons. Activating their shared touch target opens the source list and attribution. Only validated HTTPS publisher links are opened.
- Cards use a native adaptation of Kokonut UI’s Carousel Cards: image-led artwork, corner metadata, separate title/type labels and contextual focus depth. Voice cards have a bounded width even when only one is shown. Chat uses a compact responsive collection within the conversation's own scroll surface.
- Voice results retain a readable face, with independent size bounds. The body follows the actual discussed card's position, the eyes look toward it, and repeated mentions can return attention to an earlier card. These actions use conversation and playback context rather than random destinations.
- Filters use a fixed sheet with independently scrolling content. Result sorting appears after a filtered collection has loaded, in a separate control using the app's colors and typography.
- The face looks toward the visible card associated with current playback. Narration landmarks are driven by the loudspeaker’s playback position, rather than the last title in a transcript that may have arrived early. The Live API does not provide word timestamps, so attention timing is an estimate refined by the completed audio duration. Touch interaction remains responsive.
- During discovery, the model may contribute one brief observation specific to the request. It is newly composed, bounded to the current request and validated against its spoken transcript. It cannot replace the final researched answer. There is no rotating filler library.
- Pixel speaker-echo regression: the testing build had explicitly disabled the platform echo canceller and noise suppressor. The app now confirms control and successful activation of platform speech processing, with software AEC3 as fallback when platform echo cancellation cannot be enabled. AEC and NS loss reconnects the session instead of silently continuing with a broken processing path. The matched communication capture, playback and routing remain in place; speech thresholds are unchanged.

## Correctness and background work

- Recommendation ownership is fenced across account changes, delayed identity responses and token invalidation. A stale account response cannot publish private results or erase a newer credential.
- The personalized rail waits for an aligned account snapshot. Account switches clear active Ask work and editor state.
- Native download work preserves exact catalogue identity and season zero, rejects malformed episode numbers, adopts actual restored transfer state and removes obsolete preparation records after transfer deletion.
- Native settings reject non-finite values before mutation. Failed settings changes retain the available undo action.
- Background missions serialize foreground and scheduled commands and reject stale snapshot revisions. Cancelled jobs preserve cancellation ownership.
- Movie playback preserves absent episode metadata; TV specials retain season zero.
- Download capacity accounts for queued transfers against physical free space. Update cancellation remains effective during network checks and checksum verification.

## Design and implementation references

- [Progressive disclosure, Nielsen Norman Group](https://www.nngroup.com/articles/progressive-disclosure/): one primary conversation, advanced controls available at the point of use.
- [Android accessible API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults): grouped semantics and 48 dp interaction targets.
- [Material progress indicators](https://m2.material.io/go/design-progress-indicators/): a single indicator for each operation and no invented completion percentage.
- [Twin Orbit](https://www.loading-ui.com/docs/components/twin-orbit), [pinned source](https://github.com/turbostarter/loading-ui/blob/d78e09e09f9963ae87974593784a4948f612f80d/src/registry/components/loading-ui/twin-orbit.tsx): authored radius, easing and phase delay; MIT notice packaged in the app.
- [Kokonut UI Carousel Cards](https://github.com/kokonut-labs/kokonutui/blob/d33a196aa574553b6c49f947ed6e510bfe1c06c3/components/kokonutui/carousel-cards.tsx): native card adaptation; MIT notice packaged in the app. Hero Carousel, Focus Cards and Apple Cards Carousel were also evaluated before selection.
- [Gemini Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities): nonblocking tools and separation of tool completion from ongoing speech.
- [Android AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler), [WebRTC Android effects](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/audio/WebRtcAudioEffects.java): effect ownership, activation and platform AEC/NS handling.
- [Material sheet implementation](https://android.googlesource.com/platform/frameworks/support/+/e724ec44e1a07293eb7a9cd1d2b322341686d1cf/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ModalBottomSheet.kt): disabling the outer sheet gesture removes its competing nested-scroll connection while the internal list retains scrolling.

## Validation

- Android: 794 unit tests, 791 passed and three skipped, with zero failures or errors. Final mobile debug assembly, instrumentation assembly and lint passed.
- Backend: 281 tests across 31 files, TypeScript checks and deployment dry run passed. The deployed collection-refinement backend was exercised from the phone.
- Physical Pixel 7a: real chat returned twelve distinct Japanese animated films with the requested year range. A second request restricted them to 2020 onward, returned two qualifying films and preserved the original twelve-card message. Text chat captured zero microphone frames.
- Physical Pixel navigation: Ask opens text chat; the old mode chooser is absent; filters open fully; both animation genres can be selected; dismissing a draft leaves applied state unchanged; Apply remains reachable. Real up/down gestures leave the filter window stationary. Sorting is absent in the draft and available after results load.
- Physical Pixel visual test: one, two and three cards retain a readable orb within the viewport. Its attention moves between discussed titles and it docks in the chat header. Attention timing remains an estimate because the provider supplies no word timestamps. Device screenshots were visually reviewed.
- Pixel audio: the old testing build reproduced a false floor take. The corrected app defaults passed all eighteen multilingual, volume and short-reply segments with zero false activities, along with an independent actual-speaker sound check. Controlled near speech stopped actual output at 133 and 172 ms. The near fixture enters after platform processing, so these timing checks do not replace human speech validation through the physical microphone. No universal cross-device audio claim is made.

Signed artifact, public updater and production installation receipts are verified separately at publication. No TV update is included.
