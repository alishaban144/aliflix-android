import { RecommendationEnv, ServiceError } from './types';

const SUBDL_SEARCH_URL = 'https://api.subdl.com/api/v1/subtitles';
const SUBDL_DOWNLOAD_ORIGIN = 'https://dl.subdl.com';
const SUBDL_TIMEOUT_MILLIS = 12_000;
const MAX_SUBDL_JSON_BYTES = 1_048_576;
const MAX_SUBTITLE_FILE_BYTES = 8_388_608;

interface SubdlSearchDocument {
  status?: boolean;
  error?: string;
  results?: unknown[];
  subtitles?: unknown[];
}

interface SubdlTrackCandidate {
  url: string;
  releaseName: string;
  fileName: string;
  languageCode: string;
  languageName: string;
  hearingImpaired: boolean;
  format: string;
  season?: number;
  episode?: number;
  fps?: string;
  fullSeason: boolean;
}

export interface SubtitleTrackResponse {
  id: string;
  languageCode: string;
  languageName: string;
  releaseName: string;
  fileName: string;
  hearingImpaired: boolean;
  format: string;
  fps?: string;
  downloadToken: string;
}

export interface SubtitleSearchResponse {
  mediaKey: string;
  tracks: SubtitleTrackResponse[];
}

function requiredSubdlKey(env: RecommendationEnv): string {
  const key = env.SUBDL_API_KEY?.trim();
  if (!key) {
    throw new ServiceError(
      'SUBDL_NOT_CONFIGURED',
      'Subtitles are not configured yet',
      503,
      false,
    );
  }
  return key;
}

function positiveInteger(value: string | null, name: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new ServiceError('INVALID_SUBTITLE_REQUEST', `${name} must be a positive integer`, 400, false);
  }
  return parsed;
}

function nonNegativeInteger(value: string | null, name: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new ServiceError('INVALID_SUBTITLE_REQUEST', `${name} must be a non-negative integer`, 400, false);
  }
  return parsed;
}

function optionalInteger(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isInteger(value) && value >= 0) return value;
  if (typeof value === 'string' && /^\d+$/.test(value.trim())) return Number(value);
  return undefined;
}

function optionalString(record: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return '';
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function languageLabel(record: Record<string, unknown>, parent?: Record<string, unknown>): {
  code: string;
  name: string;
} {
  const raw = optionalString(record, 'language', 'lang') ||
    (parent ? optionalString(parent, 'language', 'lang') : '') ||
    'Unknown';
  const code = raw.length <= 4 ? raw.toUpperCase() : raw.slice(0, 3).toUpperCase();
  const name = optionalString(record, 'language_name') ||
    (parent ? optionalString(parent, 'language_name') : '') ||
    raw.replace(/\b\w/g, character => character.toUpperCase());
  return { code, name };
}

function toCandidate(
  record: Record<string, unknown>,
  parent?: Record<string, unknown>,
): SubdlTrackCandidate | null {
  const url = optionalString(record, 'url');
  if (!url) return null;
  const language = languageLabel(record, parent);
  const format = (optionalString(record, 'format') || optionalString(parent || {}, 'format') ||
    (/\.zip(?:$|\?)/i.test(url) ? 'zip' : '')).toLowerCase();
  const releaseName = optionalString(record, 'release_name') ||
    optionalString(parent || {}, 'release_name') ||
    optionalString(record, 'name') ||
    'SubDL subtitle';
  const fileName = optionalString(record, 'name') || releaseName;
  const hi = record.hi ?? parent?.hi;
  return {
    url,
    releaseName,
    fileName,
    languageCode: language.code,
    languageName: language.name,
    hearingImpaired: hi === true || hi === 1 || hi === '1',
    format,
    season: optionalInteger(record.season) ?? optionalInteger(parent?.season),
    episode: optionalInteger(record.episode) ?? optionalInteger(parent?.episode),
    fps: optionalString(record, 'fps') || optionalString(parent || {}, 'fps') || undefined,
    fullSeason: record.full_season === true || parent?.full_season === true,
  };
}

function exactEpisode(candidate: SubdlTrackCandidate, season: number, episode: number): boolean {
  if (candidate.season !== undefined && candidate.season !== season) return false;
  if (candidate.episode !== undefined && candidate.episode !== episode) return false;
  if (candidate.fullSeason && (candidate.season === undefined || candidate.episode === undefined)) return false;
  return true;
}

function encodeDownloadToken(rawUrl: string): string | null {
  let target: URL;
  try {
    target = new URL(rawUrl, SUBDL_DOWNLOAD_ORIGIN);
  } catch {
    return null;
  }
  if (
    target.protocol !== 'https:' || target.hostname !== 'dl.subdl.com' || target.port ||
    target.username || target.password || !target.pathname.startsWith('/subtitle/') ||
    target.pathname.includes('..') || target.pathname.length > 600
  ) {
    return null;
  }
  const path = target.pathname;
  return btoa(path).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export function decodeSubdlDownloadToken(token: string): URL | null {
  if (!/^[A-Za-z0-9_-]{8,900}$/.test(token)) return null;
  try {
    const padded = token.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - token.length % 4) % 4);
    const path = atob(padded);
    const target = new URL(path, SUBDL_DOWNLOAD_ORIGIN);
    if (
      target.protocol !== 'https:' || target.hostname !== 'dl.subdl.com' || target.port ||
      target.username || target.password || !target.pathname.startsWith('/subtitle/') ||
      target.pathname.includes('..') || target.search || target.hash || target.pathname.length > 600
    ) {
      return null;
    }
    return target;
  } catch {
    return null;
  }
}

