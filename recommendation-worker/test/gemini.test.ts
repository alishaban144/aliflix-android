import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  assessPremiseCandidates,
  GEMINI_GENERATION_TIMEOUT_MS,
  GEMINI_STRUCTURED_MAX_ATTEMPTS,
  GEMINI_VERIFICATION_TIMEOUT_MS,
  recommendDescribeTitles,
  recommendSimilarTitles,
} from '../src/gemini';
import { GeminiDescribeResponseSchema } from '../src/schemas';
import { DESCRIBE_RECOMMENDATIONS_PROMPT, SIMILAR_RECOMMENDATIONS_PROMPT, VERIFY_SIMILARITY_PROMPT } from '../src/prompts';

describe('Gemini Describe contract', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

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

  it('requires substantive Similar connections and multi-anchor fusion instead of broad genre overlap', () => {
    expect(SIMILAR_RECOMMENDATIONS_PROMPT).toContain('shared broad genre');
    expect(SIMILAR_RECOMMENDATIONS_PROMPT).toContain('blend distinctive elements from all anchors');
    expect(VERIFY_SIMILARITY_PROMPT).toContain('matching only one anchor cannot score 0.70 or higher');
    expect(VERIFY_SIMILARITY_PROMPT).toContain('Cross-media matches use the same standard');
  });

  it('uses medium thinking for Describe candidate generation and structured JSON', async () => {
    expect(GEMINI_GENERATION_TIMEOUT_MS).toBe(30_000);
    expect(GEMINI_VERIFICATION_TIMEOUT_MS).toBe(20_000);
    expect(GEMINI_STRUCTURED_MAX_ATTEMPTS).toBe(1);
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      candidates: [{
        finishReason: 'STOP',
        content: { parts: [{ text: JSON.stringify({ recommendations: [{
          title: 'Fire in the Sky',
          releaseYear: 1993,
          confidence: .97,
          reason: 'An extraterrestrial abduction is the central event.',
        }] }) }] },
      }],
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const results = await recommendDescribeTitles(
      { GEMINI_API_KEY: 'test-key' } as any,
      'movies about alien abduction',
      'movie',
      {},
    );

    expect(results.map(result => result.title)).toEqual(['Fire in the Sky']);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent');
    const body = JSON.parse(String(init.body));
    expect(body.generationConfig).toMatchObject({
      maxOutputTokens: 3072,
      thinkingConfig: { thinkingLevel: 'medium' },
      responseFormat: { text: { mimeType: 'APPLICATION_JSON' } },
    });
    expect(body.generationConfig.responseFormat.text.schema.type).toBe('object');
    expect(body.generationConfig.responseFormat.text.schema.properties.recommendations.type).toBe('array');
    expect(body.systemInstruction.parts[0].text).toContain('primary recommendation expert');
    expect(JSON.parse(body.contents[0].parts[0].text).targetCount).toBe(12);
  });

  it('uses medium thinking for Similar candidate generation', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      candidates: [{
        content: { parts: [{ text: JSON.stringify({ recommendations: [{
          title: 'Better Call Saul',
          releaseYear: 2015,
          confidence: .97,
          reason: 'A character-driven crime drama sharing Breaking Bad characters and themes.',
        }] }) }] },
      }],
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await recommendSimilarTitles(
      { GEMINI_API_KEY: 'test-key' } as any,
      [{
        tmdbId: 1396,
        mediaType: 'tv',
        title: 'Breaking Bad',
        releaseYear: 2008,
        overview: 'A chemistry teacher becomes a drug kingpin.',
        genres: ['Drama', 'Crime'],
        keywords: ['drug trafficking', 'moral decline'],
      }],
      'tv',
      '',
      {},
    );

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(String(init.body));
    expect(body.generationConfig.thinkingConfig.thinkingLevel).toBe('medium');
  });

  it('does not automatically retry a structured Gemini quota failure', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      error: {
        status: 'RESOURCE_EXHAUSTED',
        details: [{
          '@type': 'type.googleapis.com/google.rpc.RetryInfo',
          retryDelay: '0.001s',
        }],
      },
    }), { status: 429 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(recommendDescribeTitles(
      { GEMINI_API_KEY: 'test-key' } as any,
      'movies about alien abduction',
      'movie',
      {},
    )).rejects.toMatchObject({ code: 'GEMINI_UNAVAILABLE', retryable: true });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('uses medium thinking for the final premise relevance judgment', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      candidates: [{
        content: { parts: [{ text: JSON.stringify({ assessments: [{
          index: 0,
          relevanceScore: .96,
          matchedGroupIndexes: [],
          reason: 'Alien abduction is the central premise.',
        }] }) }] },
      }],
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await assessPremiseCandidates(
      { GEMINI_API_KEY: 'test-key' } as any,
      'movies about alien abduction',
      'movie',
      [],
      [{
        index: 0,
        title: 'Fire in the Sky',
        releaseYear: 1993,
        overview: 'A logger disappears after encountering a UFO.',
        genres: ['Science Fiction'],
        keywords: ['alien abduction'],
        geminiReason: 'A logger is abducted by extraterrestrials.',
        geminiConfidence: .97,
      }],
    );

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(String(init.body));
    expect(body.generationConfig.thinkingConfig.thinkingLevel).toBe('medium');
  });
});
