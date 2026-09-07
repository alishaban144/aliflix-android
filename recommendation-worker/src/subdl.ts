import { RecommendationEnv, ServiceError } from './types';
import { TmdbClient } from './tmdb';

const SUBDL_SEARCH_URL = 'https://api.subdl.com/api/v2/subtitles/search';
const SUBDL_API_ORIGIN = 'https://api.subdl.com';
const SUBDL_DOWNLOAD_ORIGIN = 'https://dl.subdl.com';
const SUBDL_TIMEOUT_MILLIS = 12_000;
const MAX_SUBDL_JSON_BYTES = 1_048_576;
const MAX_SUBTITLE_FILE_BYTES = 8_388_608;
const MAX_TRACKS_PER_SEARCH = 30;
const MAX_COMBINED_TRACKS = 60;

interface SubdlSearchDocument {
  status?: boolean;
  error?: string | { message?: string };
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

function normalizedImdbId(value: string): string | null {
  const normalized = value.trim().toLowerCase();
  return /^tt\d{5,12}$/.test(normalized) ? normalized : null;
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
    target.protocol === 'https:' &&
    target.hostname === 'dl.subdl.com' &&
    !target.port && !target.username && !target.password &&
    target.pathname.startsWith('/subtitle/') &&
    !target.pathname.includes('..') && target.pathname.length <= 600
  ) {
    const path = target.pathname;
    return btoa(path).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  }
  if (
    target.protocol === 'https:' &&
    (target.hostname.endsWith('strem.io') || target.hostname === 'api.opensubtitles.com') &&
    !target.port && !target.username && !target.password &&
    target.href.length <= 900
  ) {
    return btoa(target.href).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  }
  return null;
}

export function decodeSubdlDownloadToken(token: string): URL | null {
  if (!/^[A-Za-z0-9_-]{8,900}$/.test(token)) return null;
  try {
    const padded = token.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - token.length % 4) % 4);
    const decoded = atob(padded);
    if (decoded.startsWith('https://')) {
      const target = new URL(decoded);
      if (
        target.protocol === 'https:' &&
        (target.hostname.endsWith('strem.io') || target.hostname === 'api.opensubtitles.com') &&
        !target.port && !target.username && !target.password
      ) {
        return target;
      }
      return null;
    }
    const target = new URL(decoded, SUBDL_DOWNLOAD_ORIGIN);
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

