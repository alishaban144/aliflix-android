import { afterEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';
import { decodeSubdlDownloadToken, parseSubdlSearchDocument } from '../src/subdl';

const env: any = {
  SUBDL_API_KEY: 'private-subdl-key',
  RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) },
};

afterEach(() => vi.restoreAllMocks());

describe('SubDL subtitle proxy', () => {
  it('searches TV subtitles by exact TMDB season and episode without exposing the key', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      expect(url.origin + url.pathname).toBe('https://api.subdl.com/api/v1/subtitles');
      expect(url.searchParams.get('api_key')).toBe('private-subdl-key');
      expect(url.searchParams.get('tmdb_id')).toBe('1405');
      expect(url.searchParams.get('type')).toBe('tv');
      expect(url.searchParams.get('season_number')).toBe('1');
      expect(url.searchParams.get('episode_number')).toBe('8');
      expect(url.searchParams.get('unpack')).toBe('1');
      expect(url.searchParams.get('full_season')).toBe('1');
      return new Response(JSON.stringify({
        status: true,
        results: [{ tmdb_id: 1405, type: 'tv', name: 'Dexter' }],
        subtitles: [{
          release_name: 'Dexter.S01.1080p',
          full_season: true,
          lang: 'english',
          unpack_files: [
            { name: 'Dexter.S01E07.srt', season: 1, episode: 7, url: '/subtitle/parent/e7' },
            { name: 'Dexter.S01E08.srt', season: 1, episode: 8, url: '/subtitle/parent/e8' },
          ],
        }],
      }), { headers: { 'content-type': 'application/json' } });
    });

    const response = await worker.fetch(new Request(
      'https://worker.test/v3/subtitles?type=tv&tmdbId=1405&season=1&episode=8',
    ), env);
    const body: any = await response.json();

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(body.mediaKey).toBe('tv:1405:s1:e8');
    expect(body.tracks).toHaveLength(1);
    expect(body.tracks[0].fileName).toBe('Dexter.S01E08.srt');
    expect(JSON.stringify(body)).not.toContain('private-subdl-key');
  });

  it('rejects a mismatched TMDB result and mismatched episodes', () => {
    expect(parseSubdlSearchDocument({
      status: true,
      results: [{ tmdb_id: 999, type: 'tv' }],
      subtitles: [{ season: 1, episode: 8, url: '/subtitle/a/b', lang: 'EN' }],
    }, 'tv', 1405, 1, 8)).toEqual([]);

    expect(parseSubdlSearchDocument({
      status: true,
      results: [{ tmdb_id: 1405, type: 'tv' }],
      subtitles: [{ season: 1, episode: 7, url: '/subtitle/a/b', lang: 'EN' }],
    }, 'tv', 1405, 1, 8)).toEqual([]);
  });

  it('supports season zero specials while retaining the exact episode', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      expect(url.searchParams.get('season_number')).toBe('0');
      expect(url.searchParams.get('episode_number')).toBe('2');
      return new Response(JSON.stringify({
        status: true,
        results: [{ tmdb_id: 123, type: 'tv' }],
        subtitles: [{ season: 0, episode: 2, url: '/subtitle/special/e2', lang: 'EN' }],
      }));
    });

    const response = await worker.fetch(new Request(
      'https://worker.test/v3/subtitles?type=tv&tmdbId=123&season=0&episode=2',
    ), env);
    const body: any = await response.json();

    expect(response.status).toBe(200);
    expect(body.mediaKey).toBe('tv:123:s0:e2');
    expect(body.tracks).toHaveLength(1);
  });

  it('decodes only SubDL subtitle paths and streams downloads with server-side auth', async () => {
    const token = parseSubdlSearchDocument({
      status: true,
      results: [{ tmdb_id: 27205, type: 'movie' }],
      subtitles: [{ url: '/subtitle/3197651-3213944.zip', lang: 'EN' }],
    }, 'movie', 27205)[0].downloadToken;
    expect(decodeSubdlDownloadToken(token)?.href).toBe(
      'https://dl.subdl.com/subtitle/3197651-3213944.zip',
    );
    expect(decodeSubdlDownloadToken('aHR0cHM6Ly9ldmlsLmV4YW1wbGUv')).toBeNull();

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      expect(String(input)).toBe('https://dl.subdl.com/subtitle/3197651-3213944.zip');
      expect(new Headers(init?.headers).get('x-api-key')).toBe('private-subdl-key');
      return new Response('subtitle bytes', { headers: { 'content-type': 'application/octet-stream' } });
    });
    const response = await worker.fetch(
      new Request(`https://worker.test/v3/subtitles/download/${token}`),
      env,
    );
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('subtitle bytes');
  });

  it('fails safely when the SubDL secret has not been configured', async () => {
    const response = await worker.fetch(
      new Request('https://worker.test/v3/subtitles?type=movie&tmdbId=27205'),
      { ...env, SUBDL_API_KEY: undefined },
    );
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({
      error: {
        code: 'SUBDL_NOT_CONFIGURED',
        message: 'Subtitles are not configured yet',
        retryable: false,
      },
    });
  });

  it('falls back to SubDL anonymous download only when a free key is rejected', async () => {
    const token = parseSubdlSearchDocument({
      status: true,
      results: [{ tmdb_id: 27205, type: 'movie' }],
      subtitles: [{ url: '/subtitle/free-key.zip', lang: 'EN' }],
    }, 'movie', 27205)[0].downloadToken;
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('paid only', { status: 403 }))
      .mockResolvedValueOnce(new Response('free subtitle', { status: 200 }));

    const response = await worker.fetch(
      new Request(`https://worker.test/v3/subtitles/download/${token}`),
      env,
    );

    expect(response.status).toBe(200);
    expect(await response.text()).toBe('free subtitle');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('x-api-key')).toBe('private-subdl-key');
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).has('x-api-key')).toBe(false);
  });
});
