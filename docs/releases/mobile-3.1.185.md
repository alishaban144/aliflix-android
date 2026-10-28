# Aliflix mobile 3.1.185

- Each human turn refreshes the model's context from the app's actual conversation and catalogue identities. Interrupted narration can still establish real shown choices. Focus and ordinal follow-ups retain the original ordered shortlist across reconnects and history restoration.
- Voice supports one to eight genuine choices. The default remains three; larger requested selections are no longer truncated by the model schema, backend count policy, presentation validation, catalogue controls or renderer. Text chat retains its larger collections.
- A newly composed contextual bridge is part of each voice research call. Its continuing function response can speak while the same lookup runs, without another model or a canned phrase list. The final result waits for the bridge to drain. The spoken transcript is validated before playback, and interruptions retain ownership of cancellation.
- Visual topic controls cannot replace or dismiss cards. Same-subject continuations keep their cards. Choosing one focuses its exact identity while retaining the original set for restoration and ordinal references.
- The card stage uses centred rows, retained catalogue keys, coordinated exits and continuous placement/size changes. Outgoing cards fade and leave toward the nearest edge; the selected poster moves into focus and grows. The same poster instance survives the transition. Reduced motion snaps to the resulting layout.
- Up to eight cards share the voice page with a readable orb and controls. Larger type and smaller viewports use a bounded scrolling card stage, which brings the currently discussed card into view. Orb attention uses the cards' actual measured positions.

The Pixel-approved echo cancellation, noise suppression, microphone thresholds, native audio processing and routing settings are retained. No TV update is included.

Validation: 815 Android unit tests, 812 passed and three skipped, with no failures or errors. Mobile assembly, instrumentation assembly and lint passed. TV assembly remained successful without a TV release. The backend passed 285 tests across 32 files, TypeScript checks and a deployment dry run.

On the physical Pixel 7a, the new complete journey returned five real films with delivered model-composed speech during research, chose a single existing title, continued its discussion, reconnected, recalled the original fourth film, restored all five cards and chose again in German. The latest complete run passed in 130.005 seconds. These service/speaker checks used typed requests with capture muted to isolate conversational state from environmental speech; they are not new physical-microphone echo tests.

The separate whole-screen check passed with eight cards, five cards, a single enlarged focus and restoration. Full card bounds, orb bounds, controls and card overlap were checked. Screenshots and recorded transition frames were reviewed. This review caught a clipped metadata line missed by the earlier clipped-bounds assertion; explicit caption line heights, sufficient row height and unclipped measurements corrected it. A hundred generated choice histories exercise native state invariants; these are state tests, not a claim of testing a hundred human users.

Signed artifact, native/model parity, public updater and production installation verification are recorded separately with publication.

Implementation references: [Live nonblocking tools](https://ai.google.dev/gemini-api/docs/live-api/tools), [continuing function responses](https://googleapis.github.io/js-genai/release_docs/classes/types.FunctionResponse.html), [Live session management](https://ai.google.dev/gemini-api/docs/live-api/session-management), [Compose animation and retained exit content](https://developer.android.com/develop/ui/compose/animation/quick-guide). Card artwork/metadata styling retains the licensed Kokonut adaptation from the previous release.
