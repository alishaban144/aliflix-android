import { ZodError } from 'zod';
import { processFilterDiscoveryPage, processRecommendation, supportsDirectFilterPagination } from './engine';
import { ParsedRecommendationRequest, RecommendationRequestSchema } from './schemas';
import { ContinuationReservation, createCursor, parseCursor, RecommendationSession, requestFingerprint } from './session';
import { RecommendationEnv, RecommendationResponse, RecommendationResult, ServiceError } from './types';
import { companySearch, editorialPicks, homeFeed, personCredits, titleDetails, titleSearch, tvNetworkFeed } from './catalog';
import { downloadSubdlSubtitle, searchSubdlSubtitles } from './subdl';

export { RecommendationSession };

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' };
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
const catalogJson = (body: unknown): Response => new Response(JSON.stringify(body), {
  status: 200,
  headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=300' },
});
// A continuation normally needs one fresh engine pass. If that pass does not
// fill the requested page, one bounded retry explores the next deterministic
// TMDB lanes instead of stopping after an undersized batch. This spends no
// extra provider request when the first pass already fills the page.
const MAX_CONTINUATION_ATTEMPTS_PER_CLICK = 2;

interface ContinuationSessionApi {
  reserveContinuation(fingerprint: string): Promise<ContinuationReservation>;
  completeContinuation(
    fingerprint: string,
    pass: number,
    results: RecommendationResult[],
  ): Promise<{ added: number; count: number; exhausted: boolean }>;
  releaseContinuation(fingerprint: string, pass: number): Promise<void>;
}

function boundedExcludedTitles(
  explicitTitles: string[],
  previouslyShownTitles: string[],
  limit = 100,
): string[] {
  const explicit = [...new Set(explicitTitles)].slice(0, limit);
  const explicitSet = new Set(explicit);
  const history = [...new Set(previouslyShownTitles)].filter(title => !explicitSet.has(title));
  const remaining = limit - explicit.length;
  if (remaining <= 0 || history.length <= remaining) return [...explicit, ...history.slice(0, remaining)];
  const earlyCount = Math.ceil(remaining / 2);
  const recentCount = remaining - earlyCount;
  return [
    ...explicit,
    ...history.slice(0, earlyCount),
    ...history.slice(-recentCount),
  ];
}

function continuationRequest(
  parsed: ParsedRecommendationRequest,
  reservation: ContinuationReservation,
): ParsedRecommendationRequest {
  return {
    ...parsed,
    cursor: undefined,
    filters: {
      ...parsed.filters,
      excludedTmdbIds: [...new Set([
        ...parsed.filters.excludedTmdbIds,
        ...reservation.excludedTmdbIds,
      ])].slice(0, 1_000),
      excludedTitles: boundedExcludedTitles(
        parsed.filters.excludedTitles,
        reservation.excludedTitles,
      ),
    },
  };
}

export async function expandGeneratedContinuation(
  env: RecommendationEnv,
  parsed: ParsedRecommendationRequest,
  fingerprint: string,
  offset: number,
  stub: ContinuationSessionApi,
  runRecommendation: typeof processRecommendation = processRecommendation,
): Promise<void> {
  const target = parsed.pageSize;
  let availableAfterExpansion = 0;
  for (let attempt = 0; attempt < MAX_CONTINUATION_ATTEMPTS_PER_CLICK; attempt++) {
    const reservation = await stub.reserveContinuation(fingerprint);
    if (!reservation.canExpand || reservation.pass === null) break;
    let expanded: RecommendationResult[];
    try {
      expanded = await runRecommendation(
        env,
        continuationRequest(parsed, reservation),
        {},
        { continuationPass: reservation.pass },
      );
    } catch (error) {
      await stub.releaseContinuation(fingerprint, reservation.pass);
      if (availableAfterExpansion > 0) {
        console.warn(JSON.stringify({
          event: 'generated_continuation_retry_skipped',
          requestId: parsed.requestId,
          mode: parsed.mode,
          pass: reservation.pass,
          available: availableAfterExpansion,
        }));
        break;
      }
      throw error;
    }
    const completed = await stub.completeContinuation(fingerprint, reservation.pass, expanded);
    availableAfterExpansion = Math.max(0, completed.count - offset);
    console.log(JSON.stringify({
      event: 'generated_continuation_completed',
      requestId: parsed.requestId,
      mode: parsed.mode,
      pass: reservation.pass,
      added: completed.added,
      available: availableAfterExpansion,
      target,
      total: completed.count,
      exhausted: completed.exhausted,
    }));
    if (completed.exhausted || availableAfterExpansion >= target) break;
  }
}

