import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  assessPremiseCandidates,
  GEMINI_37_RECOMMENDATION_LIMIT,
  GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS,
  GEMINI_DESCRIBE_TIMEOUT_MS,
  GEMINI_GENERATION_TIMEOUT_MS,
  GEMINI_STRUCTURED_MAX_ATTEMPTS,
  GEMINI_VERIFICATION_TIMEOUT_MS,
  fallbackIntentFromQuery,
  recommendDescribeTitles,
  recommendSimilarTitles,
} from '../src/gemini';
import { GeminiDescribeResponseSchema } from '../src/schemas';
import {
  DESCRIBE_RECOMMENDATIONS_COMPACT_PROMPT,
  DESCRIBE_RECOMMENDATIONS_PROMPT,
  SIMILAR_RECOMMENDATIONS_COMPACT_PROMPT,
  SIMILAR_RECOMMENDATIONS_PROMPT,
  VERIFY_SIMILARITY_PROMPT,
} from '../src/prompts';

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
    expect(DESCRIBE_RECOMMENDATIONS_PROMPT).toContain('allows works centrally about aliens OR UFOs');
  });

  it('requires substantive Similar connections and multi-anchor fusion instead of broad genre overlap', () => {
    expect(SIMILAR_RECOMMENDATIONS_PROMPT).toContain('shared broad genre');
    expect(SIMILAR_RECOMMENDATIONS_PROMPT).toContain('blend distinctive elements from all anchors');
    expect(VERIFY_SIMILARITY_PROMPT).toContain('matching only one anchor cannot score 0.70 or higher');
    expect(VERIFY_SIMILARITY_PROMPT).toContain('Cross-media matches use the same standard');
  });

  it('uses low thinking and a compact bounded prompt for Describe candidate generation', async () => {
    expect(GEMINI_GENERATION_TIMEOUT_MS).toBe(30_000);
    expect(GEMINI_DESCRIBE_TIMEOUT_MS).toBe(24_000);
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
      maxOutputTokens: GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS,
      thinkingConfig: { thinkingLevel: 'low' },
      responseFormat: { text: { mimeType: 'APPLICATION_JSON' } },
    });
    expect(body.generationConfig.responseFormat.text.schema.type).toBe('object');
    expect(body.generationConfig.responseFormat.text.schema.properties.recommendations.type).toBe('array');
    expect(body.systemInstruction.parts[0].text).toBe(DESCRIBE_RECOMMENDATIONS_COMPACT_PROMPT);
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
      { GEMINI_API_KEY: 'test-key', GEMINI_GENERATION_MODEL: 'gemini-3.7-flash' } as any,
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
    expect(body.generationConfig.maxOutputTokens).toBe(GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS);
    expect(body.generationConfig.responseMimeType).toBe('application/json');
    expect(body.systemInstruction.parts[0].text).toBe(SIMILAR_RECOMMENDATIONS_COMPACT_PROMPT);
    expect(JSON.parse(body.contents[0].parts[0].text).targetCount).toBe(GEMINI_37_RECOMMENDATION_LIMIT);
  });

  it('extracts explicit concepts conservatively for a zero-Gemini TMDB fallback', () => {
    const intent = fallbackIntentFromQuery('shows about artificial intelligence or robots becoming conscious');
    expect(intent.requiredConceptGroups.map(group => group.label)).toEqual([
      'artificial intelligence', 'robots', 'conscious',
    ]);
    expect(intent.genreHints).toEqual([]);
    expect(intent.broadSearchPhrases).toEqual(['artificial intelligence', 'robots', 'conscious']);
  });

  it('uses low thinking and the bounded low-latency Describe contract for 3.7', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      candidates: [{
        finishReason: 'STOP',
        content: { parts: [{ text: JSON.stringify({ recommendations: [{
          title: 'Coherence',
          releaseYear: 2013,
          confidence: .94,
          reason: 'Friends encounter fractured realities during a comet flyby.',
        }] }) }] },
      }],
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await recommendDescribeTitles(
      { GEMINI_API_KEY: 'test-key', GEMINI_GENERATION_MODEL: 'gemini-3.7-flash' } as any,
      'mind-bending science fiction about fractured realities',
      'movie',
      {},
      [],
      12,
    );

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent');
    const body = JSON.parse(String(init.body));
    expect(body.generationConfig).toMatchObject({
      maxOutputTokens: GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS,
      thinkingConfig: { thinkingLevel: 'low' },
      responseMimeType: 'application/json',
    });
    expect(body.generationConfig.responseJsonSchema.type).toBe('object');
    expect(body.generationConfig.responseFormat).toBeUndefined();
    expect(body.systemInstruction.parts[0].text).toBe(DESCRIBE_RECOMMENDATIONS_COMPACT_PROMPT);
    expect(body.systemInstruction.parts[0].text).toContain('Return fewer instead of padding');
    expect(JSON.parse(body.contents[0].parts[0].text).targetCount).toBe(GEMINI_37_RECOMMENDATION_LIMIT);
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

  it('turns invalid Describe schema output into a retryable fallback signal', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: JSON.stringify({ recommendations: [] }) }] } }],
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(recommendDescribeTitles(
      { GEMINI_API_KEY: 'test-key' } as any,
      'movies about aliens',
      'movie',
      {},
    )).rejects.toMatchObject({ code: 'GEMINI_UNAVAILABLE', retryable: true });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('returns actionable guidance for a 3.7 high-demand capacity failure', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      error: {
        status: 'UNAVAILABLE',
        message: 'This model is currently experiencing high demand. Please try again later.',
      },
    }), { status: 503 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(recommendDescribeTitles(
      {
        GEMINI_API_KEY: 'test-key',
        GEMINI_GENERATION_MODEL: 'gemini-3.7-flash',
      } as any,
      'shows about artificial intelligence or robots becoming conscious',
      'tv',
      {},
    )).rejects.toMatchObject({
      code: 'GEMINI_UNAVAILABLE',
      message: 'Gemini 3.7 Flash is temporarily at capacity. Switch to Gemini 3.5 Flash in Settings and try again.',
      retryable: true,
    });
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
        aiReason: 'A logger is abducted by extraterrestrials.',
        aiConfidence: .97,
      }],
    );

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(String(init.body));
    expect(body.generationConfig.thinkingConfig.thinkingLevel).toBe('medium');
  });
});
