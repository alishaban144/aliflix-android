import { describe, expect, it } from 'vitest';
import {
  processFilterDiscoveryPage,
  supportsDirectFilterPagination,
} from '../src/engine';
import { ParsedRecommendationRequest } from '../src/schemas';
import { RecommendationEnv, TmdbListItem } from '../src/types';

const filtersRequest = (overrides: Partial<ParsedRecommendationRequest> = {}): ParsedRecommendationRequest => ({
  requestId: '00000000-0000-4000-8000-000000000001',
  mode: 'filters',
  query: '',
  mediaType: 'tv',
  filters: {
    minimumYear: undefined,
    maximumYear: undefined,
    originalLanguage: undefined,
    originCountries: [],
    minimumRuntimeMinutes: undefined,
    maximumRuntimeMinutes: undefined,
    includedGenres: ['Animation', 'Sci-Fi & Fantasy'],
    excludedGenres: [],
    minimumTmdbRating: undefined,
    productionCompanyIds: [],
    seriesStatus: undefined,
    sortBy: 'most_popular',
    excludedTmdbIds: [],
    excludedTitles: [],
  },
  pageSize: 24,
  ...overrides,
});

const namedEnglishMatches: TmdbListItem[] = [
  { id: 86340, name: 'Undone' },
  { id: 195339, name: 'Pantheon' },
  { id: 228878, name: 'Common Side Effects' },
  { id: 261145, name: 'The Amazing Digital Circus' },
].map((item, index) => ({
  ...item,
  original_language: 'en',
  origin_country: ['US'],
  genre_ids: [16, 10765],
  first_air_date: `${2019 + index}-01-01`,
  vote_average: 8.1,
  vote_count: 400 - index,
}));

function catalogue(): TmdbListItem[] {
  const japanese = Array.from({ length: 90 }, (_, index): TmdbListItem => ({
    id: index + 1,
    name: `Anime ${index + 1}`,
    original_language: 'ja',
    origin_country: ['JP'],
    genre_ids: [16, 10765],
    first_air_date: '2020-01-01',
    vote_average: 8,
    vote_count: 1_000 - index,
  }));
  const english = [
    ...namedEnglishMatches,
    ...Array.from({ length: 26 }, (_, index): TmdbListItem => ({
      id: 300_000 + index,
      name: `English Animation ${index + 1}`,
      original_language: 'en',
      origin_country: ['US'],
      genre_ids: [16, 10765],
      first_air_date: '2021-01-01',
      vote_average: 7.5,
      vote_count: 350 - index,
    })),
  ];
  return [...japanese, ...english];
}

function fakeTmdb(items: TmdbListItem[]) {
  let callsUsed = 0;
  const discoverParams: Array<Record<string, string | number | boolean | undefined>> = [];
  return {
    get callsRemaining() { return 38 - callsUsed; },
    discoverParams,
    genres: async () => ({ genres: [
      { id: 16, name: 'Animation' },
      { id: 10765, name: 'Sci-Fi & Fantasy' },
    ] }),
    discover: async (_mediaType: 'movie' | 'tv', params: Record<string, string | number | boolean | undefined>) => {
      callsUsed++;
      discoverParams.push(params);
      const page = Number(params.page || 1);
      return {
        page,
        results: items.slice((page - 1) * 20, page * 20),
        total_pages: Math.ceil(items.length / 20),
        total_results: items.length,
      };
    },
  };
}