function collectCandidates(document: SubdlSearchDocument): SubdlTrackCandidate[] {
  const candidates: SubdlTrackCandidate[] = [];
  for (const rawSubtitle of document.subtitles || []) {
    const subtitle = asRecord(rawSubtitle);
    if (!subtitle) continue;
    const unpackFiles = Array.isArray(subtitle.unpack_files) ? subtitle.unpack_files : [];
    if (unpackFiles.length > 0) {
      for (const rawFile of unpackFiles) {
        const file = asRecord(rawFile);
        const candidate = file ? toCandidate(file, subtitle) : null;
        if (candidate) candidates.push(candidate);
      }
    } else {
      const candidate = toCandidate(subtitle);
      if (candidate) candidates.push(candidate);
    }
  }
  return candidates;
}

export function parseSubdlSearchDocument(
  document: SubdlSearchDocument,
  mediaType: 'movie' | 'tv',
  tmdbId: number,
  season?: number,
  episode?: number,
): SubtitleTrackResponse[] {
  if (document.status === false) {
    throw new ServiceError('SUBDL_ERROR', document.error || 'SubDL could not search for subtitles', 502, true);
  }
  const resultMatches = (document.results || []).some(rawResult => {
    const result = asRecord(rawResult);
    return result !== null && optionalInteger(result.tmdb_id) === tmdbId &&
      (!optionalString(result, 'type') || optionalString(result, 'type').toLowerCase() === mediaType);
  });
  if (!resultMatches) return [];

  const seen = new Set<string>();
  const tracks: SubtitleTrackResponse[] = [];
  for (const candidate of collectCandidates(document)) {
    if (mediaType === 'tv' && !exactEpisode(candidate, season!, episode!)) continue;
    const downloadToken = encodeDownloadToken(candidate.url);
    if (!downloadToken || seen.has(downloadToken)) continue;
    seen.add(downloadToken);
    tracks.push({
      id: downloadToken,
      languageCode: candidate.languageCode,
      languageName: candidate.languageName,
      releaseName: candidate.releaseName,
      fileName: candidate.fileName,
      hearingImpaired: candidate.hearingImpaired,
      format: candidate.format,
      ...(candidate.fps ? { fps: candidate.fps } : {}),
      downloadToken,
    });
    if (tracks.length >= 30) break;
  }
  return tracks;
}

async function fetchWithTimeout(url: URL, init: RequestInit): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), SUBDL_TIMEOUT_MILLIS);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ServiceError('SUBDL_TIMEOUT', 'SubDL took too long to respond', 504, true);
    }
    throw new ServiceError('SUBDL_UNAVAILABLE', 'SubDL is temporarily unavailable', 502, true);
  } finally {
    clearTimeout(timeout);
  }
}

