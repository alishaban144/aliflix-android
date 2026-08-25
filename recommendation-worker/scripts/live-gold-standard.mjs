import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const endpoint = (process.argv[2] || process.env.RECOMMENDATION_URL || '').replace(/\/$/, '');
const versionId = process.argv[3] || process.env.RECOMMENDATION_VERSION_ID || '';
if (!endpoint) {
  throw new Error('Usage: npm run gold:live -- https://worker.example.workers.dev');
}

const describeFixtures = JSON.parse(await readFile(new URL('../test/fixtures/describe-gold-standards.json', import.meta.url), 'utf8'));
const similarFixtures = JSON.parse(await readFile(new URL('../test/fixtures/similar-gold-standards.json', import.meta.url), 'utf8'));
const fixtures = [
  ...describeFixtures.map(fixture => ({ ...fixture, mode: 'describe' })),
  ...similarFixtures.map(fixture => ({ ...fixture, mode: 'similar' })),
];
const normalize = value => value.toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/^marvel s /u, '');
const reports = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

for (const [fixtureIndex, fixture] of fixtures.entries()) {
  try {
    const response = await fetch(`${endpoint}/v3/recommendations`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        ...(versionId ? { 'Cloudflare-Workers-Version-Overrides': `aliflix-recommendations="${versionId}"` } : {}),
      },
      body: JSON.stringify({
        requestId: randomUUID(),
        mode: fixture.mode,
        query: fixture.query || '',
        mediaType: fixture.mediaType,
        ...(fixture.anchor ? { anchor: fixture.anchor } : {}),
        ...(fixture.anchors ? { anchors: fixture.anchors } : {}),
        filters: {},
        pageSize: 20,
      }),
      signal: AbortSignal.timeout(180_000),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(`HTTP ${response.status} ${JSON.stringify(body)}`);
    const titles = body.results.map(result => result.title);
    const normalizedTitles = new Set(titles.map(normalize));
    const expectedHits = fixture.expected.filter(title => normalizedTitles.has(normalize(title)));
    const weakHits = fixture.knownWeak.filter(title => normalizedTitles.has(normalize(title)));
    const minimumResults = fixture.minimumResults || (fixture.mode === 'describe' ? 12 : 10);
    const minimumExpectedHits = fixture.minimumExpectedHits || (fixture.mode === 'describe' ? 4 : 3);
    const passed = body.results.length >= minimumResults && expectedHits.length >= minimumExpectedHits && weakHits.length === 0;
    reports.push({
      id: fixture.id,
      passed,
      resultCount: body.results.length,
      minimumResults,
      expectedHits,
      expectedRecall: Number((expectedHits.length / fixture.expected.length).toFixed(3)),
      weakHits,
      titles,
    });
  } catch (error) {
    reports.push({
      id: fixture.id,
      passed: false,
      error: error instanceof Error ? error.message : String(error),
    });
  }
  const latestReport = reports.at(-1);
  console.log(JSON.stringify(latestReport));
  // A provider failure blocks every later case and can consume more of the
  // same exhausted quota. Preserve the first exact failure and stop; ordinary
  // relevance/count failures still run the complete suite.
  if (latestReport?.error && /GEMINI_UNAVAILABLE|AbortError|timed out/i.test(latestReport.error)) break;
  if (fixtureIndex < fixtures.length - 1) await sleep(5_000);
}

const failed = reports.filter(report => !report.passed);
console.log(JSON.stringify({ passed: failed.length === 0, cases: reports.length, failed: failed.map(report => report.id) }));
if (failed.length) process.exitCode = 1;
