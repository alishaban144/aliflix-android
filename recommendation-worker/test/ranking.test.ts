import { describe, expect, it } from 'vitest';
import { buildKeywordExpressions, mergeFilters, rankCandidates } from '../src/ranking';
import { Candidate, InterpretedIntent, RecommendationFilters } from '../src/types';

const filters = (overrides: Partial<RecommendationFilters> = {}): RecommendationFilters => ({
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [], ...overrides,
});
const intent = (groups: string[][], hard = filters(), genreHints: string[] = []): InterpretedIntent => ({
  hardFilters: hard, requiredConceptGroups: groups.map((synonyms, index) => ({ label: `g${index}`, synonyms, weight: 1 })),
  softConcepts: [], excludedConcepts: [], excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
  genreHints, toneAndMood: [], broadSearchPhrases: [],
});
const candidate = (id: number, title: string, overview: string, overrides: Partial<Candidate> = {}): Candidate => ({
  key: `tv:${id}`, tmdbId: id, mediaType: 'tv', title, overview, originCountries: [], genreIds: [], genres: [], certifications: [],
  keywords: [], matchedKeywordIds: new Set(), matchedConceptGroupIndexes: new Set(),
  retrievalSources: new Set(['discover:test']), hardFiltersVerified: true, detailsLoaded: true,
  matchReasons: [], tmdbRating: 7, tmdbVoteCount: 1000, ...overrides,
});

