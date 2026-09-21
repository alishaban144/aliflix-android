import { describe, expect, it, vi } from 'vitest';
import { processRecommendation } from '../src/engine';
import { RecommendationRequestSchema } from '../src/schemas';
import { ServiceError } from '../src/types';

const picks = Array.from({length: 20}, (_, i) => ({ title: `Title ${i}`, releaseYear: 2025, rating: 10 - i / 10, confidence: 0, reason: '' }));
function catalogue() {
  return { callsRemaining: 40, genres: vi.fn(async () => ({genres: []})),
    searchKeyword: vi.fn(), searchPerson: vi.fn(), searchCompany: vi.fn(), discover: vi.fn(),
    details: vi.fn(async () => ({id: 999, title: 'Anchor', overview: '', genres: []})),
    searchTitle: vi.fn(async (_type: string, title: string) => ({page: 1, total_pages: 1, total_results: 1,
      results: [{id: Number(title.split(' ')[1]) + 1, title, release_date: '2025-01-01', vote_average: 1, overview: 'Unrelated catalogue wording'}]})) };
}
describe('LLM editorial recommendations', () => {
  it.each(['describe', 'similar'] as const)('keeps twenty distinct editorial picks without verification in %s', async mode => {
    const request = RecommendationRequestSchema.parse({requestId: crypto.randomUUID(), mode, mediaType: 'movie',
      query: 'Movies about aliens and AI. Recent', aiModel: 'gemini-3.8-flash',
      ...(mode === 'similar' ? {anchor: {tmdbId: 999, title: 'Anchor', mediaType: 'movie'}} : {})});
    const generate = vi.fn(async () => picks);
    const verify = vi.fn(); const tmdb = catalogue();
    const results = await processRecommendation({} as any, request, {tmdb, recommendDescribe: generate,
      recommendSimilar: generate, assessPremise: verify, assessSimilarity: verify, embed: verify, interpret: verify});
    expect(results).toHaveLength(20);
    expect(results.map(r => r.title)).toEqual(picks.map(p => p.title));
    expect(new Set(results.map(r => r.tmdbId)).size).toBe(20);
    expect(results.every(r => r.matchReasons.length === 0 && !r.overview)).toBe(true);
    expect(verify).not.toHaveBeenCalled(); expect(tmdb.discover).not.toHaveBeenCalled();
  });
  it('rejects older Gemini models', () => {
    for (const aiModel of ['gemini-3.5-flash', 'gemini-3.7-flash']) expect(RecommendationRequestSchema.safeParse({
      requestId: crypto.randomUUID(), mode: 'describe', mediaType: 'movie', query: 'aliens', aiModel}).success).toBe(false);
  });
  it('propagates provider failure without fallback', async () => {
    const request = RecommendationRequestSchema.parse({requestId: crypto.randomUUID(), mode: 'describe', mediaType: 'movie', query: 'aliens'});
    const tmdb = catalogue();
    await expect(processRecommendation({} as any, request, {tmdb, recommendDescribe: async () => {
      throw new ServiceError('GEMINI_UNAVAILABLE', 'Capacity', 503, true);
    }})).rejects.toMatchObject({code: 'GEMINI_UNAVAILABLE'});
    expect(tmdb.searchTitle).not.toHaveBeenCalled(); expect(tmdb.discover).not.toHaveBeenCalled();
  });
});
