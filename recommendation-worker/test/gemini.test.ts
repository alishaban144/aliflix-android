import { describe, expect, it } from 'vitest';
import { fallbackIntentFromQuery } from '../src/gemini';

describe('deterministic Gemini fallback', () => {
  it('keeps independent ideas as required AND groups and extracts exclusions', () => {
    const intent = fallbackIntentFromQuery('A detective in a small town investigates a cold case with supernatural powers, but no zombies');

    expect(intent.requiredConceptGroups.map(group => group.label)).toEqual(expect.arrayContaining([
      'detective', 'small town', 'cold case', 'supernatural powers',
    ]));
    expect(intent.requiredConceptGroups.every(group => group.synonyms.length <= 2)).toBe(true);
    expect(intent.excludedConcepts).toContain('zombies');
    expect(intent.genreHints).toEqual(expect.arrayContaining(['Crime', 'Mystery']));
  });

  it('does not turn generic request language into match concepts', () => {
    const intent = fallbackIntentFromQuery('Please recommend a movie or series, surprise me');

    expect(intent.requiredConceptGroups).toEqual([]);
  });
});
