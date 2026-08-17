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
        external_ids: { imdb_id: 'tt0903747' },
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await worker.fetch(new Request('https://worker.test/v3/titles/tv/1396'), env);
    expect(result.status).toBe(200);
    expect(await result.json()).toMatchObject({
      tmdbId: 1396,
      mediaType: 'tv',
      title: 'Breaking Bad',
      imdbId: 'tt0903747',
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
      if (url.pathname.endsWith('/person/66633')) {
        return response({ id: 66633, name: 'Vince Gilligan', profile_path: '/vince.jpg' });
      }
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

    const result = await worker.fetch(new Request('https://worker.test/v3/people/66633/credits?name=Wrong%20Gilligan'), env);
    expect(result.status).toBe(200);
    const body: any = await result.json();
    expect(body.person).toEqual({ tmdbId: 66633, name: 'Vince Gilligan', profilePath: '/vince.jpg' });
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

  it('returns one complete TMDB-only Home snapshot with stable rails and released picks', async () => {
    const year = new Date().getUTCFullYear();
    const movie = (id: number, title: string, day = '03-04') => ({
      id,
      title,
      release_date: `${year}-${day}`,
      poster_path: `/movie-${id}.jpg`,
      backdrop_path: `/movie-${id}-wide.jpg`,
      genre_ids: [35],
      vote_average: 8.1,
      vote_count: 1200,
    });
    const tv = (id: number, name: string, day = '02-03') => ({
      id,
      name,
      first_air_date: `${year}-${day}`,
      poster_path: `/tv-${id}.jpg`,
      backdrop_path: `/tv-${id}-wide.jpg`,
      genre_ids: [18],
      vote_average: 8.3,
      vote_count: 900,
    });
    const seenHosts: string[] = [];
    const genreDiscoveries: Array<{ path: string; genres: string }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      seenHosts.push(url.hostname);
      expect(url.searchParams.get('api_key')).toBe('tmdb-v3-key');
      if (url.pathname.endsWith('/genre/movie/list')) return response({ genres: [{ id: 35, name: 'Comedy' }] });
      if (url.pathname.endsWith('/genre/tv/list')) return response({ genres: [{ id: 18, name: 'Drama' }] });
      if (url.pathname.endsWith('/trending/all/week')) return response({
        page: Number(url.searchParams.get('page')),
        total_pages: 2,
        total_results: 3,
        results: Number(url.searchParams.get('page')) === 1
          ? [{ ...movie(10, 'Trending Film'), media_type: 'movie' }, { ...tv(20, 'Trending Series'), media_type: 'tv' }]
          : [{ id: 30, name: 'Ignored Person', media_type: 'person', profile_path: '/person.jpg' }],
      });
      if (url.pathname.endsWith('/movie/now_playing')) return response({ page: 1, total_pages: 1, total_results: 1, results: [movie(11, 'Now Film')] });
      if (url.pathname.endsWith('/tv/on_the_air')) return response({ page: 1, total_pages: 1, total_results: 1, results: [tv(21, 'Airing Series')] });
      if (url.pathname.endsWith('/movie/popular')) return response({
        page: Number(url.searchParams.get('page')),
        total_pages: 2,
        total_results: 3,
        results: Number(url.searchParams.get('page')) === 1
          ? [movie(12, 'Popular Film')]
          : [movie(13, 'More Popular Film'), { ...movie(14, 'Future Film'), release_date: '2999-01-01' }],
      });
      if (url.pathname.endsWith('/tv/popular')) return response({
        page: Number(url.searchParams.get('page')),
        total_pages: 2,
        total_results: 2,
        results: Number(url.searchParams.get('page')) === 1 ? [tv(22, 'Popular Series')] : [tv(23, 'More Popular Series')],
      });
      if (url.pathname.endsWith('/discover/movie')) {
        const genres = url.searchParams.get('with_genres');
        if (genres) {
          genreDiscoveries.push({ path: url.pathname, genres });
          const id = Number(`1${genres.replaceAll(',', '')}`);
          return response({ page: 1, total_pages: 1, total_results: 1, results: [
            { ...movie(id, `Movie genres ${genres}`), genre_ids: genres.split(',').map(Number) },
          ] });
        }
        return response({ page: 1, total_pages: 1, total_results: 1, results: [movie(15, 'Acclaimed Film')] });
      }
      if (url.pathname.endsWith('/discover/tv')) {
        const genres = url.searchParams.get('with_genres');
        if (genres) {
          genreDiscoveries.push({ path: url.pathname, genres });
          const id = Number(`2${genres.replaceAll(',', '')}`);
          return response({ page: 1, total_pages: 1, total_results: 1, results: [
            { ...tv(id, `TV genres ${genres}`), genre_ids: genres.split(',').map(Number) },
          ] });
        }
        return response({ page: 1, total_pages: 1, total_results: 1, results: [tv(24, 'Acclaimed Series')] });
      }
      throw new Error(`Unexpected URL ${url}`);
    }));

    const result = await worker.fetch(new Request('https://worker.test/v3/home'), env);
    expect(result.status).toBe(200);
    const body: any = await result.json();
    expect(body.hero).toMatchObject({ tmdbId: 10, mediaType: 'movie', title: 'Trending Film' });
    expect(body.rails.slice(0, 5).map((rail: any) => rail.title)).toEqual([
      'Trending this week', 'Now playing', 'Airing now', 'Popular movies', 'Popular series',
    ]);
    expect(body.rails.map((rail: any) => rail.title)).toEqual(expect.arrayContaining([
      'Action movies',
      'Comedy movies',
      'Crime series',
      'Science fiction & fantasy series',
      'Action thrillers',
      'Romantic comedies',
      'Crime dramas',
    ]));
    expect(body.rails.length).toBeGreaterThanOrEqual(19);
    expect(genreDiscoveries).toEqual(expect.arrayContaining([
      { path: '/3/discover/movie', genres: '28,53' },
      { path: '/3/discover/movie', genres: '10749,35' },
      { path: '/3/discover/tv', genres: '80,18' },
    ]));
    expect(genreDiscoveries).toHaveLength(14);
    expect(body.rails.flatMap((rail: any) => rail.items).some((item: any) => item.title === 'Future Film')).toBe(false);
    expect(body.editorialPicks.map((item: any) => item.title).sort()).toEqual(['Acclaimed Film', 'Acclaimed Series']);
    expect(body.rails.flatMap((rail: any) => rail.items).every((item: any) => item.posterPath && item.releaseDate)).toBe(true);
    const homeKeys = body.rails.flatMap((rail: any) => rail.items.map((item: any) => `${item.mediaType}:${item.tmdbId}`));
    expect(new Set(homeKeys).size).toBe(homeKeys.length);
    expect(seenHosts.every(host => host === 'api.themoviedb.org')).toBe(true);
  });
});
