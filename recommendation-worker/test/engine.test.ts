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
  excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
  genreHints: ['Comedy'], toneAndMood: ['funny'], broadSearchPhrases: [],
};

function fakeTmdb(options: { fail?: boolean; authFail?: boolean; empty?: boolean } = {}) {
  let remaining = 40;
  return {
    get callsRemaining() { return remaining; },
    genres: async () => ({ genres: [{ id: 35, name: 'Comedy' }] }),
    searchKeyword: async () => ({ page: 1, total_pages: 1, total_results: 1, results: [{ id: 99, name: 'comedy' }] }),
    searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
    searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
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
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
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
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
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
      requiredConceptGroups: [], softConcepts: [], excludedConcepts: [],
      excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
      genreHints: [], toneAndMood: [], broadSearchPhrases: [],
    };

    const results = await processRecommendation({} as any, { ...request, query: 'surprise me' }, {
      tmdb: largePoolTmdb,
      interpret: async () => broadIntent,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(results).toHaveLength(48);
    expect(new Set(results.map(item => `${item.mediaType}:${item.tmdbId}`)).size).toBe(48);
    expect(results.every(item => item.retrievalSources.some(source => source.startsWith('discover:')))).toBe(true);
  });

  it('grounds every resolved group for a multi-keyword TMDB intersection', async () => {
    const discoverCalls: Array<Record<string, string | number | boolean | undefined>> = [];
    const groundedTmdb = {
      callsRemaining: 100,
      genres: async () => ({ genres: [] }),
      searchKeyword: async (term: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{ id: term.includes('detective') ? 11 : term.includes('ritual') ? 22 : 33, name: term }],
      }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async (_type: string, params: Record<string, string | number | boolean | undefined>) => {
        discoverCalls.push(params);
        return { page: 1, total_pages: 1, total_results: 1, results: [{ id: 77, title: 'Grounded Fixture', overview: 'Metadata wording differs', vote_average: 7.5, vote_count: 400 }] };
      },
      details: async () => ({ id: 77, title: 'Grounded Fixture', overview: 'Metadata wording differs', keywords: { keywords: [] }, genres: [] }),
    };
    const detailedIntent: InterpretedIntent = {
      ...interpreted,
      requiredConceptGroups: [
        { label: 'detective', synonyms: ['detective'], weight: 1 },
        { label: 'ritual murder', synonyms: ['ritual murder'], weight: 1 },
        { label: 'isolated', synonyms: ['isolated'], weight: 1 },
      ],
      genreHints: [],
    };

    const results = await processRecommendation({} as any, { ...request, query: 'detective ritual murder isolated' }, {
      tmdb: groundedTmdb as any,
      interpret: async () => detailedIntent,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(results.map(item => item.tmdbId)).toEqual([77]);
    expect(results[0].matchReasons).toContain('Grounded in multiple TMDB keyword concepts');
    expect(discoverCalls.every(params => params.with_keywords !== undefined)).toBe(true);
    expect(discoverCalls.some(params => String(params.with_keywords).split(',').length === 2)).toBe(true);
  });

  it('translates cross-media anchors through keywords and target genre names without calling incompatible endpoints', async () => {
    let recommendationCalls = 0;
    let similarCalls = 0;
    const discoverCalls: Array<{ type: string; params: Record<string, string | number | boolean | undefined> }> = [];
    const crossMediaTmdb = {
      callsRemaining: 120,
      genres: async () => ({ genres: [{ id: 18, name: 'Drama' }] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => { recommendationCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      similar: async () => { similarCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      discover: async (type: string, params: Record<string, string | number | boolean | undefined>) => {
        discoverCalls.push({ type, params });
        return { page: 1, total_pages: 1, total_results: 1, results: [{ id: 900, title: 'Movie Counterpart', overview: 'A cartel lawyer drama', genre_ids: [18], vote_average: 8, vote_count: 900 }] };
      },
      details: async (type: string, id: number) => id === 1396
        ? { id, name: 'TV Anchor', overview: 'A teacher enters the drug trade', genres: [{ id: 18, name: 'Drama' }], keywords: { results: [{ id: 1, name: 'drug trade' }, { id: 2, name: 'moral decline' }, { id: 3, name: 'cartel' }] } }
        : { id, title: 'Movie Counterpart', overview: 'A cartel lawyer drama', genres: [{ id: 18, name: 'Drama' }], keywords: { keywords: [{ id: 1, name: 'drug trade' }, { id: 2, name: 'moral decline' }, { id: 3, name: 'cartel' }] }, vote_average: 8, vote_count: 900 },
    };
    const similarRequest = RecommendationRequestSchema.parse({
      ...request,
      mode: 'similar', query: 'movies blending TV Anchor', mediaType: 'movie',
      anchor: { tmdbId: 1396, title: 'TV Anchor', mediaType: 'tv' },
    });
    let interpretedQuery: string | undefined;

    const results = await processRecommendation({} as any, similarRequest, {
      tmdb: crossMediaTmdb as any,
      interpret: async (_env, query) => { interpretedQuery = query; return { ...interpreted, requiredConceptGroups: [], genreHints: [] }; },
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(interpretedQuery).toBe('');
    expect(recommendationCalls).toBe(0);
    expect(similarCalls).toBe(0);
    expect(discoverCalls.length).toBeGreaterThan(0);
    expect(discoverCalls.every(call => call.type === 'movie' && call.params.with_genres === '18' && String(call.params.with_keywords).includes(','))).toBe(true);
    expect(results.map(item => `${item.mediaType}:${item.tmdbId}`)).toEqual(['movie:900']);
  });

  it('never returns candidates whose authoritative detail hydration failed', async () => {
    const unavailableDetails = {
      ...fakeTmdb(),
      details: async () => { throw new ServiceError('TMDB_UNAVAILABLE', 'detail timeout', 503, true); },
    };

    const results = await processRecommendation({} as any, request, {
      tmdb: unavailableDetails,
      interpret: async () => interpreted,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(results).toEqual([]);
  });

  it('counts every TMDB keyword lookup against the discovery-search cap', async () => {
    let keywordCalls = 0;
    const cappedTmdb = {
      callsRemaining: 120,
      genres: async () => ({ genres: [] }),
      searchKeyword: async () => { keywordCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      details: async () => ({ id: 1, title: 'Unused' }),
    };
    const manySynonyms: InterpretedIntent = {
      ...interpreted,
      requiredConceptGroups: Array.from({ length: 8 }, (_, group) => ({
        label: `concept-${group}`,
        synonyms: Array.from({ length: 12 }, (_, synonym) => `term-${group}-${synonym}`),
        weight: 1,
      })),
      broadSearchPhrases: ['extra one', 'extra two', 'extra three'],
      genreHints: [],
    };

    await processRecommendation({} as any, { ...request, query: 'many independent ideas' }, {
      tmdb: cappedTmdb as any,
      interpret: async () => manySynonyms,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(keywordCalls).toBeLessThanOrEqual(18);
  });

  it('does not ground concepts with fuzzy TMDB keyword search results', async () => {
    const keywordDiscoveries: string[] = [];
    const fuzzyTmdb = {
      callsRemaining: 100,
      genres: async () => ({ genres: [] }),
      searchKeyword: async () => ({ page: 1, total_pages: 1, total_results: 1, results: [{ id: 999, name: 'unrelated keyword' }] }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async (_type: string, params: Record<string, string | number | boolean | undefined>) => {
        if (params.with_keywords) keywordDiscoveries.push(String(params.with_keywords));
        return { page: 1, total_pages: 0, total_results: 0, results: [] };
      },
      details: async () => ({ id: 1, title: 'Unused' }),
    };

    await processRecommendation({} as any, { ...request, query: 'precise obscure concept' }, {
      tmdb: fuzzyTmdb as any,
      interpret: async () => ({ ...interpreted, requiredConceptGroups: [{ label: 'precise', synonyms: ['precise'], weight: 1 }], genreHints: [] }),
    });

    expect(keywordDiscoveries).toEqual([]);
  });

  it('grounds safe singular and plural variants without enabling fuzzy matches', async () => {
    const keywordDiscoveries: string[] = [];
    const morphologyTmdb = {
      callsRemaining: 100,
      genres: async () => ({ genres: [] }),
      searchKeyword: async () => ({
        page: 1, total_pages: 1, total_results: 2,
        results: [{ id: 101, name: 'teenager' }, { id: 999, name: 'teenage comedy' }],
      }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async (_type: string, params: Record<string, string | number | boolean | undefined>) => {
        if (params.with_keywords) keywordDiscoveries.push(String(params.with_keywords));
        return {
          page: 1, total_pages: 1, total_results: 1,
          results: [{ id: 101, title: 'Grounded Teen Story', overview: 'Metadata wording differs', vote_average: 7.2, vote_count: 500 }],
        };
      },
      details: async () => ({
        id: 101, title: 'Grounded Teen Story', overview: 'Metadata wording differs',
        keywords: { keywords: [{ id: 101, name: 'teenager' }] }, genres: [], vote_average: 7.2, vote_count: 500,
      }),
    };

    const results = await processRecommendation({} as any, { ...request, query: 'teenagers' }, {
      tmdb: morphologyTmdb as any,
      interpret: async () => ({
        ...interpreted,
        requiredConceptGroups: [{ label: 'teenagers', synonyms: ['teenagers'], weight: 1 }],
        genreHints: [],
      }),
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(keywordDiscoveries.length).toBeGreaterThan(0);
    expect(keywordDiscoveries.every(expression => expression === '101')).toBe(true);
    expect(results.map(item => item.tmdbId)).toEqual([101]);
  });

  it('caps TMDB discovery at the current date', async () => {
    const maximumDates: string[] = [];
    const datedTmdb = {
      ...fakeTmdb(),
      discover: async (_type: string, params: Record<string, string | number | boolean | undefined>) => {
        const maximum = params['primary_release_date.lte'];
        if (maximum) maximumDates.push(String(maximum));
        return { page: 1, total_pages: 0, total_results: 0, results: [] };
      },
    };

    await processRecommendation({} as any, { ...request, mode: 'filters', query: '' }, {
      tmdb: datedTmdb,
      interpret: async () => ({ ...interpreted, requiredConceptGroups: [], genreHints: [], toneAndMood: [] }),
    });

    expect(maximumDates.length).toBeGreaterThan(0);
    expect(maximumDates.every(date => date <= new Date().toISOString().slice(0, 10))).toBe(true);
  });

  it('supports multi-anchor movie fusion in similar mode', async () => {
    const multiAnchorTmdb = {
      callsRemaining: 50,
      genres: async () => ({ genres: [] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      details: async (_type: string, id: number) => ({
        id,
        title: id === 101 ? 'Interstellar' : (id === 102 ? 'Blade Runner 2049' : 'Arrival'),
        genres: [{ id: 878, name: 'Science Fiction' }],
        keywords: { keywords: id === 101 ? [{ id: 9715, name: 'space' }] : [{ id: 4048, name: 'cyberpunk' }] },
      }),
      recommendations: async (_type: string, id: number) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{ id: 500, title: 'Arrival', overview: 'A philosophical sci-fi film', genre_ids: [878], vote_average: 8.0, vote_count: 5000 }],
      }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
    };

    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'similar',
      anchors: [
        { tmdbId: 101, title: 'Interstellar', mediaType: 'movie' },
        { tmdbId: 102, title: 'Blade Runner 2049', mediaType: 'movie' },
      ],
    }, {
      tmdb: multiAnchorTmdb as any,
      interpret: async () => interpreted,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(results.length).toBeGreaterThan(0);
    expect(results[0].tmdbId).toBe(500);
    expect(results[0].title).toBe('Arrival');
  });
});
