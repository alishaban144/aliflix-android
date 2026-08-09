import { Env } from './gemini';
import { rateLimiter } from './rateLimit';
import { processRecommendation } from './engine';

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/health' && request.method === 'GET') {
      return new Response(
        JSON.stringify({
          status: 'ok',
          service: 'aliflix-recommendations',
          geminiConfigured: !!env.GEMINI_API_KEY,
          tmdbConfigured: !!env.TMDB_API_KEY,
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }
      );
    }

    if (url.pathname !== '/v3/recommendations') {
      return new Response('Not Found', { status: 404 });
    }

    if (request.method !== 'POST') {
      return new Response('Method Not Allowed', { status: 405 });
    }

    const contentType = request.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
      return new Response('Unsupported Media Type', { status: 415 });
    }

    const ip = request.headers.get('cf-connecting-ip') || 'unknown';
    if (!rateLimiter.check(ip, false)) {
      return new Response(JSON.stringify({ error: 'Too Many Requests' }), {
        status: 429,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    try {
      const clonedReq = request.clone();
      const text = await clonedReq.text();

      if (text.length > 131072) {
        return new Response(JSON.stringify({ error: 'Payload Too Large' }), {
          status: 413,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      const body = JSON.parse(text);

      const responseBody = await processRecommendation(env, body);

      return new Response(JSON.stringify(responseBody), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });

    } catch (error: any) {
      if (error.name === 'TmdbError') {
        return new Response(JSON.stringify({ error: 'TMDB Service Error', details: error.message }), {
          status: 503,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      
      if (error.message === 'GEMINI_RATE_LIMIT') {
        return new Response(JSON.stringify({ error: 'Upstream Rate Limit' }), {
          status: 429,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (error.message === 'GEMINI_TIMEOUT' || error.message === 'GEMINI_EMBEDDING_TIMEOUT') {
        return new Response(JSON.stringify({ error: 'Gateway Timeout' }), {
          status: 504,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (error.message === 'GEMINI_SERVER_ERROR' || error.message?.includes('GEMINI_EMBEDDING_ERROR')) {
        return new Response(JSON.stringify({ error: 'Bad Gateway' }), {
          status: 502,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      console.error(error);
      return new Response(JSON.stringify({ error: 'Internal Server Error' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' },
      });
    }
  },
};
