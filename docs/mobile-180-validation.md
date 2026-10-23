# Mobile 3.1.180: grounded Talk conversations

## Selection and delivery

Talk previously allowed three different representations of a recommendation: the voice model's tentative candidates, a server-truncated list of matched cards, and unvalidated streamed speech. Its completion check accepted an answer mentioning any one returned title. Non-blocking research also allowed speculative suggestions to arrive before the lookup finished. A live Pixel run reproduced both the premature suggestions and a later corrected list.

The new mobile protocol commits a single presentation by catalogue key. Gemini Live supplies a private shortlist; one Tavily search and parallel TMDB matching return the candidate pool and bounded Tavily excerpts. `present_recommendations` chooses only returned `movie:id`/`tv:id` keys, in the desired order, with short reasons. The client builds both the cards and approved narration from those exact identities. A reordered shortlist therefore cannot attach the first candidate's poster to a different spoken choice. Unknown keys, duplicates and incorrect choice counts are rejected. Explicit one/two-choice requests are supported; three is the default.

During research, only one approved contextual bridge can be delivered. Speculative speech and transcript text remain withheld. The final short presentation is checked against its approved text before playback; ordinary discussion continues to stream. Corrections are bounded, interruption clears pending audio, and a provider failure retains usable cards rather than playing contradictory recommendations. Years remain in card/context metadata and are spoken when needed to distinguish same-name candidates. No Clef or extra recommendation-verification stage was added. Talk uses its existing Gemini Live conversation for selection; it does not open another generation session for that shortlist.

## Identity, artwork and conversation

Canonical ID, media type, original title, year and artwork survive selection, pronouns, ordinals, comparisons, restored groups, history and reconnection. Native controls and knowledge requests reuse these identities. A repeated Annihilation reference retains the established film, rather than asking which edition again. Explicitly requested alternative years can still select another edition. For a new same-title/same-year lookup, a strongly dominant feature film is preferred over a barely rated short; genuinely unresolved alternatives remain available for clarification before mutations.

Artwork accepts safe TMDB paths and official TMDB image URLs. Missing metadata and CDN failures recover by exact ID, with bounded image retries and observable loading/success states. Optional TMDB poster-language fallback repairs missing artwork at the identity boundary. Cards use portrait artwork, preserve the orb/topic, and retain the focus/dismiss motion from the previous mobile release. The live acceptance check waits for actual loaded Compose images, then verifies the canonical media identity for each selected title.

The capable-client operating prompt replaces accumulated contradictory instructions. It covers general conversation, stable language, spoiler boundaries, current app capabilities, identity reuse and the joint presentation contract. Card actions update the UI quietly: Talk must not announce that a card is now shown, displayed, visible or on the user's screen. Follow-ups continue the substance of the discussion.

Read-only title state exposes actual saved/favorite/disliked flags. Conversation undo restores the last Talk library change, checks for intervening changes, and resets with a new chat/account. Existing native playback, downloads, lists, settings, search and navigation remain connected to real app actions. Unanswered requests resume after a transport reconnect. Removing an unfinished per-turn context injection also eliminated avoidable delays observed during card follow-ups.

## Result quality

The real journey exposed an additional issue that a title-agreement test would miss: a natural sentence such as "Recommend three thoughtful science fiction movies" did not activate the old prefix-only genre check. Genre enforcement now recognizes this phrasing, including exclusions; unrelated fantasy/musical fallback results cannot pass a science-fiction request. Search remains across eras unless recency is requested. Relative mood refinements retain useful context while an explicit new request replaces an older episode task. The result-cache generation is advanced to discard the earlier fallback pools.

## Validation and evidence boundaries

Focused unit checks cover reordered/non-first selections and their poster paths, unknown/duplicate keys, requested counts, strict narration, unsafe pre-search additions, same-name/year identity collisions, original titles, safe artwork formats, group restoration and native undo. The older acceptance check now requires every selected title instead of any one title.

The opt-in Pixel journey exercises greeting, researched science-fiction choices, actual loaded posters and genre/identity checks, third-choice focus, pronoun follow-up, restoring the group, Annihilation (2018) details and cards, chat, real saved-state lookup, a library change and undo. It rejects needless screen-visibility announcements and restores the original library state even if a check fails. Physical UI/audio, host tests, CI tests and production artifact/signature verification are reported separately in the release evidence. Owned input/typed prompts through the real live session do not establish all human microphone/accent conditions or a production-signer upgrade.

The release remains Android mobile only. TV code/version/release assets are unchanged; TV compilation is a shared-code regression check.

## Implementation references

- [Gemini Live function responses and asynchronous tools](https://ai.google.dev/gemini-api/docs/live-api/tools)
- [TMDB image URL construction](https://developer.themoviedb.org/docs/image-basics)
- [Coil Compose image loading states](https://coil-kt.github.io/coil/compose/)
- [Cloudflare Durable Objects coordination rules](https://developers.cloudflare.com/durable-objects/best-practices/rules-of-durable-objects/)
