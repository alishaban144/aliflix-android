import { Env, callGemini } from './gemini';
import { rateLimiter } from './rateLimit';
import {
  INTERPRETATION_PROMPT,
  EXPANSION_PROMPT,
  VERIFICATION_PROMPT,
} from './prompts';
import {
  InterpretationRequestSchema,
  ExpansionRequestSchema,
  VerificationRequestSchema,
  GeminiInterpretationSchema,
  GeminiExpansionSchema,
  GeminiVerificationSchema
} from './schemas';

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    const knownRoutes = ['/v1/interpret', '/v1/expand', '/v1/verify', '/health'];
    if (!knownRoutes.includes(url.pathname)) {
      return new Response('Not Found', { status: 404 });
    }

    if (url.pathname === '/health' && request.method === 'GET') {
      return new Response(JSON.stringify({ status: 'ok', service: 'aliflix-recommendations' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
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
      return new Response(JSON.stringify({ error: 'Too Many Requests' }), { status: 429, headers: { 'Content-Type': 'application/json' } });
    }

    try {
      const clonedReq = request.clone();
      const text = await clonedReq.text();
      
      // 128KB limit
      if (text.length > 131072) {
        return new Response(JSON.stringify({ error: 'Payload Too Large' }), { status: 413, headers: { 'Content-Type': 'application/json' } });
      }

      const body = JSON.parse(text);

      if (url.pathname === '/v1/interpret') {
        const parsed = InterpretationRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true); // Stricter limit on malformed
          return new Response(JSON.stringify({ error: 'Bad Request' }), { status: 400, headers: { 'Content-Type': 'application/json' } });
        }
        if (parsed.data.query.length > 1000) {
          return new Response(JSON.stringify({ error: 'Query too long' }), { status: 400, headers: { 'Content-Type': 'application/json' } });
        }
        
        const geminiRes = await callGemini(env, INTERPRETATION_PROMPT, parsed.data, GeminiInterpretationSchema);
        return new Response(JSON.stringify(geminiRes), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }

      if (url.pathname === '/v1/expand') {
        const parsed = ExpansionRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), { status: 400, headers: { 'Content-Type': 'application/json' } });
        }
        
        const geminiRes = await callGemini(env, EXPANSION_PROMPT, parsed.data, GeminiExpansionSchema);
        return new Response(JSON.stringify(geminiRes), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }

      if (url.pathname === '/v1/verify') {
        const parsed = VerificationRequestSchema.safeParse(body);
        if (!parsed.success) {
          rateLimiter.check(ip, true);
          return new Response(JSON.stringify({ error: 'Bad Request' }), { status: 400, headers: { 'Content-Type': 'application/json' } });
        }
        if (parsed.data.candidates.length > 25) {
          return new Response(JSON.stringify({ error: 'Too many candidates' }), { status: 400, headers: { 'Content-Type': 'application/json' } });
        }

        const geminiRes = await callGemini(env, VERIFICATION_PROMPT, parsed.data, GeminiVerificationSchema);
        return new Response(JSON.stringify(geminiRes), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }

      return new Response('Not Found', { status: 404 });
    } catch (error: any) {
      if (error.message === 'GEMINI_RATE_LIMIT') {
        return new Response(JSON.stringify({ error: 'Upstream Rate Limit' }), { status: 429, headers: { 'Content-Type': 'application/json' } });
      }
      if (error.message === 'GEMINI_TIMEOUT') {
        return new Response(JSON.stringify({ error: 'Gateway Timeout' }), { status: 504, headers: { 'Content-Type': 'application/json' } });
      }
      if (error.message === 'GEMINI_SERVER_ERROR') {
        return new Response(JSON.stringify({ error: 'Bad Gateway' }), { status: 502, headers: { 'Content-Type': 'application/json' } });
      }
      
      // Sanitized generic error
      return new Response(JSON.stringify({ error: 'Internal Server Error' }), { status: 500, headers: { 'Content-Type': 'application/json' } });
    }
  },
}
