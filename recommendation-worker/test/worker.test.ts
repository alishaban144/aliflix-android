import { describe, it, expect, vi } from 'vitest';
import worker from '../src/index';

describe('Cloudflare Worker', () => {
  const env: any = {
    GEMINI_API_KEY: 'test-key',
    GROQ_API_KEY: 'groq-test-key',
    TMDB_API_KEY: 'test-key',
    CURSOR_SIGNING_SECRET: 'cursor-secret',
    RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) },
  };
  const ctx: any = {
    waitUntil: () => {},
    passThroughOnException: () => {}
  };

  it('should return 200 OK for GET /health', async () => {
    const request = new Request('http://localhost/health', { method: 'GET' });
    const response = await worker.fetch(request, env);
    
    expect(response.status).toBe(200);
    const body: any = await response.json();
    expect(body).toEqual({
      status: 'ok',
      service: 'aliflix-recommendations',
      geminiConfigured: true,
      groqConfigured: true,
      tmdbConfigured: true,
    });
  });

  it('should return 404 for unsupported routes', async () => {
    const request = new Request('http://localhost/v1/interpret', { method: 'POST', body: '{}' });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(404);
  });

  it('should return 405 for wrong method', async () => {
    const request = new Request('http://localhost/v3/recommendations', { method: 'GET' });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(405);
  });

  it('should return 415 for wrong content type', async () => {
    const request = new Request('http://localhost/v3/recommendations', { 
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: 'bad'
    });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(415);
  });

  it('should return 413 for oversized requests', async () => {
    const hugeBody = JSON.stringify({ data: 'x'.repeat(150000) });
    const request = new Request('http://localhost/v3/recommendations', { 
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: hugeBody
    });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(413);
  });

  it('pages plain Ended filters directly through TMDB without requiring a Durable Object pool', async () => {
    const items = Array.from({ length: 44 }, (_, index) => ({
      id: index + 1,
      name: `Animated series ${index + 1}`,
      original_language: index % 2 ? 'ja' : 'en',
      origin_country: [index % 2 ? 'JP' : 'US'],
      genre_ids: [16, 10765],
      first_air_date: '2020-01-01',
      vote_average: 8,
      vote_count: 1_000 - index,
    }));
    const outboundUrls: URL[] = [];
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
      const url = new URL(input instanceof Request ? input.url : String(input));
      outboundUrls.push(url);
      if (url.pathname === '/3/genre/tv/list') {
        return Response.json({ genres: [
          { id: 16, name: 'Animation' },
          { id: 10765, name: 'Sci-Fi & Fantasy' },
        ] });
      }
      if (url.pathname === '/3/discover/tv') {
        const page = Number(url.searchParams.get('page') || 1);
        return Response.json({
          page,
          results: items.slice((page - 1) * 20, page * 20),
          total_pages: 3,
          total_results: items.length,
        });
      }
      throw new Error(`Unexpected outbound request: ${url}`);
    });

    try {
      const body = {
        requestId: '00000000-0000-4000-8000-000000000044',
        mode: 'filters',
        query: '',
        mediaType: 'tv',
        filters: {
          includedGenres: ['Animation', 'Sci-Fi & Fantasy'],
          seriesStatus: 'ended',
          originCountries: [],
          excludedGenres: [],
          excludedTmdbIds: [],
          excludedTitles: [],
        },
        pageSize: 24,
      };
      const firstResponse = await worker.fetch(new Request('http://localhost/v3/recommendations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }), env);
      expect(firstResponse.status).toBe(200);
      const first: any = await firstResponse.json();
      expect(first.results).toHaveLength(24);
      expect(first.totalResults).toBe(44);
      expect(first.hasMore).toBe(true);
      expect(first.results.every((item: any) => item.status === 'Ended')).toBe(true);

      const secondResponse = await worker.fetch(new Request('http://localhost/v3/recommendations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...body, cursor: first.nextCursor }),
      }), env);
      expect(secondResponse.status).toBe(200);
      const second: any = await secondResponse.json();
      expect(second.results).toHaveLength(20);
      expect(second.hasMore).toBe(false);
      const ids = [...first.results, ...second.results].map(item => item.tmdbId);
      expect(new Set(ids).size).toBe(44);
      const discoverUrls = outboundUrls.filter(url => url.pathname === '/3/discover/tv');
      expect(discoverUrls.every(url => url.searchParams.get('with_genres') === '16,10765')).toBe(true);
      expect(discoverUrls.every(url => url.searchParams.get('with_status') === '3')).toBe(true);
      expect(discoverUrls.every(url => !url.searchParams.has('vote_count.gte'))).toBe(true);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
