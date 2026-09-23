import { afterEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';
import { catalogueSearch, discoverCategory, discoveryParams, validDiscoveryItem, confidenceScore, MOODS } from '../src/discover';
import { browse, refinements } from '../src/browse';
const env: any = { TMDB_API_KEY: 'test', RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) } };
const today = '2026-09-13';
const item = { id: 12, title: 'Example', name: 'Example', poster_path: '/p.jpg', backdrop_path: '/b.jpg', release_date: '2026-09-01', first_air_date: '2026-08-01', vote_count: 800, vote_average: 7.5, popularity: 20, genre_ids: [878, 9648] };
const page = (results: unknown[], n = 1) => ({ results, page: n, total_pages: 4, total_results: 80 });
const response = (body: unknown) => new Response(JSON.stringify(body));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('deterministic mobile Discover', () => {
  it('rejects weak votes, adult, future, invalid dates and missing artwork', () => {
    expect(validDiscoveryItem(item, 'movie', 'top-rated', today)).toBe(true);
    for (const patch of [{ adult: true }, { vote_count: 5 }, { release_date: '2027-01-01' }, { release_date: 'n/a' }, { poster_path: null }, { backdrop_path: null }]) {
      expect(validDiscoveryItem({ ...item, ...patch }, 'movie', 'top-rated', today)).toBe(false);
    }
    expect(confidenceScore({ ...item, vote_count: 5, vote_average: 10 }, 'top-rated')).toBeLessThan(confidenceScore(item, 'top-rated'));
  });
  it('keeps allocated titles out of later category refinements', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname.includes('/genre/')) return response({ genres: [{ id: 1, name: 'Drama' }] });
      return response(page([item, { ...item, id: 13 }]));
    }));
    const result = await browse(env, 'genre:movie:1', 1, [12]);
    expect(result.results.map(title => title.tmdbId)).toEqual([13]);
  });

  it('offers at least twenty deterministic refinements for every selected genre', () => {
    const movieGenres = Array.from({ length: 19 }, (_, index) => ({ id: index + 1, name: `Movie ${index + 1}` }));
    const tvGenres = Array.from({ length: 16 }, (_, index) => ({ id: index + 1, name: `TV ${index + 1}` }));
    expect(refinements('movie', movieGenres, 1).length).toBeGreaterThanOrEqual(20);
    expect(refinements('tv', tvGenres, 1).length).toBeGreaterThanOrEqual(20);
  });

  it('retains concept and exclusion constraints in every controlled mood fallback', () => {
    for (const category of Object.keys(MOODS)) {
      for (const type of ['movie', 'tv'] as const) {
        const strict = discoveryParams(category as any, type, 2, today);
        const relaxed = discoveryParams(category as any, type, 2, today, true);
        expect(strict.with_genres).toContain('|');
        expect(strict.with_keywords).toContain('|');
        expect(relaxed.with_keywords).toEqual(strict.with_keywords);
        expect(relaxed.without_genres).toEqual(strict.without_genres);
        expect(relaxed['vote_count.gte']).toBeLessThan(Number(strict['vote_count.gte']));
      }
    }
    expect(validDiscoveryItem({ ...item, genre_ids: [10759, 10765] }, 'tv', 'feel-good', today)).toBe(false);
    expect(validDiscoveryItem({ ...item, genre_ids: [35], overview: 'A sadistic serial killer hunts friends.' }, 'tv', 'feel-good', today)).toBe(false);
    expect(discoveryParams('feel-good', 'tv', 1, today).without_keywords).toContain('10123');
    expect(validDiscoveryItem({ ...item, genre_ids: [35, 27] }, 'movie', 'feel-good', today)).toBe(false);
    expect(validDiscoveryItem({ ...item, genre_ids: [18] }, 'movie', 'fast-paced', today)).toBe(false);
    expect(validDiscoveryItem({ ...item, genre_ids: [80, 10751] }, 'tv', 'dark', today)).toBe(false);
  });
  it('uses multi-search for people and typed search endpoints for filters with no model credentials', async () => {
    const paths: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input)); paths.push(url.pathname);
      if (url.pathname.includes('/genre/')) return response({ genres: [] });
      expect(url.searchParams.get('include_adult')).toBe('false');
      return response(page(url.pathname.endsWith('/multi') ? [{ ...item, media_type: 'movie' }, { id: 31, media_type: 'person', name: 'Person' }] : [item]));
    }));
    expect((await catalogueSearch(env, 'Person', 'all', 1)).people).toEqual([{ tmdbId: 31, name: 'Person' }]);
    expect(paths).toContain('/3/search/multi'); paths.length = 0;
    const typed = await catalogueSearch(env, 'Example', 'tv', 2);
    expect(paths).toContain('/3/search/tv'); expect(paths.some(p => p.includes('/movie') || p.includes('/multi'))).toBe(false);
    expect(typed.results[0].mediaType).toBe('tv');
  });
  it('merges typed trending pages and uses current TV plus recent-release movie queries for New', async () => {
    const urls: URL[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input)); urls.push(url);
      if (url.pathname.includes('/genre/')) return response({ genres: [] });
      return response(page([item, item], 2));
    }));
    const trending = await discoverCategory(env, 'trending', 'all', 2, today);
    expect(trending.results.map(x => x.mediaType)).toEqual(['movie', 'tv']);
    expect(trending.hasMore).toBe(true);
    expect(urls.some(u => u.pathname === '/3/trending/movie/week')).toBe(true);
    expect(urls.some(u => u.pathname === '/3/trending/tv/week')).toBe(true);
    urls.length = 0;
    await discoverCategory(env, 'new', 'all', 1, today);
    expect(urls.some(u => u.pathname === '/3/tv/on_the_air')).toBe(true);
    const movie = urls.find(u => u.pathname === '/3/discover/movie')!;
    expect(movie.searchParams.get('primary_release_date.lte')).toBe(today);
    expect(movie.searchParams.get('primary_release_date.gte')).toBe('2026-05-16');
  });
  it('performs at most one relaxed query per type when strict results are scarce', async () => {
    const queries: URL[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname.includes('/genre/')) return response({ genres: [] });
      queries.push(url); return response(page([item]));
    }));
    const result = await discoverCategory(env, 'mind-bending', 'movie', 1, today);
    expect(queries).toHaveLength(2); expect(result.results).toHaveLength(1);
    expect(queries[0].searchParams.get('with_keywords')).toBe(queries[1].searchParams.get('with_keywords'));
  });
  it('serves repeat category requests from cache and validates filters before TMDB calls', async () => {
    const cache = new Map<string, Response>();
    vi.stubGlobal('caches', { default: {
      match: async (request: Request) => cache.get(request.url)?.clone(),
      put: async (request: Request, result: Response) => { cache.set(request.url, result); },
    } });
    const fetcher = vi.fn(async (input: RequestInfo | URL) => new URL(String(input)).pathname.includes('/genre/') ? response({ genres: [] }) : response(page([item])));
    vi.stubGlobal('fetch', fetcher);
    const request = new Request('https://worker.test/v3/discover?category=top-rated&type=movie');
    expect((await worker.fetch(request, env)).status).toBe(200);
    const calls = fetcher.mock.calls.length;
    expect((await worker.fetch(request, env)).status).toBe(200); expect(fetcher).toHaveBeenCalledTimes(calls);
    expect((await worker.fetch(new Request('https://worker.test/v3/discover?type=person'), env)).status).toBe(400);
    expect(fetcher).toHaveBeenCalledTimes(calls);
  });
});
