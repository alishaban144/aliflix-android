import { describe, expect, it, vi } from 'vitest';
import {
  CLOUDFLARE_GENERATION_MAX_TOKENS,
  CLOUDFLARE_MODEL,
  recommendDescribeTitlesWithCloudflare,
  recommendSimilarTitlesWithCloudflare,
} from '../src/cloudflare';

function recommendations(count: number, prefix = 'Title') {
  return Array.from({ length: count }, (_, index) => ({
    title: `${prefix} ${index + 1}`,
    releaseYear: 2000 + index,
    rating: 9.8 - index * 0.1,
  }));
}

describe('Cloudflare Workers AI recommendations', () => {
  it('uses GPT-OSS 120B with low reasoning, structured output and a 24-title target', async () => {
    const run = vi.fn(async (_model: string, _input: any) => ({
      response: { recommendations: recommendations(24) },
    }));
    const results = await recommendDescribeTitlesWithCloudflare(
      { AI: { run }, AI_GENERATION_MODEL: 'cloudflare-gpt-oss-120b' } as any,
      'dark intelligent science fiction about identity and memory',
      'movie',
      {},
      [],
      24,
    );

    expect(results).toHaveLength(24);
    expect(run).toHaveBeenCalledTimes(1);
    const [model, input] = run.mock.calls[0];
    expect(model).toBe(CLOUDFLARE_MODEL);
    expect(input.reasoning_effort).toBe('low');
    expect(input.max_tokens).toBe(CLOUDFLARE_GENERATION_MAX_TOKENS);
    expect(input.temperature).toBeLessThanOrEqual(.5);
    expect(input.response_format.type).toBe('json_schema');
    expect(input.response_format.json_schema.properties.recommendations.minItems).toBe(20);
    expect(input.response_format.json_schema.properties.recommendations.maxItems).toBe(24);
    expect(input.messages[0].content).toContain('tone, atmosphere, pacing');
    expect(input.messages[0].content).toContain('Never repeat excludedTitles');
    expect(input.messages[1].content).toContain('"targetCount":24');
  });

  it('filters repeated exclusions locally so Find More cannot recycle prior titles', async () => {
    const run = vi.fn(async () => ({
      response: {
        recommendations: [
          { title: 'Arrival', releaseYear: 2016, rating: 9.8 },
          ...recommendations(23, 'Fresh'),
        ],
      },
    }));
    const results = await recommendDescribeTitlesWithCloudflare(
      { AI: { run } } as any,
      'dark science fiction',
      'movie',
      {},
      ['Arrival'],
      24,
    );
    expect(results.map(item => item.title)).not.toContain('Arrival');
    expect(results).toHaveLength(23);
  });

  it('applies the same low-token model path to Similar', async () => {
    const run = vi.fn(async (_model: string, _input: any) => ({
      response: { recommendations: recommendations(24, 'Similar') },
    }));
    const results = await recommendSimilarTitlesWithCloudflare(
      { AI: { run } } as any,
      [{
        tmdbId: 335984,
        mediaType: 'movie',
        title: 'Blade Runner 2049',
        releaseYear: 2017,
        overview: 'A replicant uncovers a secret about identity.',
        genres: ['Science Fiction', 'Drama'],
        keywords: ['replicant', 'artificial intelligence'],
      }],
      'movie',
      'more atmospheric and less action-focused',
      {},
      [],
      24,
    );
    expect(results).toHaveLength(24);
    expect(run).toHaveBeenCalledTimes(1);
    const input = run.mock.calls[0][1] as any;
    expect(input.reasoning_effort).toBe('low');
    expect(input.messages[1].content).toContain('less action-focused');
  });

  it('fails clearly if the Workers AI binding is absent', async () => {
    await expect(recommendDescribeTitlesWithCloudflare(
      {} as any,
      'a precise premise',
      'movie',
      {},
    )).rejects.toMatchObject({
      code: 'CLOUDFLARE_AI_NOT_CONFIGURED',
      retryable: false,
    });
  });
});
