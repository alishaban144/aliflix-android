import { afterEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';
import { decodeSubdlDownloadToken, parseSubdlSearchDocument, searchSubdlSubtitles } from '../src/subdl';

const env: any = {
  SUBDL_API_KEY: 'private-subdl-key',
  TMDB_API_KEY: 'private-tmdb-key',
  RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) },
};

afterEach(() => vi.restoreAllMocks());

describe('SubDL subtitle proxy', () => {
  it('phone searches include language-specific episode results beyond the early foreign tracks', async () => {
    const seen: URL[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      seen.push(url);
      if (url.hostname === 'api.themoviedb.org') return Response.json({ imdb_id: 'tt0944947' });
      if (url.hostname === 'opensubtitles-v3.strem.io') return Response.json({ subtitles:
        Array.from({ length: 50 }, (_, i) => ({ url: `https://opensubtitles-v3.strem.io/file/${i}.srt`, lang: i === 49 ? 'eng' : 'ara', movieReleaseName: `release-${i}` })) });
      return Response.json({ status: true, results: [{ imdb_id: 'tt0944947', tmdb_id: 1399, type: 'tv' }],
        subtitles: url.searchParams.get('languages') === 'EN' ? [{ name: 'Game.of.Thrones.S01E01.WEB.srt',
          language: 'EN', season: 1, episode: 1, format: 'srt', url: '/subtitle/got-en' }] : [] });
    });
    const result = await searchSubdlSubtitles(env, new URL('https://worker.test/v3/subtitles?mode=phone&type=tv&tmdbId=1399&season=1&episode=1&language=EN'));
    expect(result.tracks.length).toBe(51);
    expect(result.tracks[0].languageCode).toBe('EN');
    expect(result.tracks.filter(track => track.languageName === 'English')).toHaveLength(2);
    expect(seen.filter(url => url.hostname === 'api.subdl.com' && url.searchParams.get('languages') === 'EN')).toHaveLength(2);
    expect(result.mediaKey).toBe('tv:1399:s1:e1');
  });

  it('forwards file fingerprint hints without claiming an unconfirmed hash match', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.hostname === 'api.themoviedb.org') return Response.json({ imdb_id: 'tt1375666' });
      if (url.hostname === 'opensubtitles-v3.strem.io') {
        expect(decodeURIComponent(url.pathname)).toContain('videoHash=0000000000020003&videoSize=131072&filename=Inception.mp4.json');
        return Response.json({ subtitles: [{ url: 'https://opensubtitles-v3.strem.io/file/one.srt', lang: 'eng' }] });
      }
      return Response.json({ status: true, results: [{ imdb_id: 'tt1375666' }], subtitles: [] });
    });
    const result = await searchSubdlSubtitles(env, new URL('https://worker.test/v3/subtitles?mode=phone&type=movie&tmdbId=27205&videoHash=0000000000020003&videoSize=131072&filename=Inception.mp4'));
    expect(result.tracks).toHaveLength(1);
    expect(result.tracks[0].hashMatched).toBeUndefined();
  });
  it('keeps TV-only parameters out of exact movie searches', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.hostname === 'api.themoviedb.org') {
        expect(url.pathname).toBe('/3/movie/27205/external_ids');
        return new Response(JSON.stringify({ id: 27205, imdb_id: 'tt1375666' }));
      }
      if (url.hostname === 'opensubtitles-v3.strem.io') {
        expect(url.pathname).toBe('/subtitles/movie/tt1375666.json');
        return new Response(JSON.stringify({ subtitles: [] }), { headers: { 'content-type': 'application/json' } });
      }
      expect(url.origin + url.pathname).toBe('https://api.subdl.com/api/v2/subtitles/search');
      expect(url.searchParams.has('api_key')).toBe(false);
      expect(new Headers(init?.headers).get('authorization')).toBe('Bearer private-subdl-key');
      expect(url.searchParams.get('imdb_id')).toBe('tt1375666');
      expect(url.searchParams.has('tmdb_id')).toBe(false);
      expect(url.searchParams.get('type')).toBe('movie');
      expect(url.searchParams.get('unpack')).toBe('1');
      expect(url.searchParams.has('full_season')).toBe(false);
      expect(url.searchParams.has('season_number')).toBe(false);
      expect(url.searchParams.has('episode_number')).toBe(false);
      return new Response(JSON.stringify({
        status: true,
        results: [{ imdb_id: 'tt1375666', tmdb_id: 27205, type: 'movie', name: 'Inception' }],
        subtitles: [{
          name: 'Inception.2010.1080p.srt',
          release_name: 'Inception.2010.1080p',
          language: 'EN',
          format: 'srt',
          url: '/subtitle/inception/en',
        }],
      }), { headers: { 'content-type': 'application/json' } });
    });

    const response = await worker.fetch(new Request(
      'https://worker.test/v3/subtitles?type=movie&tmdbId=27205',
    ), env);
    const body: any = await response.json();

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(body.mediaKey).toBe('movie:27205');
    expect(body.tracks).toHaveLength(1);
    expect(body.tracks[0].fileName).toBe('Inception.2010.1080p.srt');
    expect(JSON.stringify(body)).not.toContain('private-subdl-key');
  });

  it('combines direct and season-pack files while retaining the exact TV episode', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.hostname === 'api.themoviedb.org') {
        expect(url.pathname).toBe('/3/tv/1405/external_ids');
        return new Response(JSON.stringify({ id: 1405, imdb_id: 'tt0773262' }));
      }
      if (url.hostname === 'opensubtitles-v3.strem.io') {
        expect(url.pathname).toBe('/subtitles/series/tt0773262:1:8.json');
        return new Response(JSON.stringify({ subtitles: [] }), { headers: { 'content-type': 'application/json' } });
      }
      expect(url.origin + url.pathname).toBe('https://api.subdl.com/api/v2/subtitles/search');
      expect(url.searchParams.has('api_key')).toBe(false);
      expect(new Headers(init?.headers).get('authorization')).toBe('Bearer private-subdl-key');
      expect(url.searchParams.get('imdb_id')).toBe('tt0773262');
      expect(url.searchParams.has('tmdb_id')).toBe(false);
      expect(url.searchParams.get('type')).toBe('tv');
      expect(url.searchParams.get('season')).toBe('1');
      expect(url.searchParams.get('unpack')).toBe('1');
      const seasonPack = url.searchParams.get('full_season') === '1';
      if (seasonPack) {
        expect(url.searchParams.has('episode')).toBe(false);
        return new Response(JSON.stringify({
          status: true,
          results: [{ imdb_id: 'tt0773262', tmdb_id: 1405, type: 'tv', name: 'Dexter' }],
          subtitles: [{
            release_name: 'Dexter.S01.1080p',
            full_season: true,
            lang: 'english',
            unpack_files: [
              { name: 'Dexter.S01E07.srt', season: 1, episode: 7, url: '/subtitle/pack/e7' },
              { name: 'Dexter.S01E08.srt', season: 1, episode: 8, url: '/subtitle/pack/e8' },
            ],
          }],
        }), { headers: { 'content-type': 'application/json' } });
      }
      expect(url.searchParams.get('episode')).toBe('8');
      expect(url.searchParams.has('full_season')).toBe(false);
      return new Response(JSON.stringify({
        status: true,
        results: [{ imdb_id: 'tt0773262', tmdb_id: 1405, type: 'tv', name: 'Dexter' }],
        subtitles: [{
          name: 'Dexter.S01E08.direct.srt',
          season: 1,
          episode: 8,
          lang: 'english',
          url: '/subtitle/direct/e8',
        }],
      }), { headers: { 'content-type': 'application/json' } });
    });

    const response = await worker.fetch(new Request(
      'https://worker.test/v3/subtitles?type=tv&tmdbId=1405&season=1&episode=8',
    ), env);
    const body: any = await response.json();

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(body.mediaKey).toBe('tv:1405:s1:e8');
    expect(body.tracks).toHaveLength(2);
    expect(body.tracks.map((track: any) => track.fileName)).toEqual([
      'Dexter.S01E08.direct.srt',
      'Dexter.S01E08.srt',
    ]);
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

  it('rejects a mismatched IMDb identity even when SubDL reports the requested TMDB id', () => {
    expect(parseSubdlSearchDocument({
      status: true,
      results: [{ imdb_id: 'tt9999999', tmdb_id: 603, type: 'movie' }],
      subtitles: [{ url: '/subtitle/wrong/title', lang: 'EN' }],
    }, 'movie', 603, undefined, undefined, 'tt0133093')).toEqual([]);
  });

  it('supports season zero specials while retaining the exact episode', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      if (url.hostname === 'api.themoviedb.org') {
        return new Response(JSON.stringify({ id: 123, imdb_id: 'tt1234567' }));
      }
      expect(url.searchParams.get('season')).toBe('0');
      expect(url.searchParams.get('episode')).toBe('2');
      return new Response(JSON.stringify({
        status: true,
        results: [{ imdb_id: 'tt1234567', tmdb_id: 123, type: 'tv' }],
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

  it('fails safely when TMDB cannot provide a verifiable IMDb identity', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 27205, imdb_id: null })),
    );

    const response = await worker.fetch(new Request(
      'https://worker.test/v3/subtitles?type=movie&tmdbId=27205',
    ), env);

    expect(response.status).toBe(502);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(await response.json()).toEqual({
      error: {
        code: 'SUBTITLE_IDENTITY_UNAVAILABLE',
        message: 'Subtitles could not verify this title yet',
        retryable: true,
      },
    });
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

  it('recovers an expired unpacked-file link through the authenticated v2 download endpoint', async () => {
    const token = parseSubdlSearchDocument({
      status: true,
      results: [{ tmdb_id: 86831, type: 'tv' }],
      subtitles: [{
        season: 1,
        episode: 4,
        url: '/subtitle/hxp9APhXjI/23HbdmvIFo',
        lang: 'EN',
      }],
    }, 'tv', 86831, 1, 4)[0].downloadToken;
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('expired', { status: 404 }))
      .mockResolvedValueOnce(new Response('1\n00:00:01,000 --> 00:00:02,000\nHello', { status: 200 }));

    const response = await worker.fetch(
      new Request(`https://worker.test/v3/subtitles/download/${token}`),
      env,
    );

    expect(response.status).toBe(200);
    expect(await response.text()).toContain('-->');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[1][0])).toBe(
      'https://api.subdl.com/api/v2/subtitles/hxp9APhXjI/download?format=file',
    );
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get('authorization'))
      .toBe('Bearer private-subdl-key');
  });

  it('streams OpenSubtitles download directly with UTF-8 subtitle content', async () => {
    const rawUrl = 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1618';
    const token = btoa(rawUrl).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
      expect(String(input)).toBe(rawUrl);
      return new Response('1\n00:00:01,000 --> 00:00:02,000\nHello World', {
        headers: { 'content-type': 'application/x-subrip; charset=utf-8' },
      });
    });
    const response = await worker.fetch(
      new Request(`https://worker.test/v3/subtitles/download/${token}`),
      env,
    );
    expect(response.status).toBe(200);
    expect(await response.text()).toContain('Hello World');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
