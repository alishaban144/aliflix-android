import { describe, expect, it } from 'vitest';
import { buildKeywordExpressions, mergeFilters, rankCandidates } from '../src/ranking';
import { Candidate, InterpretedIntent, RecommendationFilters } from '../src/types';

const filters = (overrides: Partial<RecommendationFilters> = {}): RecommendationFilters => ({
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [], ...overrides,
});
const intent = (groups: string[][], hard = filters(), genreHints: string[] = []): InterpretedIntent => ({
  hardFilters: hard, requiredConceptGroups: groups.map((synonyms, index) => ({ label: `g${index}`, synonyms, weight: 1 })),
  softConcepts: [], excludedConcepts: [], genreHints, toneAndMood: [], broadSearchPhrases: [],
});
const candidate = (id: number, title: string, overview: string, overrides: Partial<Candidate> = {}): Candidate => ({
  key: `tv:${id}`, tmdbId: id, mediaType: 'tv', title, overview, originCountries: [], genreIds: [], genres: [],
  keywords: [], matchedKeywordIds: new Set(), retrievalSources: new Set(['discover:test']), hardFiltersVerified: true,
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
    expect(() => mergeFilters(filters({ includedGenres: ['Crime'] }), filters({ excludedGenres: ['crime'] }))).toThrow('both included and excluded');
  });

  it('ranks Better Call Saul in the top three for Breaking Bad similarity without embeddings and excludes the anchor fixture', () => {
    const items = [
      candidate(60059, 'Better Call Saul', 'A crime lawyer in Albuquerque', { directRelationshipScore: 1, anchorOverlapScore: .9, genres: ['Crime', 'Drama'], retrievalSources: new Set(['recommendations:page-1', 'similar:page-1']) }),
      candidate(1, 'Other Crime Drama', 'Crime drama', { directRelationshipScore: .7, anchorOverlapScore: .5 }),
      candidate(2, 'Another Drama', 'Drama', { directRelationshipScore: .5, anchorOverlapScore: .4 }),
    ];
    const ranked = rankCandidates(items, intent([]), filters(), true, false);
    expect(ranked.slice(0, 3).map(item => item.title)).toContain('Better Call Saul');
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

  it('renormalizes deterministic signals when embeddings fail and pages broad relevant pools', () => {
    const funny = Array.from({ length: 45 }, (_, index) => candidate(index + 1, `Comedy ${index}`, 'A funny comedy adventure', { mediaType: 'movie', key: `movie:${index + 1}`, genres: ['Comedy'] }));
    const ranked = rankCandidates(funny, intent([['funny', 'comedy']], filters(), ['Comedy']), filters(), false, false);
    expect(ranked).toHaveLength(45);
    expect(ranked.slice(20, 40)).toHaveLength(20);
    expect(ranked.every(item => item.finalScore > 0 && item.genres.includes('Comedy'))).toBe(true);
  });
});
