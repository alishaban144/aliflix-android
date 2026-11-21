import { afterEach, expect, it, vi } from 'vitest';
import { env as bindings } from 'cloudflare:test';
import worker from '../src/index';

const env = {
  ...bindings,
  OMDB_API_KEY: 'fixture-only',
  CURSOR_SIGNING_SECRET: 'fixture-only',
};

function lookup(host: string, body: object) {
  return worker.fetch(new Request(`https://${host}.test/v1/metadata/omdb`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }), env);
}

afterEach(() => vi.unstubAllGlobals());

it('returns film awards by IMDb ID and shares cached results between requests', async () => {
  const upstream = vi.fn(async (input: URL) => {
    expect(input.hostname).toBe('www.omdbapi.com');
    expect(input.searchParams.get('i')).toBe('tt1375666');
    expect(input.searchParams.has('t')).toBe(false);
    return Response.json({ Response: 'True', Title: 'Inception', Type: 'movie', Year: '2010',
      imdbID: 'tt1375666', Awards: 'Won 4 Oscars. 159 wins & 220 nominations total' });
  });
  vi.stubGlobal('fetch', upstream);
  const body = { imdbId: 'tt1375666', mediaType: 'movie' };
  for (let i = 0; i < 2; i++) {
    const response = await lookup('omdb-film', body);
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ found: true, imdbId: 'tt1375666',
      awards: 'Won 4 Oscars. 159 wins & 220 nominations total' });
  }
  expect(upstream).toHaveBeenCalledTimes(1);
});

it('uses series title and premiere year for Android TV-type lookups', async () => {
  const upstream = vi.fn(async (input: URL) => {
    expect(input.searchParams.get('type')).toBe('series');
    expect(input.searchParams.get('t')).toBe('Dark');
    expect(input.searchParams.get('y')).toBe('2017');
    return Response.json({ Response: 'True', Title: 'Dark', Type: 'series', Year: '2017–2020',
      imdbID: 'tt5753856', Awards: 'N/A' });
  });
  vi.stubGlobal('fetch', upstream);
  const response = await lookup('omdb-series', { title: 'Dark', year: 2017, mediaType: 'tv' });
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual(expect.objectContaining({ found: true, year: 2017, type: 'series' }));
  expect(upstream).toHaveBeenCalledTimes(1);
});

it('treats quota errors as retryable and discards mismatched identities', async () => {
  const upstream = vi.fn()
    .mockImplementationOnce(async () => Response.json({ Response: 'False', Error: 'Request limit reached!' }))
    .mockImplementationOnce(async () => Response.json({
      Response: 'True', Title: 'The Wrong Title', Type: 'movie', Year: '2010',
      imdbID: 'tt1234567', Awards: 'Won 1 Oscar.',
    }));
  vi.stubGlobal('fetch', upstream);
  const body = { imdbId: 'tt1375666', mediaType: 'movie' };
  const limited = await lookup('omdb-quota', body);
  expect(limited.status).toBe(503);
  expect(await limited.json()).toMatchObject({ error: { code: 'OMDB_UNAVAILABLE', retryable: true } });
  const mismatch = await lookup('omdb-quota', body);
  expect(await mismatch.json()).toEqual({ found: false, source: 'OMDB' });
  expect(upstream).toHaveBeenCalledTimes(2);
});
