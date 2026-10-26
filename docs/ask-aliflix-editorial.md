# Ask Aliflix: request and API key

## Change the Gemini API key

Cloudflare dashboard → Workers & Pages → **aliflix-recommendations** → Settings → Variables and Secrets → edit **GEMINI_API_KEY** as a Secret → save/deploy.

Alternatively, in PowerShell:

```powershell
cd D:\Aliflix\aliflix-android\recommendation-worker
npx.cmd wrangler secret put GEMINI_API_KEY
```

Paste the new key at the hidden prompt. Do not place it in source code, GitHub, or chat. Updating this secret creates and deploys a Worker version. No Android rebuild is needed for a key-only change.

Official instructions: https://developers.cloudflare.com/workers/configuration/secrets/

## Previous Describe behavior

The Android app forwarded the text unchanged. Gemini generation requested up to 12 titles (8 for the older optimized model), each with title, releaseYear, confidence and reason. Its system instruction required every essential premise facet and allowed fewer results rather than broader picks. The Worker then resolved titles, enforced metadata/semantic relevance rules and asked a second AI call to judge them. Confidence/relevance thresholds and fallback catalogue retrieval could turn a generated list into zero displayed titles.

## New request

For a first movie request with no additional filters, the Gemini user content is JSON equivalent to:

```json
{
  "query": "Movies about aliens and AI. Recent",
  "currentDate": "2026-09-22",
  "authoritativeMediaType": "movie",
  "explicitFilters": {
    "originCountries": [], "includedGenres": [], "excludedGenres": [],
    "productionCompanyIds": [], "excludedTmdbIds": [], "excludedTitles": []
  },
  "targetCount": 20,
  "expansionPass": false,
  "excludedTitles": []
}
```

The date is generated per request. User-selected filters are included when present. Refinements are passed with the original request. The system instruction is `EDITORIAL_RECOMMENDATIONS_PROMPT` in `recommendation-worker/src/prompts.ts`; it asks the LLM to be a film/TV critic, connoisseur and sommelier, honor natural-language preferences, and return 20 distinct real titles with its own 0–10 ratings. Only title, releaseYear and rating are requested. Gemini uses `gemini-3.8-flash:generateContent`, structured JSON, low thinking for Describe, medium for Similar, and 8192 output tokens. Optional Groq receives the same editorial instruction and schema.

TMDB resolves titles and release years to clickable IDs/posters. It does not judge themes, reject low scores, add recommendations or override LLM scores. Duplicate IDs and unresolved identities cannot be displayed as distinct clickable titles; one replacement generation is allowed within the request budget. Provider errors are shown instead of silently switching models or generating catalogue fallback recommendations.

## Short Codex prompt

Use only gemini-3.8-flash for Gemini, keeping Groq optional in Similar and Describe. Ask the selected LLM to act as a film/TV critic, connoisseur and sommelier and return 20 distinct titles ranked by its own rating. Remove relevance verification/filtering and fallback models; use TMDB only for identity and display metadata. Show no reasons or descriptions. Deploy the Worker.
