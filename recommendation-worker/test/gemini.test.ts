import { describe, expect, it } from 'vitest';
import { GeminiDescribeResponseSchema } from '../src/schemas';
import { DESCRIBE_RECOMMENDATIONS_PROMPT } from '../src/prompts';

describe('Gemini Describe contract', () => {
  it('requires real title identity, release year, premise confidence, and rationale', () => {
    expect(GeminiDescribeResponseSchema.parse({
      recommendations: [{
        title: 'Fire in the Sky',
        releaseYear: 1993,
        confidence: .97,
        reason: 'An extraterrestrial abduction is the central event.',
      }],
    }).recommendations).toHaveLength(1);
    expect(GeminiDescribeResponseSchema.safeParse({ recommendations: [] }).success).toBe(false);
    expect(GeminiDescribeResponseSchema.safeParse({
      recommendations: [{ title: 'Invented', confidence: .9, reason: 'Missing identity year' }],
    }).success).toBe(false);
  });

  it('forbids popularity padding and treats series status only as eligibility', () => {
    expect(DESCRIBE_RECOMMENDATIONS_PROMPT).toContain('Return fewer instead of padding');
    expect(DESCRIBE_RECOMMENDATIONS_PROMPT).toContain('Returning/Ended status is eligibility only');
    expect(DESCRIBE_RECOMMENDATIONS_PROMPT).toContain('Use your knowledge of the actual story');
  });
});
