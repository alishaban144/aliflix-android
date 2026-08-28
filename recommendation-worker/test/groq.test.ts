import { afterEach, describe, expect, it, vi } from 'vitest';
import { assessRecommendationPremise, recommendDescribeTitles } from '../src/ai';
import { GROQ_MAX_OUTPUT_TOKENS, GROQ_MODEL, GROQ_VERIFICATION_MAX_OUTPUT_TOKENS } from '../src/groq';

describe('Groq structured recommendation provider', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('uses one bounded strict-schema broad-search request and deduplicates its result', async () => {
    let requestBody: any;
    let requestHeaders: Headers | undefined;
    const providerFetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      requestBody = JSON.parse(String(init?.body));
      requestHeaders = new Headers(init?.headers);
      return Response.json({
        choices: [{
          finish_reason: 'stop',
          message: { content: JSON.stringify({ recommendations: [
            { title: 'Arrival', releaseYear: 2016, confidence: .96, reason: 'Linguists confront aliens through a story centered on communication.' },
            { title: 'Arrival', releaseYear: 2016, confidence: .90, reason: 'Duplicate.' },
          ] }) },
        }],
      });
    });
    vi.stubGlobal('fetch', providerFetch);

    const results = await recommendDescribeTitles(
      { GROQ_API_KEY: 'secret-test-key', AI_GENERATION_MODEL: 'groq-qwen-3.8-27b' } as any,
      'first contact through language',
      'movie',
      {},
      [],
      12,
    );

    expect(providerFetch).toHaveBeenCalledTimes(1);
    expect(requestHeaders?.get('authorization')).toBe('Bearer secret-test-key');
    expect(requestBody.model).toBe(GROQ_MODEL);
    expect(requestBody.reasoning_effort).toBe('low');
    expect(requestBody.reasoning_format).toBe('hidden');
    expect(requestBody.messages).toHaveLength(1);
    expect(requestBody.messages[0].role).toBe('user');
    expect(requestBody.max_completion_tokens).toBe(GROQ_MAX_OUTPUT_TOKENS);
    expect(requestBody.response_format.json_schema.strict).toBe(true);
    const schema = requestBody.response_format.json_schema.schema;
    expect(schema.additionalProperties).toBe(false);
    expect(schema.required).toEqual(['recommendations']);
    expect(schema.properties.recommendations.items.additionalProperties).toBe(false);
    expect(requestBody.messages[0].content).toContain('"targetCount":12');
    expect(requestBody.messages[0].content).toContain('include older, international, independent');
    expect(requestBody.messages[0].content).toContain('Use your knowledge of the actual story');
    expect(results.map(result => result.title)).toEqual(['Arrival']);
  });

  it('fails clearly before fetch when the Worker secret is missing', async () => {
    const providerFetch = vi.fn();
    vi.stubGlobal('fetch', providerFetch);

    await expect(recommendDescribeTitles(
      { AI_GENERATION_MODEL: 'groq-qwen-3.8-27b' } as any,
      'a precise premise',
      'movie',
      {},
    )).rejects.toMatchObject({ code: 'GROQ_NOT_CONFIGURED', retryable: false });
    expect(providerFetch).not.toHaveBeenCalled();
  });

  it('bounds an overlong assessment reason without discarding valid relevance scores', async () => {
    let requestBody: any;
    const providerFetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      requestBody = JSON.parse(String(init?.body));
      return Response.json({
      choices: [{
        finish_reason: 'stop',
        message: { content: JSON.stringify({ assessments: [{
          index: 0,
          relevanceScore: .96,
          matchedGroupIndexes: [0, 1],
          reason: `Extraterrestrials abduct the central character and the resulting testimony drives the entire investigation ${'with concrete supporting plot evidence '.repeat(6)}`,
        }] }) },
      }],
    });
    });
    vi.stubGlobal('fetch', providerFetch);

    const assessments = await assessRecommendationPremise(
      { GROQ_API_KEY: 'secret-test-key', AI_GENERATION_MODEL: 'groq-qwen-3.8-27b' } as any,
      'movies about alien abduction',
      'movie',
      [
        { label: 'alien', synonyms: ['alien'], weight: 1 },
        { label: 'abduction', synonyms: ['abduction'], weight: 1 },
      ],
      [{
        index: 0,
        title: 'Fire in the Sky',
        releaseYear: 1993,
        overview: 'A logger disappears after an encounter with an extraterrestrial craft.',
        genres: ['Science Fiction'],
        keywords: ['alien', 'abduction'],
        aiReason: 'A logger is abducted by extraterrestrials.',
        aiConfidence: .97,
      }],
    );

    expect(assessments).toHaveLength(1);
    expect(assessments[0].relevanceScore).toBe(.96);
    expect(assessments[0].reason.length).toBeLessThanOrEqual(180);
    expect(requestBody.reasoning_effort).toBe('none');
    expect(requestBody.temperature).toBe(.2);
    expect(requestBody.max_completion_tokens).toBe(GROQ_VERIFICATION_MAX_OUTPUT_TOKENS);
  });

  it('does not spend a second request after a rate-limit response', async () => {
    const providerFetch = vi.fn(async () => Response.json(
      { error: { code: 'rate_limit_exceeded', type: 'tokens', message: 'Rate limit reached' } },
      { status: 429 },
    ));
    vi.stubGlobal('fetch', providerFetch);

    await expect(recommendDescribeTitles(
      { GROQ_API_KEY: 'secret-test-key', AI_GENERATION_MODEL: 'groq-qwen-3.8-27b' } as any,
      'a precise premise',
      'movie',
      {},
    )).rejects.toMatchObject({ code: 'GROQ_UNAVAILABLE', retryable: true });
    expect(providerFetch).toHaveBeenCalledTimes(1);
  });

  it('reports semantically invalid JSON as a provider failure without retrying', async () => {
    const providerFetch = vi.fn(async () => Response.json({
      choices: [{ finish_reason: 'stop', message: { content: '{"recommendations":[]}' } }],
    }));
    vi.stubGlobal('fetch', providerFetch);

    await expect(recommendDescribeTitles(
      { GROQ_API_KEY: 'secret-test-key', AI_GENERATION_MODEL: 'groq-qwen-3.8-27b' } as any,
      'a precise premise',
      'movie',
      {},
    )).rejects.toMatchObject({ code: 'GROQ_UNAVAILABLE', retryable: true });
    expect(providerFetch).toHaveBeenCalledTimes(1);
  });
});
