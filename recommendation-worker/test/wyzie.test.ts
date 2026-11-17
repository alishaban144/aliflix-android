import { env as bindings } from 'cloudflare:test';
import { afterEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';
import { downloadWyzieSubtitle } from '../src/wyzie';
import type { RecommendationEnv } from '../src/types';

const env: RecommendationEnv = {
  ...bindings,
  CURSOR_SIGNING_SECRET: 'test-only-signing-key',
  WYZIE_API_KEY: 'private-test-key',
  SUBDL_API_KEY: 'unused-test-key',
  TMDB_API_KEY: 'tmdb-test-key',
  RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) },
};

afterEach(() => vi.restoreAllMocks());

describe('Wyzie subtitle proxy', () => {
  it('uses the episode and video identity and converts the selected file to WebVTT', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.hostname === 'api.themoviedb.org') {
        expect(url.pathname).toBe('/3/tv/1429/external_ids');
        return Response.json({ imdb_id: 'tt2560140' });
      }
      expect(url.origin).toBe('https://sub.wyzie.io');
      expect(init?.redirect).toBe('manual');
      expect(new Request(url, init).redirect).toBe('manual');
      if (url.pathname === '/search') {
        expect(Object.fromEntries(url.searchParams)).toMatchObject({
          key: 'private-test-key', id: 'tt2560140', season: '1', episode: '3', language: 'en',
          video: 'Attack.on.Titan.S01E03.mkv', hash: '0000000000020003',
        });
        const track = { id: '1', language: 'en', display: 'English', source: 'charlie',
          release: 'Attack.on.Titan.S01E03', fileName: 'Attack.on.Titan.S01E03.ass', format: 'ass',
          url: 'https://sub.wyzie.io/c/subtitle?tok=opaque-token&format=ass&id=tt2560140&season=1&episode=3',
        };
        return Response.json([
          { ...track, match: { reasons: ['hash'] } },
          { ...track, id: '2', match: { reasons: ['wrong_episode'] } },
          { ...track, id: '3', ai: true },
          { ...track, id: '4', url: 'https://sub.wyzie.io.other.test/c/file?tok=opaque' },
        ]);
      }
      expect(url.pathname).toBe('/c/subtitle');
      expect(url.searchParams.get('tok')).toBe('opaque-token');
      expect(url.searchParams.get('format')).toBe('ass');
      expect(url.searchParams.get('to')).toBe('vtt');
      expect(url.searchParams.has('key')).toBe(false);
      return new Response('WEBVTT\n\n00:01.000 --> 00:02.000\nTest');
    });
    const response = await worker.fetch(new Request(
      'https://worker.test/v3/subtitles?type=tv&tmdbId=1429&season=1&episode=3&language=EN' +
        '&videoHash=0000000000020003&filename=Attack.on.Titan.S01E03.mkv',
    ), env);
    const result = await response.json<{ mediaKey: string; tracks: { downloadToken: string }[] }>();
    expect(response.status).toBe(200);
    expect(result).toMatchObject({ mediaKey: 'tv:1429:s1:e3', tracks: [
      { id: 'wyzie:charlie:1', languageCode: 'EN', fileName: 'Attack.on.Titan.S01E03.vtt', format: 'vtt', hashMatched: true },
    ] });
    expect(JSON.stringify(result)).not.toContain('private-test-key');
    const token = result.tracks[0].downloadToken;
    await expect(downloadWyzieSubtitle({ ...env, CURSOR_SIGNING_SECRET: 'different-key' }, token))
      .rejects.toMatchObject({ status: 400 });
    const download = await worker.fetch(new Request(
      `https://worker.test/v3/subtitles/download/${result.tracks[0].downloadToken}`,
    ), env);
    expect(download.status).toBe(200);
    expect(await download.text()).toMatch(/^WEBVTT/);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it.each([404, 429])('handles an upstream movie search status of %s', async status => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const url = new URL(String(input));
      if (url.hostname === 'api.themoviedb.org') {
        expect(url.pathname).toBe('/3/movie/286217/external_ids');
        return Response.json({ imdb_id: 'tt3659388' });
      }
      expect(url.searchParams.get('id')).toBe('tt3659388');
      expect(url.searchParams.has('season')).toBe(false);
      expect(url.searchParams.has('episode')).toBe(false);
      return new Response(null, { status });
    });
    const response = await worker.fetch(new Request('https://worker.test/v3/subtitles?type=movie&tmdbId=286217'), env);
    expect(response.status).toBe(status === 404 ? 200 : 429);
    if (status === 404) expect(await response.json()).toEqual({ mediaKey: 'movie:286217', tracks: [] });
  });

  it('rejects malformed tokens and URLs outside the Wyzie download endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');
    const token = btoa('https://sub.wyzie.io/search?tok=pretend').replace(/=+$/, '');
    await expect(downloadWyzieSubtitle(env, `wyzie_${token}`)).rejects.toMatchObject({ status: 400 });
    await expect(downloadWyzieSubtitle(env, 'wyzie_a')).rejects.toMatchObject({ status: 400 });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
