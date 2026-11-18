import type { SubtitleSearchResponse, SubtitleTrackResponse } from './subdl';
import { TmdbClient } from './tmdb';
import { RecommendationEnv, ServiceError } from './types';

const ORIGIN = 'https://sub.wyzie.io';
const TRACK_TOKEN_PREFIX = 'wyzie_';
const VIDEO_HASH = /^[a-f0-9]{16}$/i;
const EPISODE_MISMATCH = new Set(['wrong_season', 'wrong_episode']);
const IMAGE_FORMATS = new Set(['pgs', 'sup', 'idx', 'vobsub', 'scc', 'txt']);

interface WyzieTrack {
  id?: string | number;
  url?: string;
  display?: string;
  language?: string;
  release?: string;
  fileName?: string;
  format?: string;
  isHearingImpaired?: boolean;
  source?: string;
  ai?: boolean;
  match?: { reasons?: string[] };
}

function subtitleNumber(value: string | null, name: string, minimum = 1): number {
  const number = value === null ? NaN : Number(value);
  if (!Number.isSafeInteger(number) || number < minimum) {
    throw new ServiceError('INVALID_SUBTITLE_REQUEST', `Invalid subtitle ${name}`, 400, false);
  }
  return number;
}

function wyzieFile(raw: string): URL | null {
  try {
    const url = new URL(raw);
    return url.origin === ORIGIN && !url.username && !url.password && !url.hash &&
      url.pathname.startsWith('/c/') && url.searchParams.has('tok') && !url.searchParams.has('key') ? url : null;
  } catch {
    return null;
  }
}

async function tokenKey(env: RecommendationEnv): Promise<CryptoKey> {
  const secret = env.CURSOR_SIGNING_SECRET?.trim();
  if (!secret) {
    throw new ServiceError('WYZIE_NOT_CONFIGURED', 'Subtitle downloads are not configured yet', 503, false);
  }
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`wyzie-download:${secret}`));
  return crypto.subtle.importKey('raw', digest, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

async function packToken(file: URL, key: CryptoKey): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(file.href)));
  const bytes = new Uint8Array(iv.length + encrypted.length);
  bytes.set(iv);
  bytes.set(encrypted, iv.length);
  return TRACK_TOKEN_PREFIX + btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function unpackToken(encoded: string, key: CryptoKey): Promise<URL | null> {
  if (encoded.length > 900 || !/^[A-Za-z0-9_-]+$/.test(encoded)) return null;
  try {
    const bytes = Uint8Array.from(atob(encoded.replace(/-/g, '+').replace(/_/g, '/')
      .padEnd(Math.ceil(encoded.length / 4) * 4, '=')), char => char.charCodeAt(0));
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.subarray(0, 12) }, key, bytes.subarray(12));
    return wyzieFile(new TextDecoder().decode(plain));
  } catch {
    return null;
  }
}

async function asTrack(row: WyzieTrack, requestedLanguage: string, key: CryptoKey): Promise<SubtitleTrackResponse | null> {
  const language = row.language?.toLowerCase();
  const file = row.url && wyzieFile(row.url);
  if (!file || row.id == null || !language || !/^[a-z]{2}(-[a-z]{2})?$/.test(language) ||
      (requestedLanguage && language.split('-')[0] !== requestedLanguage) || row.ai ||
      IMAGE_FORMATS.has(row.format?.toLowerCase() ?? '') ||
      row.match?.reasons?.some(reason => EPISODE_MISMATCH.has(reason)) || file.href.length > 630) return null;
  const downloadToken = await packToken(file, key);
  if (downloadToken.length > 900) return null;
  return {
    id: `wyzie:${row.source || 'subs'}:${row.id}`,
    languageCode: language.toUpperCase(),
    languageName: row.display || language.toUpperCase(),
    releaseName: row.release || row.fileName || 'Wyzie subtitle',
    fileName: row.fileName ? row.fileName.replace(/\.[^.]+$/, '') + '.vtt' : 'subtitle.vtt',
    hearingImpaired: row.isHearingImpaired === true,
    format: 'vtt',
    downloadToken,
    hashMatched: row.match?.reasons?.includes('hash') === true,
  };
}

async function fetchWyzie(url: URL, search: boolean): Promise<Response> {
  try {
    return await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(12_000), headers: {
      accept: search ? 'application/json' : 'text/vtt, application/octet-stream',
    } });
  } catch {
    throw new ServiceError('WYZIE_UNAVAILABLE', 'Wyzie could not respond in time', 502, true);
  }
}

function wyzieError(status: number): ServiceError {
  if (status === 429 || status === 402) {
    return new ServiceError('WYZIE_RATE_LIMITED', 'Subtitle provider capacity has been reached', 429, true);
  }
  if (status === 401 || status === 404) {
    return new ServiceError('WYZIE_FILE_UNAVAILABLE', 'This subtitle is no longer available. Search again.', 404, false);
  }
  return new ServiceError('WYZIE_UNAVAILABLE', 'Wyzie could not load subtitles', 502, status >= 500);
}

