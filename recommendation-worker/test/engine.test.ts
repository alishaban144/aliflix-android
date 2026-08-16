import { describe, expect, it } from 'vitest';
import { processRecommendation } from '../src/engine';
import { ParsedRecommendationRequest, RecommendationRequestSchema } from '../src/schemas';
import { InterpretedIntent, ServiceError } from '../src/types';
import { applyTmdbAuthentication } from '../src/tmdb';

const request: ParsedRecommendationRequest = {
  requestId: '00000000-0000-4000-8000-000000000001', mode: 'describe', query: 'funny movies', mediaType: 'movie', pageSize: 20,
  filters: {
    minimumYear: undefined, maximumYear: undefined, originalLanguage: undefined, originCountries: [],
    minimumRuntimeMinutes: undefined, maximumRuntimeMinutes: undefined, includedGenres: [], excludedGenres: [],
    minimumTmdbRating: undefined, excludedTmdbIds: [], excludedTitles: [],
  },
};
const interpreted: InterpretedIntent = {
  hardFilters: { originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [] },
  requiredConceptGroups: [{ label: 'comedy', synonyms: ['funny', 'comedy'], weight: 1 }], softConcepts: [], excludedConcepts: [],
  genreHints: ['Comedy'], toneAndMood: ['funny'], broadSearchPhrases: [],
};

function fakeTmdb(options: { fail?: boolean; authFail?: boolean; empty?: boolean } = {}) {
  let remaining = 40;
  return {
    get callsRemaining() { return remaining; },
    genres: async () => ({ genres: [{ id: 35, name: 'Comedy' }] }),
    searchKeyword: async () => ({ page: 1, total_pages: 1, total_results: 1, results: [{ id: 99, name: 'comedy' }] }),
    searchTitle: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
    recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
    similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
    details: async (_type: string, id: number) => ({ id, title: 'Funny Fixture', overview: 'A funny comedy', genre_ids: [35], genres: [{ id: 35, name: 'Comedy' }] }),
    discover: async () => {
      remaining--;
      if (options.authFail) throw new ServiceError('TMDB_AUTH_FAILED', 'TMDB rejected the credential', 503, false);
      if (options.fail) throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB failed', 503, true);
      const results = options.empty ? [] : [
        { id: 7, title: 'Funny Fixture', overview: 'A funny comedy', genre_ids: [35], vote_average: 7.2, vote_count: 500 },
        { id: 7, title: 'Funny Fixture', overview: 'A duplicate TMDB row', genre_ids: [35], vote_average: 7.2, vote_count: 500 },
      ];
      return { page: 1, total_pages: 1, total_results: results.length, results };
    },
  };
}

