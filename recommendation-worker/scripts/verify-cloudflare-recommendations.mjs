import assert from 'node:assert/strict';

const origin = 'https://aliflix-recommendations.equable-equipment.workers.dev';
const prompt = 'I want a dark, intelligent sci-fi story about identity, memory, consciousness, or the nature of reality. Atmospheric, mysterious, emotionally engaging, and not heavily action-focused.';
const gold = {
  movie: [
    'Arrival', 'Ex Machina', 'Blade Runner 2049', 'Coherence', 'Moon', 'Annihilation',
    'Predestination', 'Primer', 'Source Code', 'Possessor', 'Archive',
    'Eternal Sunshine of the Spotless Mind', 'Her', 'Gattaca', 'The Machinist',
    'Under the Skin', 'Another Earth', 'I Origins', 'The Congress', 'After Yang',
  ],
  tv: [
    'Dark', 'Severance', 'Devs', 'Counterpart', 'The OA', 'Tales from the Loop',
    'Westworld', 'Black Mirror', '1899', 'Maniac', 'Homecoming', 'Undone',
    'The Peripheral', 'Station Eleven', 'Altered Carbon', 'Bodies',
    'The Lazarus Project', 'Constellation', 'Silo', 'Shining Girls',
  ],
};
const canonical = value => value.normalize('NFKC').toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '');

async function waitForCloudflareDeployment() {
  for (let attempt = 1; attempt <= 12; attempt++) {
    try {
      const response = await fetch(new URL('/health', origin), {
        headers: { Accept: 'application/json', 'User-Agent': 'Aliflix-Cloudflare-Release-Validation' },
        signal: AbortSignal.timeout(15000),
      });
      const payload = await response.json().catch(() => ({}));
      if (response.ok && payload.cloudflareAiConfigured === true) {
        console.log(`Cloudflare deployment ready after ${attempt} health check(s)`);
        return;
      }
    } catch {}
    if (attempt < 12) await new Promise(resolve => setTimeout(resolve, 5000));
  }
  throw new Error('Cloudflare deployment did not expose the Workers AI binding within 60 seconds');
}

await waitForCloudflareDeployment();

async function recommend(mediaType, requestId, cursor) {
  const response = await fetch(new URL('/v3/recommendations', origin), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'User-Agent': 'Aliflix-Cloudflare-Release-Validation',
    },
    body: JSON.stringify({
      requestId,
      mode: 'describe',
      aiModel: 'cloudflare-gpt-oss-120b',
      query: prompt,
      mediaType,
      filters: {},
      pageSize: 24,
      ...(cursor ? { cursor } : {}),
    }),
    signal: AbortSignal.timeout(120000),
  });
  const payload = await response.json().catch(() => ({}));
  assert(response.ok, `${mediaType}: HTTP ${response.status} ${JSON.stringify(payload)}`);
  return payload;
}

const firstPages = {};
for (const mediaType of ['movie', 'tv']) {
  const requestId = crypto.randomUUID();
  const payload = await recommend(mediaType, requestId);
  assert(payload.results.length >= 20, `${mediaType}: expected at least 20 results, got ${payload.results.length}`);
  assert.equal(new Set(payload.results.map(item => item.tmdbId)).size, payload.results.length, `${mediaType}: duplicate TMDB identities`);
  const resultTitles = new Set(payload.results.map(item => canonical(item.title)));
  const overlap = gold[mediaType].filter(title => resultTitles.has(canonical(title)));
  assert(overlap.length >= 11, `${mediaType}: only ${overlap.length}/20 gold-standard matches: ${overlap.join(', ')}`);
  assert(payload.nextCursor, `${mediaType}: missing continuation cursor`);
  firstPages[mediaType] = { requestId, payload };
  console.log(`${mediaType}: ${payload.results.length} results; ${overlap.length}/20 reference matches: ${overlap.join(', ')}`);
}

const movieFirst = firstPages.movie;
const more = await recommend('movie', movieFirst.requestId, movieFirst.payload.nextCursor);
assert(more.results.length >= 20, `Find More: expected at least 20 fresh results, got ${more.results.length}`);
const firstIds = new Set(movieFirst.payload.results.map(item => item.tmdbId));
assert(more.results.every(item => !firstIds.has(item.tmdbId)), 'Find More repeated a previously shown TMDB identity');
assert.equal(new Set(more.results.map(item => item.tmdbId)).size, more.results.length, 'Find More contains duplicates');
console.log(`Find More: ${more.results.length} fresh TMDB-verified movies; zero repeated identities`);