async function enforceRateLimit(request: Request, env: RecommendationEnv): Promise<void> {
  const key = request.headers.get('cf-connecting-ip') || 'unknown';
  const limit = await env.RECOMMENDATION_RATE_LIMITER.limit({ key });
  if (!limit.success) throw new ServiceError('RATE_LIMITED', 'Too many requests', 429, true);
}

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
  let rawJson: unknown;
  try {
    rawJson = JSON.parse(raw);
  } catch {
    throw new ServiceError('INVALID_JSON', 'The request body is not valid JSON', 400, false);
  }
  const parsed = RecommendationRequestSchema.parse(rawJson);
  const fingerprintInput = {
    requestId: parsed.requestId,
    mode: parsed.mode,
    aiModel: parsed.aiModel || parsed.geminiModel,
    query: parsed.query,
    mediaType: parsed.mediaType,
    anchor: parsed.anchor,
    anchors: parsed.anchors,
    previousQuery: parsed.previousQuery,
    refinementQuery: parsed.refinementQuery,
    filters: parsed.filters,
    pageSize: parsed.pageSize,
  };
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

  if (supportsDirectFilterPagination(parsed)) {
    const page = await processFilterDiscoveryPage(env, parsed, offset);
    const nextCursor = page.nextOffset === null ? null : await createCursor(env.CURSOR_SIGNING_SECRET, {
      v: 1, sessionId, requestId: parsed.requestId, fingerprint, offset: page.nextOffset,
    });
    const response: RecommendationResponse = {
      requestId: parsed.requestId,
      results: page.results,
      totalResults: page.totalResults,
      nextCursor,
      hasMore: nextCursor !== null,
    };
    return json(response);
  }

  const stub = env.RECOMMENDATION_SESSIONS.getByName(sessionId);
  if (!parsed.cursor) {
    const status = await stub.getStatus(fingerprint);
    if (!status.exists) {
      await stub.store(
        fingerprint,
        await processRecommendation(env, parsed),
        parsed.mode !== 'filters',
      );
    }
  } else if (parsed.mode !== 'filters') {
    await expandGeneratedContinuation(env, parsed, fingerprint, offset, stub);
  }
  const page = await stub.getPage(fingerprint, offset, parsed.pageSize);
  const nextCursor = page.nextOffset === null ? null : await createCursor(env.CURSOR_SIGNING_SECRET, {
    v: 1, sessionId, requestId: parsed.requestId, fingerprint, offset: page.nextOffset,
  });
  const response: RecommendationResponse = {
    requestId: parsed.requestId,
    results: page.results,
    totalResults: page.totalCount,
    nextCursor,
    hasMore: nextCursor !== null,
  };
  return json(response);
}

export default {
  async fetch(request: Request, env: RecommendationEnv): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') {
      return json({
        status: 'ok',
        service: 'aliflix-recommendations',
        geminiConfigured: Boolean(env.GEMINI_API_KEY),
        groqConfigured: Boolean(env.GROQ_API_KEY),
        tmdbConfigured: Boolean(env.TMDB_API_KEY || env.TMDB_READ_ACCESS_TOKEN),
        subdlConfigured: Boolean(env.SUBDL_API_KEY),
      });
    }
    if (url.pathname === '/v3/subtitles' && request.method === 'GET') {
      try {
        await enforceRateLimit(request, env);
        return json(await searchSubdlSubtitles(env, url));
      } catch (error) { return errorResponse(error); }
    }
    const subtitleDownloadMatch = /^\/v3\/subtitles\/download\/([A-Za-z0-9_-]+)$/.exec(url.pathname);
    if (subtitleDownloadMatch && request.method === 'GET') {
      try {
        await enforceRateLimit(request, env);
        return await downloadSubdlSubtitle(env, subtitleDownloadMatch[1]);
      } catch (error) { return errorResponse(error); }
    }
    const titleMatch = /^\/v3\/titles\/(movie|tv)\/(\d+)$/.exec(url.pathname);
    const personMatch = /^\/v3\/people\/(\d+)\/credits$/.exec(url.pathname);
    const isEditorialPicks = url.pathname === '/v3/editorial-picks';
    const isHomeFeed = url.pathname === '/v3/home';
    const isTitleSearch = url.pathname === '/v3/search/titles';
    const isCompanySearch = url.pathname === '/v3/search/companies';
    const isTvNetworkFeed = url.pathname === '/v3/tv-networks';
    if (titleMatch || personMatch || isEditorialPicks || isHomeFeed || isTitleSearch || isCompanySearch || isTvNetworkFeed) {
      if (request.method !== 'GET') return json({ error: { code: 'METHOD_NOT_ALLOWED', message: 'Method not allowed', retryable: false } }, 405);
      try {
        await enforceRateLimit(request, env);
        if (titleMatch) return catalogJson(await titleDetails(env, titleMatch[1] as 'movie' | 'tv', Number(titleMatch[2])));
        if (personMatch) {
          return catalogJson(await personCredits(env, Number(personMatch[1])));
        }
        if (isTitleSearch) return catalogJson(await titleSearch(env, url.searchParams.get('query') || ''));
        if (isCompanySearch) return catalogJson(await companySearch(env, url.searchParams.get('query') || ''));
        if (isTvNetworkFeed) {
          const requestedRegion = (url.searchParams.get('region') || 'US').trim().toUpperCase();
          const region = /^[A-Z]{2}$/.test(requestedRegion) ? requestedRegion : 'US';
          return catalogJson(await tvNetworkFeed(env, region));
        }
        if (isHomeFeed) return catalogJson(await homeFeed(env));
        return catalogJson(await editorialPicks(env));
      } catch (error) { return errorResponse(error); }
    }
    if (url.pathname !== '/v3/recommendations') return json({ error: { code: 'NOT_FOUND', message: 'Not found', retryable: false } }, 404);
    if (request.method !== 'POST') return json({ error: { code: 'METHOD_NOT_ALLOWED', message: 'Method not allowed', retryable: false } }, 405);
    if (!request.headers.get('content-type')?.toLowerCase().includes('application/json')) return json({ error: { code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Expected application/json', retryable: false } }, 415);
    try {
      await enforceRateLimit(request, env);
      return await routeRecommendation(request, env);
    } catch (error) { return errorResponse(error); }
  },
} satisfies ExportedHandler<RecommendationEnv>;
