import { describe, expect, it } from 'vitest';
import { processRecommendation } from '../src/engine';
import { ParsedRecommendationRequest } from '../src/schemas';
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

function fakeTmdb(options: { fail?: boolean; empty?: boolean } = {}) {
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

  it('propagates a retryable TMDB failure instead of using a fallback', async () => {
    await expect(processRecommendation({} as any, request, { tmdb: fakeTmdb({ fail: true }), interpret: async () => interpreted }))
      .rejects.toMatchObject({ code: 'TMDB_UNAVAILABLE', retryable: true });
  });

  it('returns a true empty pool for a narrow no-result request', async () => {
    const results = await processRecommendation({} as any, { ...request, query: 'nonexistent narrow fixture' }, {
      tmdb: fakeTmdb({ empty: true }), interpret: async () => interpreted,
    });
    expect(results).toEqual([]);
  });
});
