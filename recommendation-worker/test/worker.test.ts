import { describe, it, expect, vi } from 'vitest';
import { env as workerBindings } from 'cloudflare:test';
import worker, { expandGeneratedContinuation } from '../src/index';

describe('Cloudflare Worker', () => {
  const env: any = {
    GEMINI_API_KEY: 'test-key',
    GROQ_API_KEY: 'groq-test-key',
    TMDB_API_KEY: 'test-key',
    SUBDL_API_KEY: 'subdl-test-key',
    CURSOR_SIGNING_SECRET: 'cursor-secret',
    AI: { run: vi.fn() },
    RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: true }) },
  };
  const ctx: any = {
    waitUntil: () => {},
    passThroughOnException: () => {}
  };

  it.each([
    {mediaType: 'movie', query: 'First-contact films about language', title: 'Arrival', id: 329865, year: 2016},
    {mediaType: 'tv', query: 'Science fiction series about memory and corporate control', title: 'Severance', id: 95396, year: 2022},
  ])('keeps the mobile $mediaType recommendation route moving while Gemini sends no headers', async ({mediaType, query, title, id, year}) => {
    const stalledKey = 'stalled-' + crypto.randomUUID();
    const availableKey = 'available-' + crypto.randomUUID();
    const projectSignals = new Map<string, AbortSignal>();
    let markBothStarted!: () => void;
    const bothStarted = new Promise<void>(resolve => {markBothStarted = resolve;});
    vi.stubGlobal('fetch', async (input: string | URL, init?: RequestInit) => {
      const url = new URL(input);
      if (url.hostname === 'generativelanguage.googleapis.com') {
        const key = new Headers(init?.headers).get('x-goog-api-key')!;
        const signal = init!.signal!;
        projectSignals.set(key, signal);
        if (projectSignals.size === 2) markBothStarted();
        if (key === stalledKey) {
          return new Promise((_, reject) => {
            signal.addEventListener('abort', () => reject(signal.reason), {once: true});
          });
        }
        await bothStarted;
        const text = JSON.stringify({recommendations: [{title, releaseYear: year, rating: 9}]});
        return Response.json({candidates: [{content: {parts: [{text}]}}]});
      }
      if (url.pathname === '/3/search/' + mediaType && url.searchParams.get('query') === title) {
        const date = year + '-01-01';
        const match = mediaType === 'movie' ? {title, release_date: date} : {name: title, first_air_date: date};
        return Response.json({results: [{id, ...match}]});
      }
      throw new Error('Unexpected upstream route ' + url.pathname);
    });
    try {
      const response = await worker.fetch(new Request('https://worker.test/v3/recommendations', {
        method: 'POST',
        headers: {'content-type': 'application/json'},
        body: JSON.stringify({requestId: crypto.randomUUID(), mode: 'describe', mediaType, query, pageSize: 1, aiModel: 'gemini-4-hydrogen'}),
      }), {
        ...env,
        RECOMMENDATION_SESSIONS: workerBindings.RECOMMENDATION_SESSIONS,
        GEMINI_API_KEY: stalledKey,
        GEMINI_API_KEY_SECONDARY: availableKey,
      });
      expect(response.status).toBe(200);
      expect(await response.json()).toMatchObject({results: [{title, tmdbId: id, mediaType}]});
      expect(projectSignals.size).toBe(2);
      expect(projectSignals.get(stalledKey)?.aborted).toBe(true);
      expect(projectSignals.get(availableKey)?.aborted).toBe(false);
    } finally {
      vi.unstubAllGlobals();
    }
  }, 3000);

  it('should return 200 OK for GET /health', async () => {
    const request = new Request('http://localhost/health', { method: 'GET' });
    const response = await worker.fetch(request, env);
    
    expect(response.status).toBe(200);
    const body: any = await response.json();
    expect(body).toEqual({
      status: 'ok',
      service: 'aliflix-recommendations',
      cloudflareAiConfigured: true,
      geminiConfigured: true,
      groqConfigured: true,
      tmdbConfigured: true,
      subdlConfigured: true,
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

  it('fills the requested continuation page when the first fresh batch is useful but sparse', async () => {
    const reservations = [1, 2];
    let totalCount = 20;
    const stub = {
      reserveContinuation: vi.fn(async () => {
        const pass = reservations.shift();
        return pass === undefined
          ? { canExpand: false, pass: null, excludedTmdbIds: [], excludedTitles: [] }
          : { canExpand: true, pass, excludedTmdbIds: [1], excludedTitles: ['Already shown'] };
      }),
      completeContinuation: vi.fn(async (_fingerprint: string, _pass: number, results: any[]) => {
        totalCount += results.length;
        return { added: results.length, count: totalCount, exhausted: false };
      }),
      releaseContinuation: vi.fn(async () => {}),
    };
    const fresh = (start: number, count: number) => Array.from({ length: count }, (_, index) => ({
      tmdbId: start + index,
      mediaType: 'movie',
      title: `Fresh ${start + index}`,
    }));
    const runRecommendation = vi.fn(async (_env, request: any, _dependencies, options: any) => {
      expect(request.filters.excludedTmdbIds).toContain(1);
      expect(request.filters.excludedTitles).toContain('Already shown');
      return options.continuationPass === 1 ? fresh(100, 12) : fresh(112, 8);
    });
    const parsed: any = {
      requestId: '00000000-0000-4000-8000-000000000045',
      mode: 'describe',
      aiModel: 'groq-gpt-oss-120b',
      query: 'movies about aliens and ufos',
      mediaType: 'movie',
      filters: {
        originCountries: [], includedGenres: [], excludedGenres: [],
        excludedTmdbIds: [], excludedTitles: [],
      },
      pageSize: 20,
      cursor: 'signed-cursor',
    };

    await expandGeneratedContinuation(
      env,
      parsed,
      'fingerprint',
      20,
      stub,
      runRecommendation as any,
    );

    expect(runRecommendation).toHaveBeenCalledTimes(2);
    expect(stub.reserveContinuation).toHaveBeenCalledTimes(2);
    expect(stub.completeContinuation).toHaveBeenCalledTimes(2);
    expect(stub.releaseContinuation).not.toHaveBeenCalled();
    expect(totalCount).toBe(40);
  });

  it('keeps representative early and recent exclusions within the bounded model prompt', async () => {
    const excludedTitles = Array.from({ length: 140 }, (_, index) => `Shown ${index + 1}`);
    const stub = {
      reserveContinuation: vi.fn(async () => ({
        canExpand: true,
        pass: 1,
        excludedTmdbIds: [1],
        excludedTitles,
      })),
      completeContinuation: vi.fn(async () => ({ added: 20, count: 40, exhausted: false })),
      releaseContinuation: vi.fn(async () => {}),
    };
    const runRecommendation = vi.fn(async (_env, request: any) => {
      expect(request.filters.excludedTitles).toHaveLength(100);
      expect(request.filters.excludedTitles).toContain('Shown 1');
      expect(request.filters.excludedTitles).toContain('Shown 50');
      expect(request.filters.excludedTitles).toContain('Shown 91');
      expect(request.filters.excludedTitles).toContain('Shown 140');
      expect(request.filters.excludedTitles).not.toContain('Shown 70');
      return Array.from({ length: 20 }, (_, index) => ({
        tmdbId: 1_000 + index,
        mediaType: 'movie',
        title: `Fresh ${index + 1}`,
      }));
    });

    await expandGeneratedContinuation(
      env,
      {
        requestId: '00000000-0000-4000-8000-000000000047',
        mode: 'describe',
        query: 'movies about aliens and ufos',
        mediaType: 'movie',
        filters: {
          originCountries: [], includedGenres: [], excludedGenres: [],
          excludedTmdbIds: [], excludedTitles: [],
        },
        pageSize: 20,
      } as any,
      'fingerprint',
      20,
      stub,
      runRecommendation as any,
    );

    expect(runRecommendation).toHaveBeenCalledTimes(1);
  });

  it('keeps a useful first continuation batch when its optional sparse retry fails', async () => {
    let pass = 0;
    let totalCount = 20;
    const stub = {
      reserveContinuation: vi.fn(async () => ({
        canExpand: true,
        pass: ++pass,
        excludedTmdbIds: [],
        excludedTitles: [],
      })),
      completeContinuation: vi.fn(async (_fingerprint: string, _pass: number, results: any[]) => {
        totalCount += results.length;
        return { added: results.length, count: totalCount, exhausted: false };
      }),
      releaseContinuation: vi.fn(async () => {}),
    };
    const runRecommendation = vi.fn(async (_env, _request, _dependencies, options: any) => {
      if (options.continuationPass === 2) throw new Error('provider unavailable');
      return Array.from({ length: 5 }, (_, index) => ({
        tmdbId: 200 + index,
        mediaType: 'movie',
        title: `Fresh ${index + 1}`,
      }));
    });

    await expect(expandGeneratedContinuation(
      env,
      {
        requestId: '00000000-0000-4000-8000-000000000046',
        mode: 'describe',
        query: 'movies about aliens',
        mediaType: 'movie',
        filters: {
          originCountries: [], includedGenres: [], excludedGenres: [],
          excludedTmdbIds: [], excludedTitles: [],
        },
        pageSize: 20,
      } as any,
      'fingerprint',
      20,
      stub,
      runRecommendation as any,
    )).resolves.toBeUndefined();

    expect(runRecommendation).toHaveBeenCalledTimes(2);
    expect(stub.releaseContinuation).toHaveBeenCalledWith('fingerprint', 2);
    expect(totalCount).toBe(25);
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