async function readWyzieBody(response: Response, limit: number): Promise<Uint8Array<ArrayBuffer>> {
  const invalid = new ServiceError('WYZIE_INVALID_RESPONSE', 'Wyzie returned an invalid subtitle response', 502, true);
  if (!response.body) throw invalid;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) {
        await reader.cancel();
        throw invalid;
      }
      chunks.push(value);
    }
  } catch {
    throw invalid;
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

export async function searchWyzieSubtitles(env: RecommendationEnv, requestUrl: URL): Promise<SubtitleSearchResponse> {
  const params = requestUrl.searchParams;
  const type = params.get('type');
  if (type !== 'movie' && type !== 'tv') {
    throw new ServiceError('INVALID_SUBTITLE_REQUEST', 'Choose a movie or TV episode', 400, false);
  }
  const tmdbId = subtitleNumber(params.get('tmdbId'), 'TMDB ID');
  const season = type === 'tv' ? subtitleNumber(params.get('season'), 'season', 0) : undefined;
  const episode = type === 'tv' ? subtitleNumber(params.get('episode'), 'episode') : undefined;
  const language = params.get('language')?.trim().toLowerCase() ?? '';
  if (language && !/^[a-z]{2}$/.test(language)) {
    throw new ServiceError('INVALID_SUBTITLE_REQUEST', 'Choose a subtitle language', 400, false);
  }
  const key = await tokenKey(env);
  const { imdb_id: imdbId } = await new TmdbClient(env, 3).externalIds(type, tmdbId);
  const url = new URL('/search', ORIGIN);
  url.searchParams.set('id', imdbId && /^tt\d+$/.test(imdbId) ? imdbId : String(tmdbId));
  url.searchParams.set('key', env.WYZIE_API_KEY?.trim() ?? '');
  if (language) {
    url.searchParams.set('language', language);
    url.searchParams.set('limit', '40');
  }
  if (season !== undefined && episode !== undefined) {
    url.searchParams.set('season', String(season));
    url.searchParams.set('episode', String(episode));
  }
  const filename = params.get('filename')?.trim();
  const hash = params.get('videoHash');
  if (filename && filename.length <= 240) url.searchParams.set('video', filename);
  if (hash && VIDEO_HASH.test(hash)) url.searchParams.set('hash', hash.toLowerCase());
  const mediaKey = type === 'movie' ? `movie:${tmdbId}` : `tv:${tmdbId}:s${season}:e${episode}`;
  const response = await fetchWyzie(url, true);
  if (response.status === 404) return { mediaKey, tracks: [] };
  if (!response.ok) throw wyzieError(response.status);
  try {
    const rows = JSON.parse(new TextDecoder().decode(await readWyzieBody(response, 1_048_576))) as WyzieTrack[];
    // The first page can be dominated by editions in one language. Give every
    // returned language its own small release budget in the manual catalogue.
    const byLanguage = new Map<string, WyzieTrack[]>();
    for (const row of rows) {
      const code = row?.language?.toLowerCase();
      if (!code || row.ai || !row.url || IMAGE_FORMATS.has(row.format?.toLowerCase() ?? '')) continue;
      const group = byLanguage.get(code) ?? [];
      if (group.length < (language ? 40 : 12)) group.push(row);
      byLanguage.set(code, group);
    }
    const candidates = await Promise.all([...byLanguage.values()].flat().map(row => asTrack(row, language, key)));
    const tracks = candidates.filter(track => track !== null);
    return { mediaKey, tracks: [...new Map(tracks.map(track => [track.id, track])).values()] };
  } catch {
    throw new ServiceError('WYZIE_INVALID_RESPONSE', 'Wyzie returned invalid subtitle results', 502, true);
  }
}

export function isWyzieSubtitleToken(token: string): boolean {
  return token.startsWith(TRACK_TOKEN_PREFIX);
}

export async function downloadWyzieSubtitle(env: RecommendationEnv, token: string): Promise<Response> {
  const encoded = token.slice(TRACK_TOKEN_PREFIX.length);
  const target = await unpackToken(encoded, await tokenKey(env));
  if (!target) throw new ServiceError('INVALID_SUBTITLE_TOKEN', 'Invalid subtitle download', 400, false);
  target.searchParams.set('to', 'vtt');
  const response = await fetchWyzie(target, false);
  if (!response.ok) throw wyzieError(response.status);
  const bytes = await readWyzieBody(response, 8_388_608);
  if (!/^WEBVTT(?:[\s\r\n]|$)/.test(new TextDecoder().decode(bytes.subarray(0, 20)))) {
    throw new ServiceError('WYZIE_INVALID_FILE', 'This subtitle could not be read', 502, false);
  }
  return new Response(bytes, { headers: {
    'content-type': 'text/vtt; charset=utf-8',
    'cache-control': 'private, max-age=3600',
  } });
}