describe('TMDB-only recommendation engine', () => {
  it('requires a canonical TMDB ID for similar requests', () => {
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      mode: 'similar',
      anchor: { title: 'Breaking Bad', mediaType: 'tv' },
    })).toThrow();
  });

  it('uses a v3 API key as api_key and only a separately named read token as Bearer', () => {
    const keyUrl = new URL('https://api.themoviedb.org/3/discover/movie');
    const keyHeaders: Record<string, string> = {};
    applyTmdbAuthentication({ TMDB_API_KEY: 'v3-key' }, keyUrl, keyHeaders);
    expect(keyUrl.searchParams.get('api_key')).toBe('v3-key');
    expect(keyHeaders.authorization).toBeUndefined();

    const tokenUrl = new URL('https://api.themoviedb.org/3/discover/tv');
    const tokenHeaders: Record<string, string> = {};
    applyTmdbAuthentication({ TMDB_READ_ACCESS_TOKEN: 'read-token' }, tokenUrl, tokenHeaders);
    expect(tokenUrl.searchParams.has('api_key')).toBe(false);
    expect(tokenHeaders.authorization).toBe('Bearer read-token');

    const bothUrl = new URL('https://api.themoviedb.org/3/discover/movie');
    const bothHeaders: Record<string, string> = {};
    applyTmdbAuthentication(
      { TMDB_API_KEY: 'updated-v3-key', TMDB_READ_ACCESS_TOKEN: 'older-read-token' },
      bothUrl,
      bothHeaders,
    );
    expect(bothUrl.searchParams.get('api_key')).toBe('updated-v3-key');
    expect(bothHeaders.authorization).toBeUndefined();

    const unambiguousUrl = new URL('https://api.themoviedb.org/3/discover/tv');
    const unambiguousHeaders: Record<string, string> = {};
    applyTmdbAuthentication({ TMDB_API_KEY: `eyJ${'x'.repeat(120)}` }, unambiguousUrl, unambiguousHeaders);
    expect(unambiguousUrl.searchParams.get('api_key')).toMatch(/^eyJ/);
    expect(unambiguousHeaders.authorization).toBeUndefined();
  });

  it('deduplicates by media type and TMDB ID, retains TMDB retrieval evidence, and survives embedding failure', async () => {
    const results = await processRecommendation({} as any, request, {
      tmdb: fakeTmdb(), interpret: async () => interpreted,
      embed: async () => { throw new Error('embedding outage'); },
    });
    expect(results).toHaveLength(1);
    expect(results[0]).toMatchObject({ tmdbId: 7, mediaType: 'movie', title: 'Funny Fixture' });
    expect(results[0].retrievalSources.every(source => source.startsWith('discover:'))).toBe(true);
    expect(JSON.stringify(results)).not.toMatch(/home|omdb|localSearch|scrap/i);
  });

  it('skips exhausted transient discovery pages without inventing fallback results', async () => {
    const results = await processRecommendation({} as any, request, {
      tmdb: fakeTmdb({ fail: true }), interpret: async () => interpreted,
    });
    expect(results).toEqual([]);
  });

  it('still propagates a non-retryable TMDB credential failure', async () => {
    await expect(processRecommendation({} as any, request, {
      tmdb: fakeTmdb({ authFail: true }), interpret: async () => interpreted,
    })).rejects.toMatchObject({ code: 'TMDB_AUTH_FAILED', retryable: false });
  });

  it('returns a true empty pool for a narrow no-result request', async () => {
    const results = await processRecommendation({} as any, { ...request, query: 'nonexistent narrow fixture' }, {
      tmdb: fakeTmdb({ empty: true }), interpret: async () => interpreted,
    });
    expect(results).toEqual([]);
  });

  it('uses the canonical Breaking Bad anchor directly, excludes it, and ranks Better Call Saul first', async () => {
    let remaining = 80;
    const similarTmdb = {
      get callsRemaining() { return remaining; },
      genres: async () => ({ genres: [{ id: 18, name: 'Drama' }, { id: 80, name: 'Crime' }, { id: 16, name: 'Animation' }] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async (_type: string, _id: number, page: number) => ({
        page, total_pages: 1, total_results: page === 1 ? 2 : 0,
        results: page === 1 ? [
          { id: 60059, name: 'Better Call Saul', overview: 'A crime lawyer in Albuquerque', genre_ids: [80, 18], vote_average: 8.7, vote_count: 6000 },
          { id: 1396, name: 'Breaking Bad', overview: 'The anchor', genre_ids: [80, 18], vote_average: 8.9, vote_count: 15000 },
        ] : [],
      }),
      similar: async (_type: string, _id: number, page: number) => ({
        page, total_pages: 1, total_results: page === 1 ? 1 : 0,
        results: page === 1 ? [{ id: 999, name: 'Unrelated Anime', overview: 'Animated fantasy', genre_ids: [16], vote_average: 9, vote_count: 9000 }] : [],
      }),
      discover: async () => { remaining--; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      details: async (_type: string, id: number) => {
        remaining--;
        if (id === 1396) return { id, name: 'Breaking Bad', genres: [{ id: 80, name: 'Crime' }, { id: 18, name: 'Drama' }], keywords: { results: [] } };
        if (id === 60059) return { id, name: 'Better Call Saul', overview: 'A crime lawyer in Albuquerque', genres: [{ id: 80, name: 'Crime' }, { id: 18, name: 'Drama' }], vote_average: 8.7, vote_count: 6000 };
        return { id, name: 'Unrelated Anime', overview: 'Animated fantasy', genres: [{ id: 16, name: 'Animation' }], vote_average: 9, vote_count: 9000 };
      },
    };
    const similarRequest = RecommendationRequestSchema.parse({
      ...request,
      requestId: '00000000-0000-4000-8000-000000000099',
      mode: 'similar',
      query: 'series similar to Breaking Bad',
      mediaType: 'tv',
      anchor: { tmdbId: 1396, title: 'Breaking Bad', mediaType: 'tv' },
    });
    const results = await processRecommendation({} as any, similarRequest, {
      tmdb: similarTmdb,
      interpret: async () => ({ ...interpreted, requiredConceptGroups: [], genreHints: [], toneAndMood: [] }),
      embed: async () => { throw new Error('embedding outage'); },
    });
    expect(results[0]?.title).toBe('Better Call Saul');
    expect(results.some(item => item.tmdbId === 1396)).toBe(false);
    expect(results.every(item => item.mediaType === 'tv')).toBe(true);
    expect(results.slice(0, 1).some(item => item.title === 'Unrelated Anime')).toBe(false);
  });

  it('bounds a broad TMDB candidate pool before enrichment', async () => {
    let remaining = 220;
    const largePoolTmdb = {
      get callsRemaining() { return remaining; },
      genres: async () => ({ genres: [] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchTitle: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      details: async (_type: string, id: number) => {
        remaining--;
        return { id, title: `TMDB Movie ${id}`, overview: 'A genuine TMDB catalogue title' };
      },
      discover: async (_type: string, params: Record<string, string | number | boolean | undefined>) => {
        remaining--;
        const page = Number(params.page || 1);
        const sort = String(params.sort_by || 'vote_count.desc');
        const offset = sort === 'popularity.desc' ? 100_000
          : sort === 'primary_release_date.desc' ? 200_000
            : sort === 'vote_average.desc' ? 300_000
              : 0;
        const results = Array.from({ length: 20 }, (_, index) => {
          const id = offset + (page - 1) * 20 + index + 1;
          return {
            id,
            title: `TMDB Movie ${id}`,
            overview: 'A genuine TMDB catalogue title',
            genre_ids: [],
            vote_average: 7.4,
            vote_count: 2_500,
          };
        });
        return { page, total_pages: 500, total_results: 10_000, results };
      },
    };
    const broadIntent: InterpretedIntent = {
      hardFilters: { originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [] },
      requiredConceptGroups: [], softConcepts: [], excludedConcepts: [], genreHints: [], toneAndMood: [], broadSearchPhrases: [],
    };

    const results = await processRecommendation({} as any, { ...request, query: 'surprise me' }, {
      tmdb: largePoolTmdb,
      interpret: async () => broadIntent,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(results).toHaveLength(360);
    expect(new Set(results.map(item => `${item.mediaType}:${item.tmdbId}`)).size).toBe(360);
    expect(results.every(item => item.retrievalSources.some(source => source.startsWith('discover:')))).toBe(true);
  });
});
