import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const endpoint = (process.argv[2] || process.env.RECOMMENDATION_URL || '').replace(/\/$/, '');
const versionId = process.argv[3] || process.env.RECOMMENDATION_VERSION_ID || '';
if (!endpoint) {
  throw new Error('Usage: npm run gold:live -- https://worker.example.workers.dev');
}

const fixtures = JSON.parse(await readFile(new URL('../test/fixtures/describe-gold-standards.json', import.meta.url), 'utf8'));
const normalize = value => value.toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/^marvel s /u, '');
const reports = [];

for (const fixture of fixtures) {
  const response = await fetch(`${endpoint}/v3/recommendations`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(versionId ? { 'Cloudflare-Workers-Version-Overrides': `aliflix-recommendations="${versionId}"` } : {}),
    },
    body: JSON.stringify({
      requestId: randomUUID(),
      mode: 'describe',
      query: fixture.query,
      mediaType: fixture.mediaType,
      filters: {},
      pageSize: 20,
    }),
  });
  const body = await response.json();
  if (!response.ok) throw new Error(`${fixture.id}: HTTP ${response.status} ${JSON.stringify(body)}`);
  const titles = body.results.map(result => result.title);
  const normalizedTitles = new Set(titles.map(normalize));
  const expectedHits = fixture.expected.filter(title => normalizedTitles.has(normalize(title)));
  const weakHits = fixture.knownWeak.filter(title => normalizedTitles.has(normalize(title)));
  const passed = body.results.length >= 6 && expectedHits.length >= 4 && weakHits.length === 0;
  reports.push({
    id: fixture.id,
    passed,
    resultCount: body.results.length,
    expectedHits,
    expectedRecall: Number((expectedHits.length / fixture.expected.length).toFixed(3)),
    weakHits,
    titles,
  });
  console.log(JSON.stringify(reports.at(-1)));
}

const failed = reports.filter(report => !report.passed);
console.log(JSON.stringify({ passed: failed.length === 0, cases: reports.length, failed: failed.map(report => report.id) }));
if (failed.length) process.exitCode = 1;
