import assert from 'node:assert/strict';

const origin = 'https://aliflix-recommendations.equable-equipment.workers.dev';
const checks = [
  ['genre:movie:36:ancient-history', 'History', 'Ancient History'],
  ['genre:movie:18:crime-drama', 'Drama', 'Crime Drama'],
  ['genre:movie:878:time-travel-sci-fi', 'Science Fiction', 'Time-Travel Sci-Fi'],
  ['genre:movie:53:psychological-thriller', 'Thriller', 'Psychological Thriller'],
  ['genre:tv:18:crime-drama', 'Drama', 'Crime Drama'],
];
for (const [category, parent, section] of checks) {
  let payload;
  for (let attempt = 0; attempt < 3; attempt++) {
    const url = new URL('/v3/discover', origin);
    url.searchParams.set('category', category);
    url.searchParams.set('type', category.split(':')[1]);
    url.searchParams.set('page', '1');
    const response = await fetch(url, { headers: { 'User-Agent': 'Aliflix-Release-Validation', Accept: 'application/json' }, signal: AbortSignal.timeout(30000) });
    if (response.ok) { payload = await response.json(); break; }
    if (attempt === 2) throw new Error(`${category}: HTTP ${response.status}`);
    await new Promise(resolve => setTimeout(resolve, 4000));
  }
  assert(payload.sections.some(s => s.name === section), `${category}: missing themed section`);
  assert(payload.results.length > 0, `${category}: empty category`);
  assert(payload.results.every(title => title.genres.includes(parent)), `${category}: escaped parent genre`);
  assert.equal(new Set(payload.results.map(title => title.tmdbId)).size, payload.results.length);
  console.log(`${category}: ${payload.results.length} titles; all match ${parent}`);
}
