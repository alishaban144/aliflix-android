import { afterEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';

const env: any = {
  GEMINI_API_KEY: 'gemini-test',
  TMDB_API_KEY: 'tmdb-v3-key',
  RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) },
};

const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'content-type': 'application/json' },
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('TMDB-backed mobile catalogue routes', () => {
  it('returns authoritative TV status, creators, genres, and original language', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      expect(url.searchParams.get('api_key')).toBe('tmdb-v3-key');
      expect(url.pathname).toBe('/3/tv/1396');
      return response({
        id: 1396,
        name: 'Breaking Bad',
        original_name: 'Breaking Bad',
        overview: 'A chemistry teacher changes course.',
        poster_path: '/poster.jpg',
        backdrop_path: '/backdrop.jpg',
        first_air_date: '2008-01-20',
        original_language: 'en',
        origin_country: ['US'],
        vote_average: 8.9,
        vote_count: 15000,
        status: 'Ended',
        episode_run_time: [47],
        genres: [{ id: 18, name: 'Drama' }, { id: 80, name: 'Crime' }],
        created_by: [{ id: 66633, name: 'Vince Gilligan', profile_path: '/vince.jpg' }],
        aggregate_credits: { cast: [{ id: 17419, name: 'Bryan Cranston' }] },
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await worker.fetch(new Request('https://worker.test/v3/titles/tv/1396'), env);
    expect(result.status).toBe(200);
    expect(await result.json()).toMatchObject({
      tmdbId: 1396,
      mediaType: 'tv',
      title: 'Breaking Bad',
      status: 'Ended',
      genres: ['Drama', 'Crime'],
      originalLanguage: 'en',
      creators: [{ tmdbId: 66633, name: 'Vince Gilligan', profilePath: '/vince.jpg' }],
      cast: [{ tmdbId: 17419, name: 'Bryan Cranston' }],
    });
  });

  it('deduplicates combined person credits by media type and TMDB ID', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname.endsWith('/person/66633/combined_credits')) {
        return response({
          crew: [
            { id: 1396, media_type: 'tv', name: 'Breaking Bad', first_air_date: '2008-01-20', genre_ids: [18, 80], vote_average: 8.9 },
            { id: 1396, media_type: 'tv', name: 'Breaking Bad', first_air_date: '2008-01-20', genre_ids: [18, 80], vote_average: 8.9 },
          ],
          cast: [
            { id: 37165, media_type: 'movie', title: 'Hancock', release_date: '2008-07-01', genre_ids: [35], vote_average: 6.3 },
          ],
        });
      }
      if (url.pathname.endsWith('/genre/movie/list')) return response({ genres: [{ id: 35, name: 'Comedy' }] });
      if (url.pathname.endsWith('/genre/tv/list')) return response({ genres: [{ id: 18, name: 'Drama' }, { id: 80, name: 'Crime' }] });
      throw new Error(`Unexpected URL ${url}`);
    }));

    const result = await worker.fetch(new Request('https://worker.test/v3/people/66633/credits?name=Vince%20Gilligan'), env);
    expect(result.status).toBe(200);
    const body: any = await result.json();
    expect(body.person).toEqual({ tmdbId: 66633, name: 'Vince Gilligan' });
    expect(body.results).toHaveLength(2);
    expect(body.results.filter((item: any) => item.mediaType === 'tv' && item.tmdbId === 1396)).toHaveLength(1);
    expect(body.results.find((item: any) => item.tmdbId === 1396).genres).toEqual(['Drama', 'Crime']);
  });

  it('keeps editorial picks recent, rated, released, and TMDB-only', async () => {
    const year = new Date().getUTCFullYear();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      expect(url.hostname).toBe('api.themoviedb.org');
      expect(url.searchParams.get('api_key')).toBe('tmdb-v3-key');
      if (url.pathname.endsWith('/genre/movie/list')) return response({ genres: [{ id: 35, name: 'Comedy' }] });
      if (url.pathname.endsWith('/genre/tv/list')) return response({ genres: [{ id: 18, name: 'Drama' }] });
      if (url.pathname.endsWith('/discover/movie')) {
        expect(url.searchParams.get('primary_release_date.lte')).toBeTruthy();
        return response({ page: 1, total_pages: 1, total_results: 2, results: [
          { id: 1, title: 'Recent Movie', release_date: `${year}-01-10`, poster_path: '/movie.jpg', genre_ids: [35], vote_average: 8.4, vote_count: 2400 },
          { id: 2, title: 'Future Movie', release_date: '2999-01-01', poster_path: '/future.jpg', genre_ids: [35], vote_average: 10, vote_count: 9000 },
        ] });
      }
      if (url.pathname.endsWith('/discover/tv')) {
        expect(url.searchParams.get('first_air_date.lte')).toBeTruthy();
        return response({ page: 1, total_pages: 1, total_results: 1, results: [
          { id: 3, name: 'Recent Series', first_air_date: `${year - 1}-06-15`, poster_path: '/tv.jpg', genre_ids: [18], vote_average: 8.7, vote_count: 1800 },
        ] });
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await worker.fetch(new Request('https://worker.test/v3/editorial-picks'), env);
    expect(result.status).toBe(200);
    const body: any = await result.json();
    expect(body.results.map((item: any) => item.title)).toEqual(['Recent Series', 'Recent Movie']);
    expect(body.results.some((item: any) => item.title === 'Future Movie')).toBe(false);
    expect(body.results.every((item: any) => item.posterPath && item.tmdbRating >= 7)).toBe(true);
  });
});