function v2DownloadFallback(target: URL): URL | null {
  const segments = target.pathname.split('/').filter(Boolean);
  const nId = segments.length >= 2 && segments[0] === 'subtitle' ? segments[1] : '';
  if (!/^[A-Za-z0-9_-]{4,100}$/.test(nId)) return null;
  const fallback = new URL(`/api/v2/subtitles/${encodeURIComponent(nId)}/download`, SUBDL_API_ORIGIN);
  fallback.searchParams.set('format', 'file');
  return fallback;
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
  expectedImdbId?: string,
): SubtitleTrackResponse[] {
  if (document.status === false) {
    const errorMessage = typeof document.error === 'string'
      ? document.error
      : document.error?.message;
    throw new ServiceError('SUBDL_ERROR', errorMessage || 'SubDL could not search for subtitles', 502, true);
  }
  const resultMatches = (document.results || []).some(rawResult => {
    const result = asRecord(rawResult);
    if (!result) return false;
    const resultType = optionalString(result, 'type').toLowerCase();
    const resultTmdbId = optionalInteger(result.tmdb_id);
    const resultImdbId = normalizedImdbId(optionalString(result, 'imdb_id'));
    const exactIdentity = expectedImdbId
      ? resultImdbId === expectedImdbId && (resultTmdbId === undefined || resultTmdbId === tmdbId)
      : resultTmdbId === tmdbId;
    return exactIdentity && (!resultType || resultType === mediaType);
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
    if (tracks.length >= MAX_TRACKS_PER_SEARCH) break;
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

async function fetchSubdlSearchDocument(url: URL, key: string): Promise<SubdlSearchDocument> {
  const response = await fetchWithTimeout(url, {
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${key}`,
    },
  });
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
  try {
    return JSON.parse(raw) as SubdlSearchDocument;
  } catch {
    throw new ServiceError('SUBDL_INVALID_RESPONSE', 'SubDL returned an invalid response', 502, true);
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

  const externalIds = await new TmdbClient(env, 3).externalIds(mediaType, tmdbId);
  const imdbId = normalizedImdbId(externalIds.imdb_id || '');
  if (!imdbId) {
    throw new ServiceError(
      'SUBTITLE_IDENTITY_UNAVAILABLE',
      'Subtitles could not verify this title yet',
      502,
      true,
    );
  }

  const upstreamUrl = new URL(SUBDL_SEARCH_URL);
  upstreamUrl.searchParams.set('imdb_id', imdbId);
  upstreamUrl.searchParams.set('type', mediaType);
  upstreamUrl.searchParams.set('subs_per_page', String(MAX_TRACKS_PER_SEARCH));
  upstreamUrl.searchParams.set('unpack', '1');
  if (season !== undefined && episode !== undefined) {
    upstreamUrl.searchParams.set('season', String(season));
    upstreamUrl.searchParams.set('episode', String(episode));
  }

const OPENSUB_LANG_MAP: Record<string, { code: string; name: string }> = {
  eng: { code: 'EN', name: 'English' },
  spa: { code: 'ES', name: 'Spanish' },
  fre: { code: 'FR', name: 'French' },
  fra: { code: 'FR', name: 'French' },
  ger: { code: 'DE', name: 'German' },
  deu: { code: 'DE', name: 'German' },
  ita: { code: 'IT', name: 'Italian' },
  por: { code: 'PT', name: 'Portuguese' },
  pob: { code: 'PT', name: 'Portuguese (BR)' },
  ara: { code: 'AR', name: 'Arabic' },
  rus: { code: 'RU', name: 'Russian' },
  per: { code: 'FA', name: 'Persian' },
  fas: { code: 'FA', name: 'Persian' },
  pol: { code: 'PL', name: 'Polish' },
  tur: { code: 'TR', name: 'Turkish' },
  dut: { code: 'NL', name: 'Dutch' },
  nld: { code: 'NL', name: 'Dutch' },
  ell: { code: 'EL', name: 'Greek' },
  gre: { code: 'EL', name: 'Greek' },
  swe: { code: 'SV', name: 'Swedish' },
  dan: { code: 'DA', name: 'Danish' },
  fin: { code: 'FI', name: 'Finnish' },
  nor: { code: 'NO', name: 'Norwegian' },
  cze: { code: 'CS', name: 'Czech' },
  ces: { code: 'CS', name: 'Czech' },
  hun: { code: 'HU', name: 'Hungarian' },
  kor: { code: 'KO', name: 'Korean' },
  zho: { code: 'ZH', name: 'Chinese' },
  chi: { code: 'ZH', name: 'Chinese' },
  jpn: { code: 'JA', name: 'Japanese' },
  ind: { code: 'ID', name: 'Indonesian' },
  vie: { code: 'VI', name: 'Vietnamese' },
  heb: { code: 'HE', name: 'Hebrew' },
  hin: { code: 'HI', name: 'Hindi' },
  rum: { code: 'RO', name: 'Romanian' },
  ron: { code: 'RO', name: 'Romanian' },
  bul: { code: 'BG', name: 'Bulgarian' },
  hrv: { code: 'HR', name: 'Croatian' },
  srp: { code: 'SR', name: 'Serbian' },
  slv: { code: 'SL', name: 'Slovenian' },
  slk: { code: 'SK', name: 'Slovak' },
  slo: { code: 'SK', name: 'Slovak' },
  ukr: { code: 'UK', name: 'Ukrainian' },
  tha: { code: 'TH', name: 'Thai' },
};

async function fetchOpenSubtitles(
  mediaType: 'movie' | 'tv',
  imdbId: string,
  season?: number,
  episode?: number,
): Promise<SubtitleTrackResponse[]> {
  try {
    const path = mediaType === 'movie'
      ? `movie/${encodeURIComponent(imdbId)}.json`
      : `series/${encodeURIComponent(imdbId)}:${season || 1}:${episode || 1}.json`;
    const url = new URL(`https://opensubtitles-v3.strem.io/subtitles/${path}`);
    const response = await fetchWithTimeout(url, {
      headers: {
        accept: 'application/json',
        'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
      },
    });
    if (!response.ok) return [];
    const data = await response.json() as { subtitles?: Array<Record<string, unknown>> };
    const list = Array.isArray(data.subtitles) ? data.subtitles : [];
    const tracks: SubtitleTrackResponse[] = [];
    for (const item of list) {
      const rawUrl = typeof item.url === 'string' ? item.url : '';
      if (!rawUrl || !rawUrl.startsWith('https://')) continue;
      const rawLang = typeof item.lang === 'string' ? item.lang.toLowerCase() : 'eng';
      const meta = OPENSUB_LANG_MAP[rawLang] || {
        code: rawLang.length <= 3 ? rawLang.toUpperCase() : rawLang.slice(0, 2).toUpperCase(),
        name: rawLang.toUpperCase(),
      };
      const releaseName = (typeof item.movieReleaseName === 'string' && item.movieReleaseName.trim()) ||
        (typeof item.subtitleFileName === 'string' && item.subtitleFileName.trim()) ||
        'OpenSubtitles track';
      const fileName = (typeof item.subtitleFileName === 'string' && item.subtitleFileName.trim()) ||
        `${releaseName}.srt`;
      const downloadToken = encodeDownloadToken(rawUrl);
      if (!downloadToken) continue;
      tracks.push({
        id: downloadToken,
        languageCode: meta.code,
        languageName: meta.name,
        releaseName,
        fileName,
        hearingImpaired: false,
        format: 'srt',
        downloadToken,
      });
      if (tracks.length >= 35) break;
    }
    return tracks;
  } catch {
    return [];
  }
}

  const searchUrls = [upstreamUrl];
  if (mediaType === 'tv') {
    const seasonPackUrl = new URL(upstreamUrl);
    seasonPackUrl.searchParams.delete('episode');
    seasonPackUrl.searchParams.set('full_season', '1');
    searchUrls.push(seasonPackUrl);
  }

  const [subdlResults, openSubTracks] = await Promise.all([
    Promise.allSettled(searchUrls.map(url => fetchSubdlSearchDocument(url, key))),
    fetchOpenSubtitles(mediaType, imdbId, season, episode),
  ]);
  const documents = subdlResults.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);

  const tracks: SubtitleTrackResponse[] = [];
  const seenTrackIds = new Set<string>();
  let parseFailure: unknown;

  // Include OpenSubtitles first for guaranteed reliable, unlimited downloads
  for (const track of openSubTracks) {
    if (seenTrackIds.has(track.id)) continue;
    seenTrackIds.add(track.id);
    tracks.push(track);
  }

  for (const document of documents) {
    try {
      for (const track of parseSubdlSearchDocument(document, mediaType, tmdbId, season, episode, imdbId)) {
        if (seenTrackIds.has(track.id)) continue;
        seenTrackIds.add(track.id);
        tracks.push(track);
        if (tracks.length >= MAX_COMBINED_TRACKS) break;
      }
    } catch (error) {
      parseFailure ??= error;
    }
    if (tracks.length >= MAX_COMBINED_TRACKS) break;
  }
  if (tracks.length === 0 && parseFailure) throw parseFailure;
  if (tracks.length === 0 && documents.length === 0 && openSubTracks.length === 0) {
    throw (subdlResults[0] as PromiseRejectedResult)?.reason ||
      new ServiceError('SUBTITLE_NOT_FOUND', 'No subtitles found for this title', 404, false);
  }

  // Sort with EN / English first
  tracks.sort((a, b) => {
    const aEn = a.languageCode.toUpperCase() === 'EN' || a.languageName.toLowerCase().startsWith('eng') ? 0 : 1;
    const bEn = b.languageCode.toUpperCase() === 'EN' || b.languageName.toLowerCase().startsWith('eng') ? 0 : 1;
    if (aEn !== bEn) return aEn - bEn;
    return a.languageName.localeCompare(b.languageName);
  });

  return {
    mediaKey: mediaType === 'movie'
      ? `movie:${tmdbId}`
      : `tv:${tmdbId}:s${season}:e${episode}`,
    tracks,
  };
}

export async function downloadSubdlSubtitle(
  env: RecommendationEnv,
  token: string,
): Promise<Response> {
  const target = decodeSubdlDownloadToken(token);
  if (!target) {
    throw new ServiceError('INVALID_SUBTITLE_DOWNLOAD', 'Invalid subtitle download', 400, false);
  }

  // Handle direct OpenSubtitles / Stremio subtitle streams
  if (target.hostname.endsWith('strem.io') || target.hostname === 'api.opensubtitles.com') {
    const streamResponse = await fetchWithTimeout(target, {
      headers: {
        accept: 'application/x-subrip, text/plain, text/vtt, application/octet-stream, */*',
        'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
      },
    });
    if (streamResponse.ok && streamResponse.body) {
      const contentLength = Number(streamResponse.headers.get('content-length') || 0);
      const headers = new Headers({
        'content-type': streamResponse.headers.get('content-type') || 'application/x-subrip; charset=utf-8',
        'cache-control': 'private, max-age=86400',
        'x-content-type-options': 'nosniff',
      });
      if (contentLength > 0) headers.set('content-length', String(contentLength));
      return new Response(streamResponse.body, { status: 200, headers });
    }
    throw new ServiceError('SUBTITLE_DOWNLOAD_FAILED', 'Could not download subtitle from provider', 502, true);
  }

  const key = requiredSubdlKey(env);
  const userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
  let response = await fetchWithTimeout(target, {
    headers: {
      accept: 'application/zip, text/plain, text/vtt, application/octet-stream',
      'user-agent': userAgent,
      'x-api-key': key,
    },
  });
  // If x-api-key was rejected or challenged, try appending api_key query param or anonymous pool
  if (response.status === 401 || response.status === 403) {
    await response.body?.cancel();
    const targetWithKey = new URL(target.href);
    targetWithKey.searchParams.set('api_key', key);
    response = await fetchWithTimeout(targetWithKey, {
      headers: {
        accept: 'application/zip, text/plain, text/vtt, application/octet-stream',
        'user-agent': userAgent,
        authorization: `Bearer ${key}`,
      },
    });
    if (!response.ok) {
      await response.body?.cancel();
      response = await fetchWithTimeout(target, {
        headers: {
          accept: 'application/zip, text/plain, text/vtt, application/octet-stream',
          'user-agent': userAgent,
        },
      });
    }
  }
  if (!response.ok) {
    await response.body?.cancel();
    const fallback = v2DownloadFallback(target);
    if (fallback) {
      response = await fetchWithTimeout(fallback, {
        headers: {
          accept: 'application/zip, text/plain, text/vtt, application/octet-stream',
          'user-agent': userAgent,
          authorization: `Bearer ${key}`,
        },
      });
    }
  }
  if (!response.ok || !response.body) {
    const errorBody = await response.text().catch(() => '');
    throw new ServiceError(
      'SUBDL_DOWNLOAD_FAILED',
      `SubDL could not download this subtitle (status ${response.status}${errorBody ? ': ' + errorBody.slice(0, 100) : ''})`,
      502,
      response.status >= 500,
    );
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
