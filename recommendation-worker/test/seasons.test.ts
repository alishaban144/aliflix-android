import { afterEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';
import { env as bindings } from 'cloudflare:test';

const env = { ...bindings, TMDB_API_KEY: 'test-key', CURSOR_SIGNING_SECRET: 'test-only-cursor-secret', RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) } };
afterEach(() => vi.unstubAllGlobals());

describe('phone season metadata', () => {
  it('returns the requested season directly from TMDB without rating or title-detail fanout', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      expect(url.pathname).toBe('/3/tv/1396/season/3');
      expect(init?.cf?.cacheTtl).toBe(3600);
      return Response.json({ episodes: [
        { episode_number: 1, season_number: 3, name: 'Episode one', overview: 'Story', still_path: '/still.jpg', runtime: 47, vote_average: 9.1 },
        { episode_number: 1, season_number: 2, name: 'Wrong season' },
      ] });
    });
    vi.stubGlobal('fetch', fetcher);
    const result = await worker.fetch(new Request('https://worker.test/v3/tv/1396/seasons/3'), env);
    expect(result.status).toBe(200);
    expect(await result.json()).toEqual({ tmdbId: 1396, seasonNumber: 3, episodes: [
      { number: 1, seasonNumber: 3, title: 'Episode one', overview: 'Story', stillPath: '/still.jpg', runtime: 47 },
    ] });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('returns season names and counts in a small independent response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ seasons: [
      { season_number: 0, name: 'Specials', episode_count: 2 }, { season_number: 1, name: 'Season 1', episode_count: 7 },
    ] })));
    const result = await worker.fetch(new Request('https://worker.test/v3/tv/1396/seasons'), env);
    expect(await result.json()).toEqual({ tmdbId: 1396, seasons: [
      { number: 0, title: 'Specials', episodeCount: 2 }, { number: 1, title: 'Season 1', episodeCount: 7 },
    ] });
  });
  it('rejects mutations and invalid IDs without reaching TMDB', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    expect((await worker.fetch(new Request('https://worker.test/v3/tv/1396/seasons/1', { method: 'POST' }), env)).status).toBe(405);
    expect((await worker.fetch(new Request('https://worker.test/v3/tv/0/seasons/1'), env)).status).toBe(404);
    expect(fetcher).not.toHaveBeenCalled();
  });
});
