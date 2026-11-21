import assert from 'node:assert/strict';

const base = process.env.ALIFLIX_RECOMMENDATION_BASE_URL
  || 'https://aliflix-recommendations.equable-equipment.workers.dev';
const titles = [
  { imdbId: 'tt1375666', mediaType: 'movie', expectedType: 'movie', awards: /\bWon\s+\d+\s+Oscars?\b/i },
  { imdbId: 'tt0944947', mediaType: 'tv', expectedType: 'series', awards: /\S/ },
];

for (const { imdbId, mediaType, expectedType, awards } of titles) {
  const response = await fetch(new URL('/v1/metadata/omdb', base), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ imdbId, mediaType }),
    signal: AbortSignal.timeout(15_000),
  });
  assert.equal(response.status, 200, `OMDb ${mediaType} lookup failed with HTTP ${response.status}`);
  const result = await response.json();
  assert.equal(result.found, true);
  assert.equal(result.imdbId, imdbId);
  assert.equal(result.type, expectedType);
  assert.match(result.awards ?? '', awards, `Missing awards for ${imdbId}`);
  console.log(`Verified OMDb awards for ${mediaType} ${imdbId}`);
}