describe('deterministic recommendation logic', () => {
  it('preserves OR within concept groups and AND between groups', () => {
    const expressions = buildKeywordExpressions([[1, 2, 3], [10, 11]], 8);
    expect(expressions).toContain('1|2|3,10|11');
    expect(expressions).toContain('1,10');
    expect(expressions).not.toContain('1,2,3,10,11');
  });

  it('keeps explicit hard filters strict and rejects contradictions', () => {
    const hard = filters({ minimumYear: 2021, originalLanguage: 'ko', originCountries: ['KR'], includedGenres: ['Crime'] });
    const ranked = rankCandidates([
      candidate(1, 'Korean Killer', 'A serial killer investigation', { releaseDate: '2022-01-01', originalLanguage: 'ko', originCountries: ['KR'], genres: ['Crime'] }),
      candidate(2, 'Old Killer', 'A serial killer investigation', { releaseDate: '2019-01-01', originalLanguage: 'ko', originCountries: ['KR'], genres: ['Crime'] }),
    ], intent([['serial killer']], hard), hard, false);
    expect(ranked.map(item => item.tmdbId)).toEqual([1]);
    expect(() => mergeFilters(filters({ includedGenres: ['Crime'], excludedGenres: ['crime'] }), filters())).toThrow('both included and excluded');
  });

  it('lets explicit UI filters override conflicting Gemini interpretation', () => {
    const merged = mergeFilters(
      filters({ minimumYear: 2010, maximumYear: 2020, originalLanguage: 'ko', includedGenres: ['Crime'] }),
      filters({ minimumYear: 2022, originalLanguage: 'en', includedGenres: ['Comedy'], excludedGenres: ['Crime'] }),
    );
    expect(merged).toMatchObject({
      minimumYear: 2010,
      maximumYear: 2020,
      originalLanguage: 'ko',
      includedGenres: ['Crime'],
      excludedGenres: [],
    });
  });

  it('rejects missing metadata when a hard filter requires verification', () => {
    const hard = filters({ minimumRuntimeMinutes: 45, excludedGenres: ['Animation'] });
    const ranked = rankCandidates([
      candidate(1, 'Unknown Runtime', 'A crime story', { genres: ['Crime'], runtimeMinutes: undefined, hardFiltersVerified: true }),
      candidate(2, 'Unknown Genres', 'A crime story', { genres: [], runtimeMinutes: 50, hardFiltersVerified: true }),
      candidate(3, 'Verified Match', 'A crime story', { genres: ['Crime'], runtimeMinutes: 50 }),
    ], intent([], hard), hard, false);
    expect(ranked.map(item => item.tmdbId)).toEqual([3]);
  });

  it('applies TMDB series status as a hard filter without adding relevance score', () => {
    const hard = filters({ seriesStatus: 'ended' });
    const ranked = rankCandidates([
      candidate(1, 'Ended Match', 'A funny comedy', { status: 'Ended' }),
      candidate(2, 'Returning Match', 'A funny comedy', { status: 'Returning Series' }),
      candidate(3, 'Unknown Match', 'A funny comedy'),
    ], intent([['funny', 'comedy']], hard), hard, false);

    expect(ranked.map(item => item.tmdbId)).toEqual([1]);
    expect(ranked[0].status).toBe('Ended');
    expect(ranked[0].matchReasons.join(' ')).not.toMatch(/ended|status/i);
    const unfiltered = rankCandidates([
      candidate(1, 'Ended Match', 'A funny comedy', { status: 'Ended' }),
    ], intent([['funny', 'comedy']]), filters(), false);
    expect(ranked[0].finalScore).toBe(unfiltered[0].finalScore);
  });

  it('supports kids/teenagers AND supernatural powers and romantic time-travel evidence', () => {
    const kids = rankCandidates([
      candidate(1, 'Powered Teens', 'Teenagers discover supernatural powers'),
      candidate(2, 'Ordinary Adults', 'Adults at work'),
    ], intent([['kids', 'teenagers'], ['supernatural powers', 'psychic ability']]), filters(), false);
    expect(kids.map(item => item.tmdbId)).toEqual([1]);
    const romance = rankCandidates([
      candidate(3, 'Across Time', 'A romantic couple use time travel', { mediaType: 'movie', key: 'movie:3', genres: ['Romance'] }),
    ], intent([['romantic', 'love'], ['time travel']], filters(), ['Romance']), filters(), false);
    expect(romance[0]?.title).toBe('Across Time');
  });

  it('accepts genuine TMDB keyword-group evidence when synopsis wording is different', () => {
    const ranked = rankCandidates([
      candidate(9, 'The Signal', 'A family faces an impossible night', {
        matchedKeywordIds: new Set([101, 202]),
        matchedConceptGroupIndexes: new Set([0, 1]),
        retrievalSources: new Set(['discover:concept-intersection:page-1']),
      }),
    ], intent([['teenagers'], ['supernatural powers']]), filters(), false);

    expect(ranked.map(item => item.tmdbId)).toEqual([9]);
    expect(ranked[0].matchReasons).toContain('Grounded in multiple TMDB keyword concepts');
  });

  it('matches safe singular and plural concept morphology in authoritative metadata', () => {
    const ranked = rankCandidates([
      candidate(1, 'Metadata Variant', 'A teenager develops a supernatural power in a small town'),
    ], intent([['teenagers'], ['supernatural powers'], ['small towns']]), filters(), false);

    expect(ranked.map(item => item.tmdbId)).toEqual([1]);
  });

  it('never treats words in a candidate title as topical evidence', () => {
    const ranked = rankCandidates([
      candidate(1, 'The Cold Case Detective', 'A cheerful cooking competition in a city restaurant'),
      candidate(2, 'Hidden Truths', 'A detective reopens a cold case in a corrupt small town'),
    ], intent([['detective'], ['cold case'], ['corruption', 'corrupt'], ['small town']]), filters(), false);

    expect(ranked.map(item => item.tmdbId)).toEqual([2]);
  });

  it('requires most independent concept groups instead of accepting two shared keywords', () => {
    const ranked = rankCandidates([
      candidate(1, 'Superficial Match', 'A detective visits a small town'),
      candidate(2, 'Grounded Match', 'A detective investigates a ritual murder in an isolated small town during winter'),
    ], intent([['detective'], ['ritual murder'], ['isolated'], ['small town'], ['winter']]), filters(), false);

    expect(ranked.map(item => item.tmdbId)).toEqual([2]);
  });

  it('collapses repeated pages into one retrieval path and preserves strict relevance order', () => {
    const repeated = candidate(1, 'Repeated Pages', 'A funny comedy', {
      semanticScore: .90,
      genres: ['Comedy'],
      retrievalSources: new Set(['discover:concept:page-1', 'discover:concept:page-2', 'discover:concept:page-3']),
    });
    const single = candidate(2, 'Single Page', 'A funny comedy', {
      semanticScore: .85,
      genres: ['Comedy'],
      retrievalSources: new Set(['discover:concept:page-1']),
    });
    const offGenre = candidate(3, 'Different Era', 'A funny comedy', {
      semanticScore: .84,
      genres: ['Documentary'],
      releaseDate: '1960-01-01',
    });
    const ranked = rankCandidates([repeated, single, offGenre], intent([['funny', 'comedy']]), filters(), true);

    expect(ranked.map(item => item.tmdbId)).toEqual([1, 2, 3]);
    expect(ranked[0].finalScore).toBeGreaterThan(ranked[1].finalScore);
  });

  it('enforces requested US age ratings from hydrated metadata', () => {
    const certificationIntent = { ...intent([]), certifications: ['PG-13'] };
    const ranked = rankCandidates([
      candidate(1, 'Wrong Rating', 'A filtered catalogue title', { certifications: ['R'] }),
      candidate(2, 'Right Rating', 'A filtered catalogue title', { certifications: ['PG-13'] }),
    ], certificationIntent, filters(), false);

    expect(ranked.map(item => item.tmdbId)).toEqual([2]);
    expect(ranked[0].matchReasons).toContain('Matches the requested age rating');
  });

  it('never admits an unreleased title even without an explicit year filter', () => {
    const nextYear = new Date().getUTCFullYear() + 1;
    const ranked = rankCandidates([
      candidate(1, 'Future Title', 'A funny comedy', { releaseDate: `${nextYear}-01-01`, genres: ['Comedy'] }),
      candidate(2, 'Released Title', 'A funny comedy', { releaseDate: '2020-01-01', genres: ['Comedy'] }),
    ], intent([['funny', 'comedy']], filters(), ['Comedy']), filters(), false);

    expect(ranked.map(item => item.tmdbId)).toEqual([2]);
  });

  it('renormalizes deterministic signals when embeddings fail and pages broad relevant pools', () => {
    const funny = Array.from({ length: 45 }, (_, index) => candidate(index + 1, `Comedy ${index}`, 'A funny comedy adventure', { mediaType: 'movie', key: `movie:${index + 1}`, genres: ['Comedy'] }));
    const ranked = rankCandidates(funny, intent([['funny', 'comedy']], filters(), ['Comedy']), filters(), false);
    expect(ranked).toHaveLength(45);
    expect(ranked.slice(20, 40)).toHaveLength(20);
    expect(ranked.every(item => item.finalScore > 0 && item.genres.includes('Comedy'))).toBe(true);
  });

  it('keeps deterministic relevance intact beyond the semantic reranking window', () => {
    const candidates = Array.from({ length: 2_500 }, (_, index) => candidate(
      index + 1,
      `Catalogue Comedy ${index + 1}`,
      'A funny comedy adventure grounded in the TMDB catalogue',
      {
        mediaType: 'movie',
        key: `movie:${index + 1}`,
        genres: ['Comedy'],
        semanticScore: index < 1_000 ? .72 : undefined,
      },
    ));
    const ranked = rankCandidates(
      candidates,
      intent([['funny', 'comedy']], filters(), ['Comedy']),
      filters(),
      true,
    );

    expect(ranked).toHaveLength(2_500);
    expect(new Set(ranked.map(item => `${item.mediaType}:${item.tmdbId}`)).size).toBe(2_500);
    expect(ranked.some(item => item.tmdbId > 1_000)).toBe(true);
  });
});
