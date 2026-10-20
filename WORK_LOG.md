# v3.1.177 usability and reliability

Baseline: published v3.1.176, 5aaa915f64fb322879643039ce87e70ca18b3c85. Isolated checkout. Pixel 7a 41301JEHN02777 connected.

Required: current researched Talk recommendations; no stale episode answers; fluent continuity; text chat and icon-only recent chats with account sync; full-screen orb tracking; provider audit/priority with fast fallback; independent ratings/episode loading; stable Picked for You; detail preparation and smooth player reveal; physical validation then GitHub publication and artifact verification. Respect API quotas. Mobile scope.

Findings:
- Talk prompt both requires research and allows immediate recommendations. Tool completion checks connection epoch but not question identity. Async question reads can refer to a newer utterance.
- initialPlaybackRace returns only the priority edition provider, serializing fallback. Details uses the same exclusive path.
- Picked for You reranks and publishes on every hydrated candidate and on history ordering changes.
- Recent transcript is local, one conversation only. Existing authenticated per-owner Durable Object can store bounded chats without a new service.

Validation pending. Do not claim upstream availability for every title or claim physical proof from compilation.
