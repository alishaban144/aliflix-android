import { describe, expect, it } from 'vitest';
import { processRecommendation } from '../src/engine';
import { ParsedRecommendationRequest, RecommendationRequestSchema } from '../src/schemas';
import { InterpretedIntent, PremiseCandidateDocument, ServiceError } from '../src/types';
import { applyTmdbAuthentication } from '../src/tmdb';

const request: ParsedRecommendationRequest = {
  requestId: '00000000-0000-4000-8000-000000000001', mode: 'filters', query: 'funny movies', mediaType: 'movie', pageSize: 20,
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
  it('accepts the three user-selectable AI models', () => {
    expect(RecommendationRequestSchema.parse({
      ...request,
      geminiModel: 'gemini-3.7-flash',
    }).geminiModel).toBe('gemini-3.7-flash');
    expect(RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'gemini-3.5-flash',
    }).aiModel).toBe('gemini-3.5-flash');
    expect(RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-qwen-3.8-27b',
    }).aiModel).toBe('groq-qwen-3.8-27b');
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-unapproved',
    })).toThrow();
  });

  it('rejects conflicting current and legacy model fields', () => {
    expect(() => RecommendationRequestSchema.parse({
      ...request,
      aiModel: 'groq-qwen-3.8-27b',
      geminiModel: 'gemini-3.5-flash',
    })).toThrow('aiModel and legacy geminiModel must match');
  });

  it('routes one request through the explicitly selected model', async () => {
    let routedModel: string | undefined;
    await processRecommendation({ GEMINI_GENERATION_MODEL: 'gemini-3.5-flash' } as any, {
      ...request,
      aiModel: 'groq-qwen-3.8-27b',
    }, {
      tmdb: fakeTmdb({ empty: true }),
      interpret: async env => {
        routedModel = env.AI_GENERATION_MODEL;
        return interpreted;
      },
    });
    expect(routedModel).toBe('groq-qwen-3.8-27b');
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

  it('uses AI to retrieve and independently verify Similar candidates, excluding the canonical anchor', async () => {
    let recommendationCalls = 0;
    let similarCalls = 0;
    let discoverCalls = 0;
    const similarTmdb = {
      callsRemaining: 80,
      genres: async () => ({ genres: [{ id: 18, name: 'Drama' }, { id: 80, name: 'Crime' }, { id: 16, name: 'Animation' }] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchTitle: async (_type: string, title: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: title === 'Better Call Saul'
          ? [{ id: 60059, name: title, first_air_date: '2015-02-08', overview: 'A compromised lawyer descends into Albuquerque crime.' }]
          : title === 'Breaking Bad'
            ? [{ id: 1396, name: title, first_air_date: '2008-01-20', overview: 'The anchor.' }]
            : [{ id: 999, name: title, first_air_date: '2020-01-01', overview: 'Animated fantasy.' }],
      }),
      recommendations: async () => { recommendationCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      similar: async () => { similarCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      discover: async () => { discoverCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      details: async (_type: string, id: number) => {
        if (id === 1396) return { id, name: 'Breaking Bad', first_air_date: '2008-01-20', overview: 'A teacher becomes a drug kingpin.', genres: [{ id: 80, name: 'Crime' }, { id: 18, name: 'Drama' }], keywords: { results: [{ id: 1, name: 'moral decline' }] } };
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
      tmdb: similarTmdb as any,
      recommendSimilar: async () => [
        { title: 'Better Call Saul', releaseYear: 2015, confidence: .97, reason: 'A morally compromised Albuquerque protagonist descends into crime.' },
        { title: 'Breaking Bad', releaseYear: 2008, confidence: .99, reason: 'The anchor itself.' },
        { title: 'Unrelated Anime', releaseYear: 2020, confidence: .69, reason: 'It is also dramatic.' },
      ],
      assessSimilarity: async (_env, _anchors, _refinement, _type, candidates) => acceptedAssessments(candidates),
    });
    expect(results[0]?.title).toBe('Better Call Saul');
    expect(results.some(item => item.tmdbId === 1396)).toBe(false);
    expect(results.every(item => item.mediaType === 'tv')).toBe(true);
    expect(results.slice(0, 1).some(item => item.title === 'Unrelated Anime')).toBe(false);
    expect(results[0].retrievalSources).toEqual(expect.arrayContaining([
      'gemini:similar-recommendation', 'gemini:similarity-verification', 'tmdb:identity-search', 'tmdb:details',
    ]));
    expect({ recommendationCalls, similarCalls, discoverCalls }).toEqual({ recommendationCalls: 0, similarCalls: 0, discoverCalls: 0 });
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
    expect(discoverCalls.filter(params => params.with_keywords !== undefined).length).toBeGreaterThan(0);
    expect(discoverCalls.some(params => String(params.with_keywords).split(',').length === 2)).toBe(true);
  });

  it('uses the same AI similarity standard for cross-media anchors without incompatible TMDB endpoints', async () => {
    let recommendationCalls = 0;
    let similarCalls = 0;
    let discoverCalls = 0;
    const crossMediaTmdb = {
      callsRemaining: 120,
      genres: async () => ({ genres: [{ id: 18, name: 'Drama' }] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchTitle: async (type: string, title: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{ id: 900, title, release_date: '2019-01-01', overview: 'A cartel lawyer faces moral collapse.' }],
      }),
      recommendations: async () => { recommendationCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      similar: async () => { similarCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      discover: async () => { discoverCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      details: async (type: string, id: number) => id === 1396
        ? { id, name: 'TV Anchor', overview: 'A teacher enters the drug trade', genres: [{ id: 18, name: 'Drama' }], keywords: { results: [{ id: 1, name: 'drug trade' }, { id: 2, name: 'moral decline' }, { id: 3, name: 'cartel' }] } }
        : { id, title: 'Movie Counterpart', overview: 'A cartel lawyer drama', genres: [{ id: 18, name: 'Drama' }], keywords: { keywords: [{ id: 1, name: 'drug trade' }, { id: 2, name: 'moral decline' }, { id: 3, name: 'cartel' }] }, vote_average: 8, vote_count: 900 },
    };
    const similarRequest = RecommendationRequestSchema.parse({
      ...request,
      mode: 'similar', query: 'movies blending TV Anchor', mediaType: 'movie',
      anchor: { tmdbId: 1396, title: 'TV Anchor', mediaType: 'tv' },
    });
    const results = await processRecommendation({} as any, similarRequest, {
      tmdb: crossMediaTmdb as any,
      recommendSimilar: async (_env, anchors, type) => {
        expect(anchors).toHaveLength(1);
        expect(anchors[0]).toMatchObject({ title: 'TV Anchor', mediaType: 'tv' });
        expect(type).toBe('movie');
        return [{ title: 'Movie Counterpart', releaseYear: 2019, confidence: .92, reason: 'A crime-world moral collapse centered on a cartel lawyer.' }];
      },
      assessSimilarity: async (_env, _anchors, _refinement, _type, candidates) => acceptedAssessments(candidates),
    });

    expect(recommendationCalls).toBe(0);
    expect(similarCalls).toBe(0);
    expect(discoverCalls).toBe(0);
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

  it('rejects the unrelated title-word match Abduction after inspecting authoritative TMDB plot evidence', async () => {
    const seedTmdb = {
      callsRemaining: 120,
      genres: async () => ({ genres: [{ id: 878, name: 'Science Fiction' }] }),
      searchTitle: async (_type: string, title: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{
          id: title === 'Fire in the Sky' ? 1 : title === 'The Fourth Kind' ? 3 : 2,
          title,
           release_date: title === 'Fire in the Sky' ? '1993-03-12' : title === 'The Fourth Kind' ? '2009-11-06' : '2011-09-22',
           overview: title === 'Fire in the Sky'
            ? 'A logger disappears after an encounter with an extraterrestrial craft and returns with memories of abduction.'
            : title === 'The Fourth Kind'
              ? 'A psychologist investigates patients whose hypnosis sessions reveal recurring extraterrestrial abductions.'
              : 'A teenager discovers his childhood photo on a missing persons website and uncovers a spy conspiracy.',
          genre_ids: [878], vote_average: 7, vote_count: 500,
        }],
      }),
      searchKeyword: async (term: string) => term === 'alien abduction'
        ? { page: 1, total_pages: 1, total_results: 1, results: [{ id: 99, name: 'alien abduction' }] }
        : { page: 1, total_pages: 0, total_results: 0, results: [] },
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      recommendations: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      similar: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async () => ({
        page: 1,
        total_pages: 1,
        total_results: 3,
        results: [
          { id: 1, title: 'Fire in the Sky', release_date: '1993-03-12', overview: 'A logger recounts an extraterrestrial abduction.', genre_ids: [878] },
          { id: 3, title: 'The Fourth Kind', release_date: '2009-11-06', overview: 'Hypnosis reveals extraterrestrial abductions.', genre_ids: [878] },
          { id: 4, title: 'Communion', release_date: '1989-11-10', overview: 'A writer experiences recurring apparent alien abductions.', genre_ids: [878] },
        ],
      }),
      details: async (_type: string, id: number) => id === 1
        ? { id, title: 'Fire in the Sky', release_date: '1993-03-12', overview: 'A logger disappears after an encounter with an extraterrestrial craft and returns with memories of abduction.', genres: [{ id: 878, name: 'Science Fiction' }], keywords: { keywords: [{ id: 10, name: 'alien' }, { id: 11, name: 'abduction' }] }, vote_average: 7, vote_count: 500 }
        : id === 3
          ? { id, title: 'The Fourth Kind', release_date: '2009-11-06', overview: 'A psychologist investigates patients whose hypnosis sessions reveal recurring extraterrestrial abductions.', genres: [{ id: 878, name: 'Science Fiction' }], keywords: { keywords: [{ id: 10, name: 'alien' }, { id: 11, name: 'abduction' }] }, vote_average: 6.3, vote_count: 1800 }
          : id === 4
            ? { id, title: 'Communion', release_date: '1989-11-10', overview: 'A writer experiences recurring apparent alien abductions.', genres: [{ id: 878, name: 'Science Fiction' }], keywords: { keywords: [{ id: 10, name: 'alien' }, { id: 11, name: 'abduction' }] }, vote_average: 5.7, vote_count: 500 }
            : { id, title: 'Abduction', release_date: '2011-09-22', overview: 'A teenager discovers his childhood photo on a missing persons website and uncovers a spy conspiracy.', genres: [{ id: 28, name: 'Action' }], keywords: { keywords: [{ id: 12, name: 'spy' }] }, vote_average: 5.9, vote_count: 500 },
    };

    let verifiedDocuments: PremiseCandidateDocument[] = [];
    const results = await processRecommendation({} as any, { ...request, mode: 'describe', query: 'movies about alien abduction' }, {
      tmdb: seedTmdb,
      recommendDescribe: async () => [
        { title: 'Fire in the Sky', releaseYear: 1993, confidence: .96, reason: 'A logger is abducted by extraterrestrials.' },
        { title: 'The Fourth Kind', releaseYear: 2009, confidence: .58, reason: 'Hypnosis reveals recurring extraterrestrial abductions.' },
        { title: 'Abduction', releaseYear: 2011, confidence: .99, reason: 'The title appears to match alien abduction.' },
      ],
      assessPremise: async (_env, query, _type, groups, candidates) => {
        expect(query).toBe('movies about alien abduction');
        expect(groups.map(group => group.label)).toEqual(expect.arrayContaining(['alien', 'abduction']));
        verifiedDocuments = candidates;
        return candidates.map(candidate => candidate.title === 'Abduction'
          ? { index: candidate.index, relevanceScore: .04, matchedGroupIndexes: [], reason: 'Its abduction is a spy conspiracy, not extraterrestrial kidnapping.' }
          : candidate.title === 'The Fourth Kind'
            ? { index: candidate.index, relevanceScore: .94, matchedGroupIndexes: [0, 1], reason: 'Hypnosis evidence of repeated extraterrestrial abductions drives the investigation.' }
            : candidate.title === 'Communion'
              ? { index: candidate.index, relevanceScore: .93, matchedGroupIndexes: [0, 1], reason: 'A writer repeatedly experiences apparent extraterrestrial abductions.' }
              : { index: candidate.index, relevanceScore: .97, matchedGroupIndexes: [0, 1], reason: 'Extraterrestrials abduct a logger and his account drives the story.' });
      },
    });

    expect(results.map(result => result.title)).toEqual(['Fire in the Sky', 'The Fourth Kind', 'Communion']);
    expect(results.find(result => result.title === 'Communion')?.retrievalSources).toContain('tmdb:keyword-supplement');
    expect(verifiedDocuments.find(candidate => candidate.title === 'Abduction')?.overview).toContain('spy conspiracy');
    expect(results[0].matchReasons[0]).toContain('Extraterrestrials abduct a logger');
    expect(results[0].retrievalSources).toEqual(expect.arrayContaining([
      'gemini:describe-recommendation', 'gemini:premise-verification', 'tmdb:identity-search', 'tmdb:details',
    ]));
  });

  it('uses one quota-bounded generation pass and returns the requested verified page', async () => {
    let generationPasses = 0;
    let verificationPasses = 0;
    let titleSearches = 0;
    let detailCalls = 0;
    const expandedTmdb = {
      ...fakeTmdb(),
      callsRemaining: 100,
      searchTitle: async (_type: string, title: string) => {
        titleSearches++;
        const id = Number(title.replace('Genuine Match ', ''));
        return {
          page: 1, total_pages: 1, total_results: 1,
          results: [{ id, title, release_date: `${2000 + id}-01-01`, overview: 'The complete requested premise drives the story.' }],
        };
      },
      details: async (_type: string, id: number) => {
        detailCalls++;
        return {
          id,
          title: `Genuine Match ${id}`,
          release_date: `${2000 + id}-01-01`,
          overview: 'The complete requested premise drives the story.',
          genres: [{ id: 18, name: 'Drama' }],
          keywords: { keywords: [{ id, name: 'central premise' }] },
        };
      },
    };
    const results = await processRecommendation({} as any, {
      ...request, mode: 'describe', query: 'a precise premise with many genuine films', pageSize: 8,
    }, {
      tmdb: expandedTmdb as any,
      recommendDescribe: async (_env, _query, _type, _filters, excludedTitles, targetCount) => {
        generationPasses++;
        expect(excludedTitles).toEqual([]);
        expect(targetCount).toBe(8);
        const start = 1;
        const end = 12;
        return Array.from({ length: end - start + 1 }, (_, offset) => {
          const id = start + offset;
          return {
            title: `Genuine Match ${id}`,
            releaseYear: 2000 + id,
            confidence: .98 - id * .001,
            reason: 'The requested premise is central throughout the story.',
          };
        });
      },
      assessPremise: async (_env, _query, _type, _groups, candidates) => {
        verificationPasses++;
        return acceptedAssessments(candidates);
      },
    });

    expect(generationPasses).toBe(1);
    expect(verificationPasses).toBe(1);
    expect(titleSearches).toBe(8);
    expect(detailCalls).toBe(8);
    expect(results).toHaveLength(8);
    expect(results.every(result => result.retrievalSources.includes('tmdb:details'))).toBe(true);
  });

  it('reserves verifier capacity for both generated and TMDB-grounded candidates', async () => {
    const keywordDiscoverVariants = new Set<string>();
    const tmdb = {
      ...fakeTmdb(),
      callsRemaining: 100,
      searchTitle: async (_type: string, title: string) => {
        const id = Number(title.replace('Generated ', ''));
        return {
          page: 1, total_pages: 1, total_results: 1,
          results: [{
            id,
            title,
            release_date: `${2000 + id}-01-01`,
            overview: 'Aliens abduct people as the central story.',
            vote_count: 100 + id,
          }],
        };
      },
      searchKeyword: async (term: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{ id: 99, name: term }],
      }),
      discover: async (_type: string, params: Record<string, string | number>) => {
        keywordDiscoverVariants.add(`${params.sort_by || 'default'}:${params.page}`);
        return ({
          page: 1, total_pages: 1, total_results: 24,
          results: Array.from({ length: 24 }, (_, offset) => ({
            id: 101 + offset,
            title: `Keyword ${offset + 1}`,
            release_date: `${1990 + offset}-01-01`,
            overview: 'Aliens abduct people as the central story.',
            genre_ids: [878],
            vote_count: 2_000 - offset,
          })),
        });
      },
      details: async (_type: string, id: number) => ({
        id,
        title: id >= 101 ? `Keyword ${id - 100}` : `Generated ${id}`,
        release_date: id >= 101 ? `${1989 + id - 100}-01-01` : `${2000 + id}-01-01`,
        overview: 'Aliens abduct people as the central story.',
        genres: [{ id: 878, name: 'Science Fiction' }],
        keywords: { keywords: [{ id: 99, name: 'alien abduction' }] },
        vote_count: id >= 101 ? 2_000 - (id - 101) : 100 + id,
      }),
    };
    let verifiedTitles: string[] = [];
    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'describe',
      query: 'movies about alien abduction',
      pageSize: 8,
    }, {
      tmdb: tmdb as any,
      recommendDescribe: async () => Array.from({ length: 12 }, (_, offset) => ({
        title: `Generated ${offset + 1}`,
        releaseYear: 2001 + offset,
        confidence: .95,
        reason: 'Aliens abduct people as the central story.',
      })),
      assessPremise: async (_env, _query, _type, _groups, candidates) => {
        verifiedTitles = candidates.map(candidate => candidate.title);
        return candidates.map(candidate => ({
          index: candidate.index,
          relevanceScore: .90,
          matchedGroupIndexes: [0, 1],
          reason: 'The complete premise is supported by authoritative metadata.',
        }));
      },
    });

    expect(verifiedTitles).toHaveLength(20);
    expect(verifiedTitles.filter(title => title.startsWith('Generated '))).toHaveLength(4);
    expect(verifiedTitles.filter(title => title.startsWith('Keyword '))).toHaveLength(16);
    expect(keywordDiscoverVariants).toEqual(new Set(['default:1']));
    expect(results).toHaveLength(8);
    expect(results.every(result => !result.retrievalSources.includes('tmdb:premise-evidence-fallback'))).toBe(true);
  });

  it('retrieves deeper TMDB lanes for a fresh broad-query continuation batch', async () => {
    const discoverVariants: string[] = [];
    const tmdb = {
      ...fakeTmdb(),
      callsRemaining: 100,
      searchTitle: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchKeyword: async (term: string) => {
        const id = term.includes('ufo') ? 100 : term.includes('extraterrestrial') ? 101 : 99;
        return {
          page: 1, total_pages: 1, total_results: 1,
          results: [{ id, name: term }],
        };
      },
      discover: async (_type: string, params: Record<string, string | number>) => {
        const page = Number(params.page);
        const ranked = params.sort_by === 'vote_count.desc';
        const keywordId = Number(params.with_keywords);
        discoverVariants.push(`${keywordId}:${ranked ? 'vote' : 'default'}:${page}`);
        const lane = keywordId * 100_000 + (ranked ? 50_000 : 0);
        return {
          page, total_pages: 20, total_results: 400,
          results: Array.from({ length: 20 }, (_, index) => ({
            id: lane + page * 100 + index,
            title: `Alien UFO Match ${lane + page * 100 + index}`,
            release_date: '2020-01-01',
            overview: 'Aliens arrive in UFOs and drive the central story.',
            genre_ids: [878],
            vote_count: 2_000 - index,
          })),
        };
      },
      details: async (_type: string, id: number) => ({
        id,
        title: `Alien UFO Match ${id}`,
        release_date: '2020-01-01',
        overview: 'Aliens arrive in UFOs and drive the central story.',
        genres: [{ id: 878, name: 'Science Fiction' }],
        keywords: { keywords: [{ id: 99, name: 'alien' }, { id: 100, name: 'ufo' }] },
        vote_count: 1_000,
      }),
    };
    const broadRequest = {
      ...request,
      mode: 'describe' as const,
      query: 'movies about aliens and ufos',
      pageSize: 20,
    };
    const dependencies = {
      tmdb: tmdb as any,
      recommendDescribe: async () => [],
      assessPremise: async (_env: any, _query: string, _type: string, _groups: any[], candidates: any[]) => (
        candidates.map(candidate => ({
          index: candidate.index,
          relevanceScore: .91,
          matchedGroupIndexes: [0],
          reason: 'Aliens and UFO activity are central to the plot.',
        }))
      ),
    };

    const first = await processRecommendation({} as any, broadRequest, dependencies);
    const second = await processRecommendation({} as any, {
      ...broadRequest,
      filters: {
        ...broadRequest.filters,
        excludedTmdbIds: first.map(item => item.tmdbId),
        excludedTitles: first.map(item => item.title),
      },
    }, dependencies, { continuationPass: 1 });

    expect(first).toHaveLength(20);
    expect(second).toHaveLength(20);
    expect(new Set([...first, ...second].map(item => item.tmdbId)).size).toBe(40);
    expect(discoverVariants).toEqual(expect.arrayContaining([
      '99:default:1', '100:default:1', '101:default:1',
      '99:default:2', '100:default:2', '101:default:2',
    ]));
  });

  it('stabilizes a broad single-topic match with exact TMDB keyword and overview evidence', async () => {
    const works = [
      {
        id: 301,
        title: 'Central Alien Story',
        overview: 'An alien arrival changes humanity and drives the entire story.',
        keywords: [{ id: 99, name: 'alien' }],
      },
      {
        id: 302,
        title: 'Incidental Tag',
        overview: 'A family repairs its home after a severe winter storm.',
        keywords: [{ id: 99, name: 'alien' }],
      },
    ];
    const tmdb = {
      ...fakeTmdb(),
      callsRemaining: 100,
      searchTitle: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchKeyword: async (term: string) => ({
        page: 1, total_pages: 1, total_results: 1, results: [{ id: 99, name: term }],
      }),
      discover: async () => ({
        page: 1, total_pages: 1, total_results: works.length,
        results: works.map(work => ({ ...work, release_date: '2020-01-01', genre_ids: [878] })),
      }),
      details: async (_type: string, id: number) => {
        const work = works.find(item => item.id === id)!;
        return {
          ...work,
          release_date: '2020-01-01',
          genres: [{ id: 878, name: 'Science Fiction' }],
          keywords: { keywords: work.keywords },
        };
      },
    };

    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'describe',
      query: 'movies about aliens and ufos',
      pageSize: 20,
    }, {
      tmdb: tmdb as any,
      recommendDescribe: async () => [],
      assessPremise: async (_env, _query, _type, groups, candidates) => {
        expect(groups).toHaveLength(1);
        return candidates.map(candidate => ({
          index: candidate.index,
          relevanceScore: .55,
          matchedGroupIndexes: [],
          reason: 'The candidate does not contain every word in the query.',
        }));
      },
    });

    expect(results.map(result => result.title)).toEqual(['Central Alien Story']);
    expect(results[0].finalScore).toBe(.70);
    expect(results[0].retrievalSources).toContain('tmdb:exact-keyword-corroboration');
    expect(results[0].matchReasons).toEqual([
      'TMDB keywords and overview support the requested central topic.',
    ]);

    const verifierUnavailable = await processRecommendation({} as any, {
      ...request,
      mode: 'describe',
      query: 'movies about aliens and ufos',
      pageSize: 20,
    }, {
      tmdb: tmdb as any,
      recommendDescribe: async () => [],
      assessPremise: async () => {
        throw new ServiceError('GROQ_UNAVAILABLE', 'Verifier timed out', 504, true);
      },
    });
    expect(verifierUnavailable.map(result => result.title)).toEqual(['Central Alien Story']);
    expect(verifierUnavailable[0].retrievalSources).toContain('tmdb:premise-evidence-fallback');
  });

  it('promotes only borderline verifier matches corroborated by exact compound metadata', async () => {
    const works = [
      {
        id: 201,
        title: 'Grounded Encounter',
        overview: 'Extraterrestrials abduct a family and hold them aboard a spacecraft.',
        keywords: [{ id: 99, name: 'alien abduction' }],
      },
      {
        id: 202,
        title: 'False Memory Story',
        overview: 'A teenager obsessed with alien abductions confronts a false memory of childhood abuse.',
        keywords: [{ id: 99, name: 'alien abduction' }],
      },
      {
        id: 203,
        title: 'Abduction',
        overview: 'A teenager uncovers a spy conspiracy after seeing his childhood photo.',
        keywords: [{ id: 12, name: 'spy' }],
      },
      {
        id: 204,
        title: 'Misapplied Tag',
        overview: 'Fantasy musicians tour different kingdoms to reunite feuding tribes.',
        keywords: [{ id: 99, name: 'alien abduction' }],
      },
      {
        id: 728526,
        title: 'Encounter',
        overview: 'A father takes his sons on the road to escape an unhuman threat.',
        keywords: [{ id: 99, name: 'alien abduction' }],
      },
    ];
    const tmdb = {
      ...fakeTmdb(),
      callsRemaining: 100,
      searchTitle: async (_type: string, title: string) => {
        const work = works.find(item => item.title === title)!;
        return {
          page: 1, total_pages: 1, total_results: 1,
          results: [{
            id: work.id,
            title: work.title,
            release_date: '2020-01-01',
            overview: work.overview,
          }],
        };
      },
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      details: async (_type: string, id: number) => {
        const work = works.find(item => item.id === id)!;
        return {
          id: work.id,
          title: work.title,
          release_date: '2020-01-01',
          overview: work.overview,
          genres: [{ id: 878, name: 'Science Fiction' }],
          keywords: { keywords: work.keywords },
        };
      },
    };

    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'describe',
      query: 'movies about alien abduction',
      pageSize: 5,
    }, {
      tmdb: tmdb as any,
      recommendDescribe: async () => works.map(work => ({
        title: work.title,
        releaseYear: 2020,
        confidence: .95,
        reason: 'Possible match requiring independent verification.',
      })),
      assessPremise: async (_env, _query, _type, _groups, candidates) => candidates.map(candidate => ({
        index: candidate.index,
        relevanceScore: candidate.title === 'Encounter' ? .95 : .12,
        matchedGroupIndexes: [0, 1],
        reason: candidate.title === 'Grounded Encounter'
          ? 'Exact alien abduction metadata confirms the central event.'
          : candidate.title === 'False Memory Story'
            ? 'Alien abduction is framed as a false memory.'
            : candidate.title === 'Abduction'
              ? 'No actual alien abduction occurs.'
              : 'The supplied keyword is the only apparent connection.',
      })),
    });

    expect(results.map(result => result.title)).toEqual(['Grounded Encounter']);
    expect(results[0].finalScore).toBe(.70);
    expect(results[0].retrievalSources).toContain('tmdb:exact-keyword-corroboration');
    expect(results[0].retrievalSources).toContain('gemini:premise-verification');
  });

  it('forwards displayed-title exclusions and rejects their TMDB identities', async () => {
    const exclusionTmdb = {
      callsRemaining: 40,
      searchTitle: async (_type: string, title: string) => ({
        page: 1,
        total_pages: 1,
        total_results: 1,
        results: [{
          id: title === 'Already Shown' ? 7 : 8,
          title,
          release_date: '2020-01-01',
          overview: 'The requested premise is central.',
        }],
      }),
      details: async (_type: string, id: number) => ({
        id,
        title: id === 7 ? 'Already Shown' : 'Fresh Match',
        release_date: '2020-01-01',
        overview: 'The requested premise is central.',
        genres: [],
        keywords: { keywords: [] },
      }),
    };
    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'describe',
      query: 'a precise premise',
      filters: {
        ...request.filters,
        excludedTmdbIds: [7],
        excludedTitles: ['Already Shown'],
      },
    }, {
      tmdb: exclusionTmdb as any,
      recommendDescribe: async (_env, _query, _type, _filters, excludedTitles) => {
        expect(excludedTitles).toEqual(['Already Shown']);
        return [
          { title: 'Already Shown', releaseYear: 2020, confidence: .98, reason: 'Duplicate.' },
          { title: 'Fresh Match', releaseYear: 2020, confidence: .97, reason: 'Fresh genuine match.' },
        ];
      },
      assessPremise: async (_env, _query, _type, _groups, candidates) => acceptedAssessments(candidates),
    });
    expect(results.map(result => result.tmdbId)).toEqual([8]);
  });

  it('uses TMDB series status only for eligibility and never changes premise relevance', async () => {
    const statusTmdb = {
      ...fakeTmdb(),
      searchTitle: async (_type: string, title: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{
          id: title === 'Still Running' ? 10 : 20,
          name: title,
          first_air_date: title === 'Still Running' ? '2022-01-01' : '2018-01-01',
          overview: 'People are trapped in a mysterious place they cannot escape.',
        }],
      }),
      details: async (_type: string, id: number) => ({
        id,
        name: id === 10 ? 'Still Running' : 'Completed Mystery',
        first_air_date: id === 10 ? '2022-01-01' : '2018-01-01',
        overview: 'People are trapped in a mysterious place they cannot escape.',
        status: id === 10 ? 'Returning Series' : 'Ended',
        genres: [{ id: 9648, name: 'Mystery' }],
        keywords: { results: [] },
      }),
    };
    const generated = [
      { title: 'Still Running', releaseYear: 2022, confidence: .98, reason: 'The characters cannot leave the mysterious location.' },
      { title: 'Completed Mystery', releaseYear: 2018, confidence: .82, reason: 'The characters cannot leave the mysterious location.' },
    ];
    const tvDescribe = { ...request, mode: 'describe' as const, mediaType: 'tv' as const };

    const unrestricted = await processRecommendation({} as any, tvDescribe, {
      tmdb: statusTmdb,
      recommendDescribe: async () => generated,
      assessPremise: async (_env, _query, _type, _groups, candidates) => acceptedAssessments(candidates),
    });
    const endedOnly = await processRecommendation({} as any, {
      ...tvDescribe,
      filters: { ...request.filters, seriesStatus: 'ended' as const },
    }, {
      tmdb: statusTmdb,
      recommendDescribe: async () => generated,
      assessPremise: async (_env, _query, _type, _groups, candidates) => acceptedAssessments(candidates),
    });

    expect(endedOnly.map(result => result.title)).toEqual(['Completed Mystery']);
    expect(endedOnly[0].status).toBe('Ended');
    expect(endedOnly[0].finalScore).toBe(
      unrestricted.find(result => result.title === 'Completed Mystery')?.finalScore,
    );
  });

  it('falls back to strict TMDB retrieval after a retryable Describe provider failure without another Gemini call', async () => {
    let keywordCalls = 0;
    let discoverCalls = 0;
    let detailCalls = 0;
    let embeddingCalls = 0;
    const tmdb = {
      ...fakeTmdb(),
      searchKeyword: async () => {
        keywordCalls++;
        return { page: 1, total_pages: 0, total_results: 0, results: [] };
      },
      discover: async () => {
        discoverCalls++;
        return {
          page: 1, total_pages: 1, total_results: 12,
          results: Array.from({ length: 12 }, (_, offset) => ({
            id: 7 + offset,
            title: `Funny Fixture ${offset + 1}`,
            overview: 'A funny comedy',
            genre_ids: [35],
            vote_average: 7.2,
            vote_count: 500 - offset,
          })),
        };
      },
      details: async (_type: string, id: number) => {
        detailCalls++;
        return { id, title: `Funny Fixture ${id - 6}`, overview: 'A funny comedy', genres: [{ id: 35, name: 'Comedy' }] };
      },
    };
    const failure = new ServiceError('GEMINI_UNAVAILABLE', 'Gemini timed out', 504, true);

    const results = await processRecommendation({} as any, { ...request, mode: 'describe', pageSize: 5 }, {
      tmdb,
      recommendDescribe: async () => { throw failure; },
      embed: async () => {
        embeddingCalls++;
        throw new Error('fallback must not call Gemini embeddings');
      },
    });

    expect(keywordCalls).toBe(0);
    expect(discoverCalls).toBeGreaterThan(0);
    expect(detailCalls).toBeGreaterThan(0);
    expect(embeddingCalls).toBe(0);
    expect(results).toHaveLength(5);
    expect(results.map(result => result.title)).toEqual([
      'Funny Fixture 1',
      'Funny Fixture 2',
      'Funny Fixture 3',
      'Funny Fixture 4',
      'Funny Fixture 5',
    ]);
    expect(results[0].retrievalSources.some(source => source.startsWith('discover:genre-hints'))).toBe(true);
  });

  it('returns only TMDB-grounded fallback matches when the final verifier is unavailable', async () => {
    let discoverCalls = 0;
    const verificationTmdb = {
      ...fakeTmdb(),
      searchTitle: async (_type: string, title: string) => ({
        page: 1,
        total_pages: 1,
        total_results: 1,
        results: [{
          id: title === 'Fire in the Sky' ? 44 : 45,
          title,
          release_date: title === 'Fire in the Sky' ? '1993-03-12' : '2011-09-22',
          overview: title === 'Fire in the Sky'
            ? 'A logger is abducted by extraterrestrials.'
            : 'A teenager uncovers a spy conspiracy after seeing his childhood photo.',
        }],
      }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      discover: async () => {
        discoverCalls++;
        return { page: 1, total_pages: 0, total_results: 0, results: [] };
      },
      details: async (_type: string, id: number) => id === 44
        ? {
            id,
            title: 'Fire in the Sky',
            release_date: '1993-03-12',
            overview: 'A logger is abducted by extraterrestrials.',
            genres: [{ id: 878, name: 'Science Fiction' }],
            keywords: { keywords: [{ id: 1, name: 'alien abduction' }] },
          }
        : {
            id,
            title: 'Abduction',
            release_date: '2011-09-22',
            overview: 'A teenager uncovers a spy conspiracy after seeing his childhood photo.',
            genres: [{ id: 28, name: 'Action' }],
            keywords: { keywords: [{ id: 2, name: 'spy' }] },
          },
    };

    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'describe',
      query: 'movies about alien abduction',
    }, {
      tmdb: verificationTmdb,
      recommendDescribe: async () => [{
        title: 'Fire in the Sky', releaseYear: 1993, confidence: .96,
        reason: 'A logger is abducted by extraterrestrials.',
      }, {
        title: 'Abduction', releaseYear: 2011, confidence: .99,
        reason: 'The title appears to match alien abduction.',
      }],
      assessPremise: async () => {
        throw new ServiceError('GROQ_UNAVAILABLE', 'TPM limit reached', 503, true);
      },
    });
    expect(results.map(result => result.title)).toEqual(['Fire in the Sky']);
    expect(results[0].retrievalSources).toContain('tmdb:premise-evidence-fallback');
    expect(results[0].retrievalSources).not.toContain('groq:premise-verification');
    expect(discoverCalls).toBe(0);
  });

  it('returns a corroborated Similar match when the final verifier is unavailable', async () => {
    const tmdb = {
      ...fakeTmdb(),
      searchTitle: async () => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{
          id: 56,
          name: 'Looped Again',
          first_air_date: '2022-01-01',
          overview: 'A detective repeatedly relives the same day while solving a murder.',
        }],
      }),
      details: async (_type: string, id: number) => id === 55
        ? {
            id,
            name: 'Canonical Anchor',
            first_air_date: '2020-01-01',
            overview: 'A detective relives one day while investigating a murder.',
            genres: [{ id: 18, name: 'Drama' }, { id: 9648, name: 'Mystery' }],
            keywords: { results: [{ id: 9, name: 'time loop' }] },
          }
        : {
            id,
            name: 'Looped Again',
            first_air_date: '2022-01-01',
            overview: 'A detective repeatedly relives the same day while solving a murder.',
            genres: [{ id: 18, name: 'Drama' }, { id: 9648, name: 'Mystery' }],
            keywords: { results: [{ id: 9, name: 'time loop' }] },
          },
    };

    const results = await processRecommendation({} as any, {
      ...request,
      mode: 'similar',
      mediaType: 'tv',
      anchor: { tmdbId: 55, title: 'Canonical Anchor', mediaType: 'tv' },
    }, {
      tmdb: tmdb as any,
      recommendSimilar: async () => [{
        title: 'Looped Again',
        releaseYear: 2022,
        confidence: .94,
        reason: 'Both center time-loop detectives solving murders.',
      }],
      assessSimilarity: async () => {
        throw new ServiceError('GROQ_UNAVAILABLE', 'temporary verifier outage', 503, true);
      },
    });

    expect(results.map(result => result.title)).toEqual(['Looped Again']);
    expect(results[0].retrievalSources).toContain('tmdb:similarity-evidence-fallback');
  });

  it('does not hide a non-retryable Describe configuration failure behind fallback results', async () => {
    const failure = new ServiceError('GEMINI_UNAVAILABLE', 'Invalid Gemini request', 502, false);
    await expect(processRecommendation({} as any, { ...request, mode: 'describe' }, {
      tmdb: fakeTmdb(),
      recommendDescribe: async () => { throw failure; },
    })).rejects.toBe(failure);
  });

  it('propagates Gemini Similar failure without invoking broad TMDB retrieval fallback', async () => {
    let recommendationCalls = 0;
    let similarCalls = 0;
    let discoverCalls = 0;
    const tmdb = {
      ...fakeTmdb(),
      details: async (_type: string, id: number) => ({
        id, name: 'Canonical Anchor', first_air_date: '2020-01-01', overview: 'A distinctive central story.',
        genres: [{ id: 18, name: 'Drama' }], keywords: { results: [] },
      }),
      recommendations: async () => { recommendationCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      similar: async () => { similarCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
      discover: async () => { discoverCalls++; return { page: 1, total_pages: 0, total_results: 0, results: [] }; },
    };
    const failure = new ServiceError('GEMINI_UNAVAILABLE', 'Gemini timed out', 504, true);

    await expect(processRecommendation({} as any, {
      ...request,
      mode: 'similar', mediaType: 'tv',
      anchor: { tmdbId: 55, title: 'Canonical Anchor', mediaType: 'tv' },
    }, {
      tmdb: tmdb as any,
      recommendSimilar: async () => { throw failure; },
    })).rejects.toBe(failure);
    expect({ recommendationCalls, similarCalls, discoverCalls }).toEqual({ recommendationCalls: 0, similarCalls: 0, discoverCalls: 0 });
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

  it('requires AI Similar fusion across every canonical anchor', async () => {
    const multiAnchorTmdb = {
      callsRemaining: 50,
      genres: async () => ({ genres: [] }),
      searchKeyword: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchPerson: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchCompany: async () => ({ page: 1, total_pages: 0, total_results: 0, results: [] }),
      searchTitle: async (_type: string, title: string) => ({
        page: 1, total_pages: 1, total_results: 1,
        results: [{ id: 500, title, release_date: '2016-11-11', overview: 'A philosophical science-fiction mystery.' }],
      }),
      details: async (_type: string, id: number) => ({
        id,
        title: id === 101 ? 'Interstellar' : (id === 102 ? 'Blade Runner 2049' : 'Arrival'),
        release_date: id === 101 ? '2014-11-07' : id === 102 ? '2017-10-06' : '2016-11-11',
        overview: id === 101 ? 'Space travel shaped by time and family.' : id === 102 ? 'An artificial person investigates identity.' : 'First contact reshapes time and human identity.',
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
      recommendSimilar: async (_env, anchors) => {
        expect(anchors.map(anchor => anchor.title)).toEqual(['Interstellar', 'Blade Runner 2049']);
        return [{
          title: 'Arrival', releaseYear: 2016, confidence: .96,
          reason: 'It blends cerebral science fiction, nonlinear time, identity, and intimate human stakes.',
        }];
      },
      assessSimilarity: async (_env, anchors, _refinement, _type, candidates) => {
        expect(anchors).toHaveLength(2);
        return acceptedAssessments(candidates);
      },
    });

    expect(results.length).toBeGreaterThan(0);
    expect(results[0].tmdbId).toBe(500);
    expect(results[0].title).toBe('Arrival');
  });
});
