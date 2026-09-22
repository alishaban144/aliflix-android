import { describe, expect, it } from 'vitest';
import { processRecommendation } from '../src/engine';
import { ParsedRecommendationRequest, RecommendationRequestSchema } from '../src/schemas';
import { InterpretedIntent, PremiseCandidateDocument, ServiceError } from '../src/types';
import { applyTmdbAuthentication } from '../src/tmdb';

const request: ParsedRecommendationRequest = {
  requestId: '00000000-0000-4000-8000-000000000001', mode: 'filters', query: 'funny movies', mediaType: 'movie', pageSize: 20,
  filters: {
    minimumYear: undefined, maximumYear: undefined, originalLanguage: undefined, originCountries: [],
    minimumRuntimeMinutes: undefined, maximumRuntimeMinutes: undefined, includedGenres: [], excludedGenres: [], productionCompanyIds: [],
    minimumTmdbRating: undefined, excludedTmdbIds: [], excludedTitles: [],
  },
};
const interpreted: InterpretedIntent = {
  hardFilters: { originCountries: [], includedGenres: [], excludedGenres: [], productionCompanyIds: [], excludedTmdbIds: [], excludedTitles: [] },
  requiredConceptGroups: [{ label: 'comedy', synonyms: ['funny', 'comedy'], weight: 1 }], softConcepts: [], excludedConcepts: [],
  excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
  genreHints: ['Comedy'], toneAndMood: ['funny'], broadSearchPhrases: [],
};

function acceptedAssessments(candidates: PremiseCandidateDocument[]) {
  return candidates.map(candidate => ({
    index: candidate.index,
    relevanceScore: candidate.aiConfidence,
    matchedGroupIndexes: [],
    reason: candidate.aiReason,
  }));
}

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

describe('AI-generated, TMDB-grounded recommendation engine', () => {
  it('accepts Gemini 3.8 Flash and optional Groq', () => {
    expect(RecommendationRequestSchema.parse({
      ...request,
      geminiModel: 'gemini-3.8-flash',
    }).geminiModel).toBe('gemini-3.8-flash');
    expect(RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'gemini-3.8-flash',
    }).aiModel).toBe('gemini-3.8-flash');
    expect(RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-gpt-oss-120b',
    }).aiModel).toBe('groq-gpt-oss-120b');
    expect(RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-qwen-3.8-27b',
    }).aiModel).toBe('groq-gpt-oss-120b');
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-unapproved',
    })).toThrow();
  });

  it('rejects conflicting current and legacy model fields', () => {
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-gpt-oss-120b',
      geminiModel: 'gemini-3.8-flash',
    })).toThrow('aiModel and legacy geminiModel must match');
  });

  it('routes one request through the explicitly selected model', async () => {
    let routedModel: string | undefined;
    await processRecommendation({ GEMINI_GENERATION_MODEL: 'gemini-3.8-flash' } as any, {
      ...request,
      aiModel: 'groq-gpt-oss-120b',
    }, {
      tmdb: fakeTmdb({ empty: true }),
      interpret: async env => {
        routedModel = env.AI_GENERATION_MODEL;
        return interpreted;
      },
    });
    expect(routedModel).toBe('groq-gpt-oss-120b');
  });

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
      hardFilters: { originCountries: [], includedGenres: [], excludedGenres: [], productionCompanyIds: [], excludedTmdbIds: [], excludedTitles: [] },
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
    expect(discoverCalls.filter(params => params.with_keywords !== undefined).length).toBeGreaterThan(0);
    expect(discoverCalls.some(params => String(params.with_keywords).split(',').length === 2)).toBe(true);
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

  it('rejects a TV-only seriesStatus filter on movie requests', () => {
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      filters: { seriesStatus: 'ended' },
    })).toThrow('seriesStatus is only valid for TV recommendations');
  });

  it('rejects runtime sorting for TV requests', () => {
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      mediaType: 'tv',
      filters: { sortBy: 'runtime_short_to_long' },
    })).toThrow('runtime_short_to_long is only valid for movie recommendations');
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

  it('uses a semantic synonym after ignoring duplicate hyphen and spacing variants', async () => {
    const searchedTerms: string[] = [];
    const synonymTmdb = {
      callsRemaining: 100,
      genres: async () => ({ genres: [] }),
      searchKeyword: async (term: string) => {
        searchedTerms.push(term);
        return term === 'psychic ability'
          ? { page: 1, total_pages: 1, total_results: 1, results: [{ id: 303, name: 'psychic ability' }] }
          : { page: 1, total_pages: 0, total_results: 0, results: [] };
      },
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async () => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{ id: 303, title: 'Supernatural Fixture', overview: 'Metadata wording differs', vote_average: 7.5, vote_count: 700 }],
      }),
      details: async () => ({
        id: 303, title: 'Supernatural Fixture', overview: 'Metadata wording differs',
        keywords: { keywords: [{ id: 303, name: 'psychic ability' }] }, genres: [], vote_average: 7.5, vote_count: 700,
      }),
    };

    const results = await processRecommendation({} as any, { ...request, query: 'supernatural powers' }, {
      tmdb: synonymTmdb as any,
      interpret: async () => ({
        ...interpreted,
        requiredConceptGroups: [{
          label: 'supernatural powers',
          synonyms: ['supernatural powers', 'supernatural-powers'],
          weight: 1,
        }],
        genreHints: [],
      }),
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(searchedTerms).toEqual(['supernatural powers', 'psychic ability']);
    expect(results.map(item => item.tmdbId)).toEqual([303]);
  });

  it('merges duplicate genre concepts and omits narrative connector verbs', async () => {
    let keywordCalls = 0;
    const discoverCalls: Array<Record<string, string | number | boolean | undefined>> = [];
    const cleanedTmdb = {
      callsRemaining: 100,
      genres: async () => ({ genres: [{ id: 35, name: 'Comedy' }] }),
      searchKeyword: async () => { keywordCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async (_type: string, params: Record<string, string | number | boolean | undefined>) => {
        discoverCalls.push(params);
        return {
          page: 1, total_pages: 1, total_results: 1,
          results: [{ id: 35, title: 'Real Comedy', overview: 'A joyful night out', genre_ids: [35], vote_average: 7.4, vote_count: 800 }],
        };
      },
      details: async () => ({
        id: 35, title: 'Real Comedy', overview: 'A joyful night out', genres: [{ id: 35, name: 'Comedy' }],
        keywords: { keywords: [] }, vote_average: 7.4, vote_count: 800,
      }),
    };
    const noisyIntent: InterpretedIntent = {
      ...interpreted,
      requiredConceptGroups: [
        { label: 'funny', synonyms: ['funny'], weight: 1 },
        { label: 'comedy', synonyms: ['comedy'], weight: 1 },
        { label: 'solving', synonyms: ['solving'], weight: 1 },
      ],
      genreHints: ['Comedy'],
      broadSearchPhrases: ['funny', 'comedy'],
    };

    const results = await processRecommendation({} as any, { ...request, query: 'funny comedy solving' }, {
      tmdb: cleanedTmdb as any,
      interpret: async () => noisyIntent,
      embed: async () => { throw new Error('embedding outage'); },
    });

    expect(keywordCalls).toBe(0);
    expect(discoverCalls.some(params => params.with_genres === '35' && params.with_keywords === undefined)).toBe(true);
    expect(results.map(item => item.tmdbId)).toEqual([35]);
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


});
