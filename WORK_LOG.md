# v3.1.177 usability and reliability

Baseline: published v3.1.176, 5aaa915f64fb322879643039ce87e70ca18b3c85. Isolated checkout. Pixel 7a 41301JEHN02777 connected.

Required: current researched Talk recommendations; no stale episode answers; fluent continuity; text chat and icon-only recent chats with account sync; full-screen orb tracking; provider audit/priority with fast fallback; independent ratings/episode loading; stable Picked for You; detail preparation and smooth player reveal; physical validation then GitHub publication and artifact verification. Respect API quotas. Mobile scope.

Findings:
- Talk prompt both requires research and allows immediate recommendations. Tool completion checks connection epoch but not question identity. Async question reads can refer to a newer utterance.
- initialPlaybackRace returns only the priority edition provider, serializing fallback. Details uses the same exclusive path.
- Picked for You reranks and publishes on every hydrated candidate and on history ordering changes.
- Recent transcript is local, one conversation only. Existing authenticated per-owner Durable Object can store bounded chats without a new service.

Implemented: recent-year research gates and latest-question ownership; native text conversation, bounded local/account archive, icon-only history and full-screen gaze; priority-head-start provider racing in details/player; independent episodes/ratings; stable Home taste keys and rail order; smooth launch/video reveal.

Physical Pixel evidence (debug signer, existing data preserved): Minority Report first frame 3.7s and seek passed; original Don't Hug Me I'm Scared S1E1 4.7s and seek passed. Direct native preparation succeeded for Flixer, Serienstream, Filmo (Interstellar), AniWorld (Black Clover after canonical IMDb fix), Seven Movies, Movy, CineJoy, RamoFlix, Doraby, Moviepire and Miruro. AniKuro's two publisher-listed origins time out on host and phone; its per-resolution transport circuit now avoids eleven serial dead-origin calls. Definite 404/410 files skip browser retry. Saved server hints no longer disable other mirrors; explicit pins remain strict.

Talk phone replacement test reached recommendations for the new sci-fi request without old episode output. Research returned HTTP 429; no successful live recent recommendation count is claimed. New socket ownership removes uncorrelatable old typed-turn completions. Three Talk UI tests passed on Pixel. Account archive server isolation/tombstone/size tests pass; physical account transport is not verified because this phone is signed out.

IMDb Minority 7.6 / RT 89 verified. Incoherence IMDb 6.7 verified via exact IMDb identity and original release year 1994; primary RT header parsing added for its unrated 31-minute record. The 2011 web series must not borrow the 2022 TV series score. Incoherence playback was unavailable across attempted sources; no universal availability claim.

Worker: 24 files / 226 tests, typecheck and dry-run passed. Initial full CI run 39477316824 passed API 35 device suite, unit/lint, signing and draft artifact checks at 05687066. Final fixes require a new current-commit draft build and metadata/Home/device confirmation before publishing. Local lint stalled in Kotlin UAST; CI lint is the release gate. TV version and TV-specific source files remain unchanged.
