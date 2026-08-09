import { ZodError } from 'zod';
import { processRecommendation } from './engine';
import { RecommendationRequestSchema } from './schemas';
import { createCursor, parseCursor, RecommendationSession, requestFingerprint } from './session';
import { RecommendationEnv, RecommendationResponse, ServiceError } from './types';

export { RecommendationSession };

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' };
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });

function errorResponse(error: unknown): Response {
  if (error instanceof ServiceError) return json({ error: { code: error.code, message: error.message, retryable: error.retryable } }, error.status);
  if (error instanceof ZodError) return json({ error: { code: 'INVALID_REQUEST', message: 'The recommendation request is invalid', retryable: false, issues: error.issues } }, 400);
  if (error instanceof SyntaxError) return json({ error: { code: 'INVALID_JSON', message: 'The request body is not valid JSON', retryable: false } }, 400);
  console.error('Unhandled recommendation error', error);
  return json({ error: { code: 'INTERNAL_ERROR', message: 'An unexpected error occurred', retryable: true } }, 500);
}

async function routeRecommendation(request: Request, env: RecommendationEnv): Promise<Response> {
  const contentLength = Number(request.headers.get('content-length') || 0);
  if (contentLength > 131_072) return json({ error: { code: 'PAYLOAD_TOO_LARGE', message: 'Payload exceeds 128 KiB', retryable: false } }, 413);
  const raw = await request.text();
  if (raw.length > 131_072) return json({ error: { code: 'PAYLOAD_TOO_LARGE', message: 'Payload exceeds 128 KiB', retryable: false } }, 413);
  const parsed = RecommendationRequestSchema.parse(JSON.parse(raw));
  const fingerprintInput = { requestId: parsed.requestId, mode: parsed.mode, query: parsed.query, mediaType: parsed.mediaType, anchor: parsed.anchor, filters: parsed.filters };
  const fingerprint = await requestFingerprint(fingerprintInput);
  const sessionId = parsed.requestId;
  let offset = 0;
  if (parsed.cursor) {
    const cursor = await parseCursor(env.CURSOR_SIGNING_SECRET, parsed.cursor);
    if (cursor.sessionId !== sessionId || cursor.requestId !== parsed.requestId || cursor.fingerprint !== fingerprint) {
      throw new ServiceError('INVALID_CURSOR', 'The cursor does not match this request', 400, false);
    }
    offset = cursor.offset;
  }

  const stub = env.RECOMMENDATION_SESSIONS.get(env.RECOMMENDATION_SESSIONS.idFromName(sessionId)) as DurableObjectStub<RecommendationSession>;
  if (!parsed.cursor) {
    const status = await stub.getStatus(fingerprint);
    if (!status.exists) await stub.store(fingerprint, await processRecommendation(env, parsed));
  }
  const page = await stub.getPage(fingerprint, offset, parsed.pageSize);
  const nextCursor = page.nextOffset === null ? null : await createCursor(env.CURSOR_SIGNING_SECRET, {
    v: 1, sessionId, requestId: parsed.requestId, fingerprint, offset: page.nextOffset,
  });
  const response: RecommendationResponse = { requestId: parsed.requestId, results: page.results, nextCursor, hasMore: nextCursor !== null };
  return json(response);
}

export default {
  async fetch(request: Request, env: RecommendationEnv): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') {
      return json({ status: 'ok', service: 'aliflix-recommendations', geminiConfigured: Boolean(env.GEMINI_API_KEY), tmdbConfigured: Boolean(env.TMDB_API_KEY || env.TMDB_READ_ACCESS_TOKEN) });
    }
    if (url.pathname !== '/v3/recommendations') return json({ error: { code: 'NOT_FOUND', message: 'Not found', retryable: false } }, 404);
    if (request.method !== 'POST') return json({ error: { code: 'METHOD_NOT_ALLOWED', message: 'Method not allowed', retryable: false } }, 405);
    if (!request.headers.get('content-type')?.toLowerCase().includes('application/json')) return json({ error: { code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Expected application/json', retryable: false } }, 415);
    try {
      const key = request.headers.get('cf-connecting-ip') || 'unknown';
      const limit = await env.RECOMMENDATION_RATE_LIMITER.limit({ key });
      if (!limit.success) throw new ServiceError('RATE_LIMITED', 'Too many recommendation requests', 429, true);
      return await routeRecommendation(request, env);
    } catch (error) { return errorResponse(error); }
  },
} satisfies ExportedHandler<RecommendationEnv>;