describe('direct TMDB filter pagination', () => {
  it('uses the direct path only for plain structured filters', () => {
    expect(supportsDirectFilterPagination(filtersRequest())).toBe(true);
    expect(supportsDirectFilterPagination(filtersRequest({ query: 'dark animation' }))).toBe(false);
    expect(supportsDirectFilterPagination(filtersRequest({
      filters: { ...filtersRequest().filters, seriesStatus: 'returning' },
    }))).toBe(true);
  });

  it('pages Ended and Returning Series through TMDB Discover status filters', async () => {
    const endedTmdb = fakeTmdb(catalogue());
    const ended = await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      filtersRequest({ filters: { ...filtersRequest().filters, seriesStatus: 'ended' } }),
      0,
      { tmdb: endedTmdb },
    );
    expect(ended.totalResults).toBe(120);
    expect(ended.results).toHaveLength(24);
    expect(ended.results.every(result => result.status === 'Ended')).toBe(true);
    expect(endedTmdb.discoverParams.every(params => params.with_status === 3)).toBe(true);

    const returningTmdb = fakeTmdb(catalogue());
    const returning = await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      filtersRequest({ filters: { ...filtersRequest().filters, seriesStatus: 'returning' } }),
      0,
      { tmdb: returningTmdb },
    );
    expect(returning.results.every(result => result.status === 'Returning Series')).toBe(true);
    expect(returningTmdb.discoverParams.every(params => params.with_status === 0)).toBe(true);
  });

  it('keeps every TMDB row reachable in the selected TMDB sort order', async () => {
    const items = catalogue();
    const firstTmdb = fakeTmdb(items);
    const first = await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      filtersRequest(),
      0,
      { tmdb: firstTmdb },
    );

    expect(first.totalResults).toBe(120);
    expect(first.results).toHaveLength(24);
    expect(first.nextOffset).toBe(24);
    expect(first.results.filter(item => item.originalLanguage === 'ja')).toHaveLength(24);
    expect(firstTmdb.discoverParams.map(params => params.page)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(firstTmdb.discoverParams.every(params => params.with_genres === '16,10765')).toBe(true);
    expect(firstTmdb.discoverParams.every(params => params['vote_count.gte'] === undefined)).toBe(true);

    const allIds: number[] = [];
    let offset: number | null = 0;
    while (offset !== null) {
      const page = await processFilterDiscoveryPage(
        {} as RecommendationEnv,
        filtersRequest(),
        offset,
        { tmdb: fakeTmdb(items) },
      );
      allIds.push(...page.results.map(item => item.tmdbId));
      offset = page.nextOffset;
    }
    expect(allIds).toHaveLength(120);
    expect(new Set(allIds).size).toBe(120);
    expect(new Set(allIds)).toEqual(new Set(items.map(item => item.id)));
  });

  it.each([
    ['most_popular', 'popularity.desc'],
    ['highest_rated', 'vote_average.desc'],
    ['most_voted', 'vote_count.desc'],
    ['newest_first', 'first_air_date.desc'],
    ['oldest_first', 'first_air_date.asc'],
  ] as const)('maps %s to TMDB Discover sort %s', async (sortBy, expectedSort) => {
    const tmdb = fakeTmdb(catalogue());
    await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      filtersRequest({ filters: { ...filtersRequest().filters, sortBy } }),
      0,
      { tmdb },
    );

    expect(tmdb.discoverParams.every(params => params.sort_by === expectedSort)).toBe(true);
  });

  it('applies selected production company IDs to every comprehensive TMDB page', async () => {
    const tmdb = fakeTmdb(catalogue());
    const result = await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      filtersRequest({
        filters: {
          ...filtersRequest().filters,
          productionCompanyIds: [41077, 420],
        },
      }),
      0,
      { tmdb },
    );

    expect(result.results).toHaveLength(24);
    expect(tmdb.discoverParams).toHaveLength(6);
    expect(tmdb.discoverParams.every(params => params.with_companies === '41077|420')).toBe(true);
  });

  it('paginates movies in exact ascending runtime order across duration groups', async () => {
    const items = [
      ...Array.from({ length: 30 }, (_, index) => ({
        id: 500_000 + index,
        title: `Short Movie ${index + 1}`,
        runtime: 70,
        genre_ids: [16],
        release_date: '2020-01-01',
        vote_average: 7,
        vote_count: 100,
      })),
      ...Array.from({ length: 30 }, (_, index) => ({
        id: 600_000 + index,
        title: `Long Movie ${index + 1}`,
        runtime: 95,
        genre_ids: [16],
        release_date: '2020-01-01',
        vote_average: 7,
        vote_count: 100,
      })),
    ];
    let callsUsed = 0;
    const runtimeTmdb = {
      get callsRemaining() { return 100 - callsUsed; },
      genres: async () => ({ genres: [{ id: 16, name: 'Animation' }] }),
      discover: async (_mediaType: 'movie' | 'tv', params: Record<string, string | number | boolean | undefined>) => {
        callsUsed++;
        const minimum = Number(params['with_runtime.gte'] || 1);
        const maximum = Number(params['with_runtime.lte'] || 1_000);
        const matching = items.filter(item => item.runtime >= minimum && item.runtime <= maximum);
        const page = Number(params.page || 1);
        return {
          page,
          results: matching.slice((page - 1) * 20, page * 20),
          total_pages: Math.ceil(matching.length / 20),
          total_results: matching.length,
        };
      },
    };
    const runtimeRequest = filtersRequest({
      mediaType: 'movie',
      filters: {
        ...filtersRequest().filters,
        includedGenres: ['Animation'],
        sortBy: 'runtime_short_to_long',
      },
    });

    const first = await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      runtimeRequest,
      0,
      { tmdb: runtimeTmdb },
    );
    const second = await processFilterDiscoveryPage(
      {} as RecommendationEnv,
      runtimeRequest,
      first.nextOffset!,
      { tmdb: runtimeTmdb },
    );

    expect(first.results).toHaveLength(24);
    expect(first.results.every(item => item.runtimeMinutes === 70)).toBe(true);
    expect(second.results).toHaveLength(24);
    expect(second.results.slice(0, 6).every(item => item.runtimeMinutes === 70)).toBe(true);
    expect(second.results.slice(6).every(item => item.runtimeMinutes === 95)).toBe(true);
    expect(second.nextOffset).toBe(48);
  });
});
