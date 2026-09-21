import { afterEach, describe, expect, it, vi } from 'vitest';
import { recommendDescribeTitles, recommendSimilarTitles } from '../src/gemini';
import { EDITORIAL_RECOMMENDATIONS_PROMPT } from '../src/prompts';

describe('Gemini editorial contract', () => {
  afterEach(() => vi.unstubAllGlobals());
  it.each(['describe', 'similar'])('uses only 3.8 Flash and requests twenty rated titles for %s', async mode => {
    const recommendations = Array.from({length: 20}, (_, i) => ({title: `Title ${i}`, releaseYear: 2025, rating: 10 - i / 10}));
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({
      candidates: [{content: {parts: [{text: JSON.stringify({recommendations})}]}}]}));
    vi.stubGlobal('fetch', fetchMock);
    const env = {GEMINI_API_KEY: 'fixture'} as any;
    const result = mode === 'describe' ? await recommendDescribeTitles(env, 'Movies about aliens and AI. Recent', 'movie', {})
      : await recommendSimilarTitles(env, [], 'movie', '', {});
    expect(result).toHaveLength(20); expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('/gemini-3.8-flash:generateContent');
    const body = JSON.parse(String(init?.body));
    expect(body.systemInstruction.parts[0].text).toBe(EDITORIAL_RECOMMENDATIONS_PROMPT);
    expect(JSON.parse(body.contents[0].parts[0].text).targetCount).toBe(20);
    expect(body.generationConfig.maxOutputTokens).toBe(8192);
  });
  it('does not silently retry with an older model after a quota failure', async () => {
    const fetchMock = vi.fn(async () => Response.json({error: {message: 'Quota'}}, {status: 429}));
    vi.stubGlobal('fetch', fetchMock);
    await expect(recommendDescribeTitles({GEMINI_API_KEY: 'fixture'} as any, 'aliens', 'movie', {})).rejects.toMatchObject({code: 'GEMINI_UNAVAILABLE'});
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});
