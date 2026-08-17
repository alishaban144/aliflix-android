import { describe, it, expect, vi } from 'vitest';
import worker from '../src/index';

describe('Cloudflare Worker', () => {
  const env: any = {
    GEMINI_API_KEY: 'test-key',
    TMDB_API_KEY: 'test-key',
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
});
