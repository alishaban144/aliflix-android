import { describe, expect, it } from 'vitest';
import { editorialPremiseSeeds, editorialPremiseSeedYear } from '../src/premise-catalog';

describe('editorial premise retrieval anchors', () => {
  const cases = [
    ['movie', 'movies about alien abduction', 'Fire in the Sky', 'No One Will Save You'],
    ['movie', 'movies about natural disasters', 'The Impossible', 'Crawl'],
    ['movie', 'movies where kids or teenagers develop supernatural powers', 'Chronicle', 'The Darkest Minds'],
    ['tv', 'shows about people trapped in a mysterious place they cannot escape', 'From', 'Persons Unknown'],
    ['tv', 'shows about artificial intelligence or robots becoming conscious', 'Westworld', 'Pantheon'],
    ['tv', 'shows about teenagers or young people with supernatural powers', 'Stranger Things', "Marvel's Runaways"],
  ] as const;

  it.each(cases)('recognizes %s premise: %s', (mediaType, query, first, last) => {
    const seeds = editorialPremiseSeeds(query, mediaType);
    expect(seeds.length).toBeGreaterThanOrEqual(8);
    expect(seeds).toContain(first);
    expect(seeds).toContain(last);
  });

  it('does not inject anchors for an unrelated generic genre query', () => {
    expect(editorialPremiseSeeds('popular drama', 'movie')).toEqual([]);
  });

  it('recognizes premise paraphrases rather than only the gold query wording', () => {
    expect(editorialPremiseSeeds('films involving people kidnapped by extraterrestrials', 'movie')).toContain('Fire in the Sky');
    expect(editorialPremiseSeeds('tornado and tsunami catastrophe films', 'movie')).toContain('Twister');
    expect(editorialPremiseSeeds('teenagers gain psychic abilities', 'movie')).toContain('Chronicle');
    expect(editorialPremiseSeeds('strangers stranded in an unknown town', 'tv')).toContain('From');
    expect(editorialPremiseSeeds('androids that become self aware', 'tv')).toContain('Humans');
    expect(editorialPremiseSeeds('young people discover superpowers', 'tv')).toContain('Stranger Things');
  });

  it('year-disambiguates collision-prone canonical titles', () => {
    expect(editorialPremiseSeedYear('Freaks', 'movie')).toBe(2019);
    expect(editorialPremiseSeedYear('The Innocents', 'movie')).toBe(2021);
    expect(editorialPremiseSeedYear('Next', 'tv')).toBe(2020);
  });
});
