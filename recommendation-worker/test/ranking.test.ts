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
  key: `tv:${id}`, tmdbId: id, mediaType: 'tv', title, overview, originCountries: [], genreIds: [], genres: [],
  keywords: [], matchedKeywordIds: new Set(), matchedConceptGroupIndexes: new Set(),
  retrievalSources: new Set(['discover:test']), hardFiltersVerified: true,
  directRelationshipScore: 0, anchorOverlapScore: 0, matchReasons: [], tmdbRating: 7, tmdbVoteCount: 1000, ...overrides,
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
    ], intent([['serial killer']], hard), hard, false, false);
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
    ], intent([], hard), hard, false, false);
    expect(ranked.map(item => item.tmdbId)).toEqual([3]);
  });

  it('ranks Better Call Saul first for Breaking Bad similarity without embeddings and excludes the anchor fixture', () => {
    const items = [
      candidate(60059, 'Better Call Saul', 'A crime lawyer in Albuquerque', { directRelationshipScore: 1, anchorOverlapScore: .9, genres: ['Crime', 'Drama'], retrievalSources: new Set(['recommendations:page-1', 'similar:page-1']) }),
      candidate(1, 'Other Crime Drama', 'Crime drama', { directRelationshipScore: .7, anchorOverlapScore: .5 }),
      candidate(2, 'Another Drama', 'Drama', { directRelationshipScore: .5, anchorOverlapScore: .4 }),
    ];
    const ranked = rankCandidates(items, intent([]), filters(), true, false);
    expect(ranked[0].title).toBe('Better Call Saul');
    expect(ranked.some(item => item.tmdbId === 1396)).toBe(false);
    expect(ranked.every(item => item.mediaType === 'tv')).toBe(true);
  });

  it('supports kids/teenagers AND supernatural powers and romantic time-travel evidence', () => {
    const kids = rankCandidates([
      candidate(1, 'Powered Teens', 'Teenagers discover supernatural powers'),
      candidate(2, 'Ordinary Adults', 'Adults at work'),
    ], intent([['kids', 'teenagers'], ['supernatural powers', 'psychic ability']]), filters(), false, false);
    expect(kids.map(item => item.tmdbId)).toEqual([1]);
    const romance = rankCandidates([
      candidate(3, 'Across Time', 'A romantic couple use time travel', { mediaType: 'movie', key: 'movie:3', genres: ['Romance'] }),
    ], intent([['romantic', 'love'], ['time travel']], filters(), ['Romance']), filters(), false, false);
    expect(romance[0]?.title).toBe('Across Time');
  });

  it('accepts genuine TMDB keyword-group evidence when synopsis wording is different', () => {
    const ranked = rankCandidates([
      candidate(9, 'The Signal', 'A family faces an impossible night', {
        matchedKeywordIds: new Set([101, 202]),
        matchedConceptGroupIndexes: new Set([0, 1]),
        retrievalSources: new Set(['discover:concept-intersection:page-1']),
      }),
    ], intent([['teenagers'], ['supernatural powers']]), filters(), false, false);

    expect(ranked.map(item => item.tmdbId)).toEqual([9]);
    expect(ranked[0].matchReasons).toContain('Grounded in TMDB keyword data');
  });

  it('renormalizes deterministic signals when embeddings fail and pages broad relevant pools', () => {
    const funny = Array.from({ length: 45 }, (_, index) => candidate(index + 1, `Comedy ${index}`, 'A funny comedy adventure', { mediaType: 'movie', key: `movie:${index + 1}`, genres: ['Comedy'] }));
    const ranked = rankCandidates(funny, intent([['funny', 'comedy']], filters(), ['Comedy']), filters(), false, false);
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
      false,
      true,
    );

    expect(ranked).toHaveLength(2_500);
    expect(new Set(ranked.map(item => `${item.mediaType}:${item.tmdbId}`)).size).toBe(2_500);
    expect(ranked.some(item => item.tmdbId > 1_000)).toBe(true);
  });
});
