import { Env, callGemini } from './gemini';
import { rateLimiter } from './rateLimit';
import {
  INTERPRETATION_PROMPT,
  EXPANSION_PROMPT,
  VERIFICATION_PROMPT,
  INTERPRET_V2_PROMPT,
  VERIFY_PLOTS_PROMPT,
  ANCHOR_PROFILE_PROMPT,
} from './prompts';
import {
  InterpretationRequestSchema,
  ExpansionRequestSchema,
  VerificationRequestSchema,
  OmdbMetadataRequestSchema,
  OmdbBatchRequestSchema,
  GeminiInterpretationSchema,
  GeminiExpansionSchema,
  GeminiVerificationSchema,
  InterpretV2RequestSchema,
  GeminiInterpretV2Schema,
  VerifyPlotsRequestSchema,
  GeminiVerifyPlotsSchema,
  ProfileAnchorRequestSchema,
  GeminiProfileAnchorSchema,
} from './schemas';
import { lookupOmdbTitle, normalizeOmdbResponse } from './omdb';

async function processBatchConcurrently<T, R>(
  items: T[],
  limit: number,
  fn: (item: T) => Promise<R>
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let index = 0;
  async function worker() {
    while (index < items.length) {
      const i = index++;
      results[i] = await fn(items[i]);
    }
  }
  const workers = Array.from({ length: Math.min(limit, items.length) }, () => worker());
  await Promise.all(workers);
  return results;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    const knownRoutes = [
      '/v1/interpret',
      '/v1/expand',
      '/v1/verify',
      '/v1/metadata/omdb',
      '/v1/metadata/omdb/batch',
      '/v2/recommendations/interpret',
      '/v2/recommendations/verify-plots',
      '/v2/recommendations/profile-anchor',
      '/health',
    ];
    if (!knownRoutes.includes(url.pathname)) {
      return new Response('Not Found', { status: 404 });
    }

    if (url.pathname === '/health' && request.method === 'GET') {
      return new Response(
        JSON.stringify({
          status: 'ok',
          service: 'aliflix-recommendations',
          geminiConfigured: !!env.GEMINI_API_KEY,
          omdbConfigured: !!env.OMDB_API_KEY,
          omdbCacheConfigured: !!env.OMDB_CACHE,
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }
      );
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

      // 128KB limit
      if (text.length > 131072) {
        return new Response(JSON.stringify({ error: 'Payload Too Large' }), {
          status: 413,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      const body = JSON.parse(text);

      if (url.pathname === '/v1/metadata/omdb') {
        const parsed = OmdbMetadataRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const res = await lookupOmdbTitle(env, parsed.data);
        if (res.status === 'UNAVAILABLE') {
          return new Response(JSON.stringify({ error: 'OMDb service unavailable' }), {
            status: 503,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const payload = res.metadata || normalizeOmdbResponse(null);
        return new Response(JSON.stringify(payload), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v1/metadata/omdb/batch') {
        const parsed = OmdbBatchRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        if (parsed.data.titles.length > 25) {
          return new Response(JSON.stringify({ error: 'Too many candidates' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const results = await processBatchConcurrently(
          parsed.data.titles,
          5,
          async (cand) => {
            const lookupRes = await lookupOmdbTitle(env, {
              imdbId: cand.imdbId,
              title: cand.title,
              year: cand.year,
              mediaType: cand.mediaType,
            });
            return {
              candidateId: cand.candidateId,
              metadata: lookupRes.metadata,
              status: lookupRes.status,
            };
          }
        );

        return new Response(JSON.stringify({ results }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v1/interpret') {
        const parsed = InterpretationRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (parsed.data.query.length > 1000) {
          return new Response(JSON.stringify({ error: 'Query too long' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const geminiRes = await callGemini(
          env,
          INTERPRETATION_PROMPT,
          parsed.data,
          GeminiInterpretationSchema
        );
        return new Response(JSON.stringify(geminiRes), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v1/expand') {
        const parsed = ExpansionRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const geminiRes = await callGemini(
          env,
          EXPANSION_PROMPT,
          parsed.data,
          GeminiExpansionSchema
        );
        return new Response(JSON.stringify(geminiRes), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v1/verify') {
        const parsed = VerificationRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (parsed.data.candidates.length > 25) {
          return new Response(JSON.stringify({ error: 'Too many candidates' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const geminiRes = await callGemini(
          env,
          VERIFICATION_PROMPT,
          parsed.data,
          GeminiVerificationSchema
        );
        return new Response(JSON.stringify(geminiRes), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v2/recommendations/interpret') {
        const parsed = InterpretV2RequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const geminiRes = await callGemini(
          env,
          INTERPRET_V2_PROMPT,
          parsed.data,
          GeminiInterpretV2Schema
        );
        return new Response(JSON.stringify(geminiRes), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v2/recommendations/verify-plots') {
        const parsed = VerifyPlotsRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (parsed.data.candidates.length > 25) {
          return new Response(JSON.stringify({ error: 'Too many candidates' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const geminiRes = await callGemini(
          env,
          VERIFY_PLOTS_PROMPT,
          parsed.data,
          GeminiVerifyPlotsSchema
        );
        return new Response(JSON.stringify(geminiRes), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.pathname === '/v2/recommendations/profile-anchor') {
        const parsed = ProfileAnchorRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          });
        }

        const geminiRes = await callGemini(
          env,
          ANCHOR_PROFILE_PROMPT,
          parsed.data,
          GeminiProfileAnchorSchema
        );
        return new Response(JSON.stringify(geminiRes), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      return new Response('Not Found', { status: 404 });
    } catch (error: any) {
      if (error.message === 'GEMINI_RATE_LIMIT') {
        return new Response(JSON.stringify({ error: 'Upstream Rate Limit' }), {
          status: 429,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (error.message === 'GEMINI_TIMEOUT') {
        return new Response(JSON.stringify({ error: 'Gateway Timeout' }), {
          status: 504,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (error.message === 'GEMINI_SERVER_ERROR') {
        return new Response(JSON.stringify({ error: 'Bad Gateway' }), {
          status: 502,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      return new Response(JSON.stringify({ error: 'Internal Server Error' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' },
      });
    }
  },
};
