import { describe, it, expect } from 'vitest';
import worker from '../src/index';

describe('Cloudflare Worker', () => {
  const env = { GEMINI_API_KEY: 'test-key' };
  const ctx = { waitUntil: () => {}, passThroughOnException: () => {} } as any;

  it('should return 200 OK for GET /health', async () => {
    const request = new Request('http://localhost/health', { method: 'GET' });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ status: 'ok', service: 'aliflix-recommendations' });
  });

  it('should return 404 for unsupported routes', async () => {
    const request = new Request('http://localhost/unsupported', { method: 'POST', headers: { 'Content-Type': 'application/json' } });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(404);
  });

  it('should return 405 for wrong method', async () => {
    const request = new Request('http://localhost/v1/interpret', { method: 'GET' });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(405);
  });

  it('should return 415 for wrong content type', async () => {
    const request = new Request('http://localhost/v1/interpret', { method: 'POST' });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(415);
  });

  it('should return 413 for oversized requests', async () => {
    const hugeBody = 'x'.repeat(131073);
    const request = new Request('http://localhost/v1/interpret', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: hugeBody
    });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(413);
  });

  it('should return 400 for invalid schemas on /v1/interpret', async () => {
    const request = new Request('http://localhost/v1/interpret', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ missingFields: true })
    });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(400);
  });

  it('should return 400 if verification candidates exceed 25', async () => {
    const request = new Request('http://localhost/v1/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        requestId: '123',
        originalQuery: 'test',
        mediaType: 'movie',
        requiredConceptGroups: [],
        excludedConcepts: [],
        hardConstraints: {},
        candidates: new Array(26).fill({
          candidateId: "movie:123", tmdbId: 123, mediaType: "movie", title: "T", originalTitle: "T", overview: "O", genres: [], keywords: [], releaseYear: 2020, directorOrCreators: [], principalCast: []
        })
      })
    });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(400);
  });
});
