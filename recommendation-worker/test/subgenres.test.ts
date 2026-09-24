import { afterEach, expect, it, vi } from 'vitest';
import { subgenres } from '../src/subgenres';
import { browse } from '../src/browse';
import { RecommendationEnv } from '../src/types';

afterEach(() => vi.unstubAllGlobals());

it('covers every TMDB parent with distinct, actionable themed sections', () => {
  const movie = [28,12,16,35,80,99,18,10751,14,36,27,10402,9648,10749,878,10770,53,10752,37];
  const tv = [10759,16,35,80,99,18,10751,10762,9648,10763,10764,10765,10766,10767,10768,37];
  for (const [type, ids] of [['movie', movie], ['tv', tv]] as const) for (const parent of ids) {
    const sections = subgenres(type, parent);
    expect(sections.length, `${type}:${parent}`).toBeGreaterThanOrEqual(8);
    expect(new Set(sections.map(s => s.id)).size).toBe(sections.length);
    for (const section of sections) {
      expect(section.keywords.length || section.genres.filter(g => g !== parent).length).toBeGreaterThan(0);
      for (const genre of section.genres) expect(ids).toContain(genre);
    }
  }
  for (const parent of [18,878,53,36]) expect(subgenres('movie', parent)).toHaveLength(20);
  expect(subgenres('movie', 36).map(s => s.name)).toContain('Ancient History');
  expect(subgenres('movie', 36).some(s => ['Romance', 'Action', 'Drama'].includes(s.name))).toBe(false);
});

it('combines the parent with a verified exact theme and never widens missing keywords', async () => {
  let found = true;
  const queries: URL[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input));
    if (url.pathname.includes('/genre/')) return Response.json({ genres: [{ id:36, name:'History' }] });
    if (url.pathname.endsWith('/search/keyword')) return Response.json({ results: found ? [{ id:100, name:url.searchParams.get('query') }] : [{ id:999, name:'unrelated result' }] });
    queries.push(url);
    return Response.json({ page:1, total_pages:1, results:[] });
  }));
  const env = { TMDB_API_KEY:'test' } as RecommendationEnv;
  await browse(env, 'genre:movie:36:ancient-history', 1);
  expect(queries).toHaveLength(1);
  expect(queries[0].searchParams.get('with_genres')).toBe('36');
  expect(queries[0].searchParams.get('with_keywords')).toBe('100');
  found = false; queries.length = 0;
  const missing = await browse(env, 'genre:movie:36:ancient-history', 1);
  expect(missing.results).toEqual([]); expect(missing.hasMore).toBe(false); expect(queries).toHaveLength(0);
});