export async function searchSubdlSubtitles(
  env: RecommendationEnv,
  requestUrl: URL,
): Promise<SubtitleSearchResponse> {
  const key = requiredSubdlKey(env);
  const mediaType = requestUrl.searchParams.get('type');
  if (mediaType !== 'movie' && mediaType !== 'tv') {
    throw new ServiceError('INVALID_SUBTITLE_REQUEST', 'type must be movie or tv', 400, false);
  }
  const tmdbId = positiveInteger(requestUrl.searchParams.get('tmdbId'), 'tmdbId');
  const season = mediaType === 'tv'
    ? nonNegativeInteger(requestUrl.searchParams.get('season'), 'season')
    : undefined;
  const episode = mediaType === 'tv'
    ? positiveInteger(requestUrl.searchParams.get('episode'), 'episode')
    : undefined;

  const upstreamUrl = new URL(SUBDL_SEARCH_URL);
  upstreamUrl.searchParams.set('api_key', key);
  upstreamUrl.searchParams.set('tmdb_id', String(tmdbId));
  upstreamUrl.searchParams.set('type', mediaType);
  upstreamUrl.searchParams.set('subs_per_page', '30');
  upstreamUrl.searchParams.set('unpack', '1');
  upstreamUrl.searchParams.set('releases', '1');
  upstreamUrl.searchParams.set('hi', '1');
  upstreamUrl.searchParams.set('client', 'custom_integration');
  if (season !== undefined && episode !== undefined) {
    upstreamUrl.searchParams.set('full_season', '1');
    upstreamUrl.searchParams.set('season_number', String(season));
    upstreamUrl.searchParams.set('episode_number', String(episode));
  }

  const response = await fetchWithTimeout(upstreamUrl, { headers: { accept: 'application/json' } });
  if (!response.ok) {
    throw new ServiceError('SUBDL_UNAVAILABLE', 'SubDL could not search for subtitles', 502, response.status >= 500);
  }
  const contentLength = Number(response.headers.get('content-length') || 0);
  if (contentLength > MAX_SUBDL_JSON_BYTES) {
    throw new ServiceError('SUBDL_INVALID_RESPONSE', 'SubDL returned an invalid response', 502, true);
  }
  const raw = await response.text();
  if (raw.length > MAX_SUBDL_JSON_BYTES) {
    throw new ServiceError('SUBDL_INVALID_RESPONSE', 'SubDL returned an invalid response', 502, true);
  }
  let document: SubdlSearchDocument;
  try {
    document = JSON.parse(raw) as SubdlSearchDocument;
  } catch {
    throw new ServiceError('SUBDL_INVALID_RESPONSE', 'SubDL returned an invalid response', 502, true);
  }
  return {
    mediaKey: mediaType === 'movie'
      ? `movie:${tmdbId}`
      : `tv:${tmdbId}:s${season}:e${episode}`,
    tracks: parseSubdlSearchDocument(document, mediaType, tmdbId, season, episode),
  };
}

export async function downloadSubdlSubtitle(
  env: RecommendationEnv,
  token: string,
): Promise<Response> {
  const key = requiredSubdlKey(env);
  const target = decodeSubdlDownloadToken(token);
  if (!target) {
    throw new ServiceError('INVALID_SUBTITLE_DOWNLOAD', 'Invalid subtitle download', 400, false);
  }
  let response = await fetchWithTimeout(target, {
    headers: {
      accept: 'application/zip, text/plain, text/vtt, application/octet-stream',
      'x-api-key': key,
    },
  });
  // Free keys may authenticate search but use SubDL's anonymous download pool. Paid keys use
  // x-api-key. Retrying only an explicit auth rejection keeps both account types functional.
  if (response.status === 401 || response.status === 403) {
    await response.body?.cancel();
    response = await fetchWithTimeout(target, {
      headers: { accept: 'application/zip, text/plain, text/vtt, application/octet-stream' },
    });
  }
  if (!response.ok || !response.body) {
    throw new ServiceError('SUBDL_DOWNLOAD_FAILED', 'SubDL could not download this subtitle', 502, response.status >= 500);
  }
  const contentLength = Number(response.headers.get('content-length') || 0);
  if (contentLength > MAX_SUBTITLE_FILE_BYTES) {
    throw new ServiceError('SUBTITLE_TOO_LARGE', 'This subtitle file is too large', 413, false);
  }
  const headers = new Headers({
    'content-type': response.headers.get('content-type') || 'application/octet-stream',
    'cache-control': 'private, max-age=86400',
    'x-content-type-options': 'nosniff',
  });
  if (contentLength > 0) headers.set('content-length', String(contentLength));
  return new Response(response.body, { status: 200, headers });
}
