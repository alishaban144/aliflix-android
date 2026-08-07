import { Env } from './gemini';

export interface OmdbTitleMetadata {
  found: boolean;
  imdbId: string | null;
  title: string | null;
  year: number | null;
  yearText: string | null;
  type: 'movie' | 'series' | 'episode' | null;
  genres: string[];
  plot: string | null;
  runtimeMinutes: number | null;
  director: string | null;
  writers: string[];
  actors: string[];
  languages: string[];
  countries: string[];
  contentRating: string | null;
  awards: string | null;
  imdbRating: number | null;
  imdbVotes: number | null;
  rottenTomatoesRating: number | null;
  metascore: number | null;
  totalSeasons: number | null;
  source: 'OMDB';
}

export interface OmdbLookupRequest {
  imdbId?: string;
  title?: string;
  year?: number;
  mediaType: 'movie' | 'series';
}

export interface OmdbLookupResult {
  candidateId?: string;
  metadata: OmdbTitleMetadata | null;
  status: 'VERIFIED' | 'NOT_FOUND' | 'UNAVAILABLE';
  cacheHit?: boolean;
}

const VERIFIED_TTL_SECONDS = 7 * 24 * 3600; // 7 days
const STALE_LIMIT_SECONDS = 30 * 24 * 3600; // 30 days
const NOT_FOUND_TTL_SECONDS = 6 * 3600; // 6 hours
const UPSTREAM_TIMEOUT_MS = 3500;

function cleanString(val: any): string | null {
  if (val === null || val === undefined) return null;
  const s = String(val).trim();
  if (s.length === 0 || s.toUpperCase() === 'N/A') return null;
  return s;
}

function cleanList(val: any): string[] {
  const s = cleanString(val);
  if (!s) return [];
  return s
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0 && item.toUpperCase() !== 'N/A');
}

function parseYear(yearVal: any): { year: number | null; yearText: string | null } {
  const yearText = cleanString(yearVal);
  if (!yearText) return { year: null, yearText: null };
  const match = yearText.match(/\d{4}/);
  const year = match ? parseInt(match[0], 10) : null;
  return { year, yearText };
}

function parseRuntimeMinutes(runtimeVal: any): number | null {
  const s = cleanString(runtimeVal);
  if (!s) return null;
  const match = s.match(/(\d+)/);
  return match ? parseInt(match[1], 10) : null;
}

function parseImdbRating(ratingVal: any): number | null {
  const s = cleanString(ratingVal);
  if (!s) return null;
  const num = parseFloat(s);
  return !isNaN(num) && num > 0 ? num : null;
}

function parseImdbVotes(votesVal: any): number | null {
  const s = cleanString(votesVal);
  if (!s) return null;
  const cleanNum = s.replace(/,/g, '');
  const num = parseInt(cleanNum, 10);
  return !isNaN(num) && num >= 0 ? num : null;
}

function extractRottenTomatoesRating(ratings: any[]): number | null {
  if (!Array.isArray(ratings)) return null;
  const rtEntry = ratings.find(
    (r) => r && typeof r.Source === 'string' && r.Source.toLowerCase() === 'rotten tomatoes'
  );
  if (!rtEntry || !rtEntry.Value) return null;
  const s = cleanString(rtEntry.Value);
  if (!s) return null;
  const match = s.match(/(\d+)/);
  if (!match) return null;
  const val = parseInt(match[1], 10);
  return !isNaN(val) && val >= 0 && val <= 100 ? val : null;
}

function extractMetascore(rawMetascore: any, ratings: any[]): number | null {
  const direct = cleanString(rawMetascore);
  if (direct) {
    const num = parseInt(direct, 10);
    if (!isNaN(num) && num >= 0) return num;
  }
  if (Array.isArray(ratings)) {
    const metaEntry = ratings.find(
      (r) => r && typeof r.Source === 'string' && r.Source.toLowerCase() === 'metacritic'
    );
    if (metaEntry && metaEntry.Value) {
      const s = cleanString(metaEntry.Value);
      if (s) {
        const match = s.match(/(\d+)/);
        if (match) {
          const val = parseInt(match[1], 10);
          if (!isNaN(val) && val >= 0) return val;
        }
      }
    }
  }
  return null;
}

export function normalizeOmdbResponse(raw: any): OmdbTitleMetadata {
  if (!raw || raw.Response === 'False' || raw.Response === false) {
    return {
      found: false,
      imdbId: null,
      title: null,
      year: null,
      yearText: null,
      type: null,
      genres: [],
      plot: null,
      runtimeMinutes: null,
      director: null,
      writers: [],
      actors: [],
      languages: [],
      countries: [],
      contentRating: null,
      awards: null,
      imdbRating: null,
      imdbVotes: null,
      rottenTomatoesRating: null,
      metascore: null,
      totalSeasons: null,
      source: 'OMDB',
    };
  }

  const { year, yearText } = parseYear(raw.Year);
  const typeStr = cleanString(raw.Type)?.toLowerCase();
  const type: 'movie' | 'series' | 'episode' | null =
    typeStr === 'movie' || typeStr === 'series' || typeStr === 'episode' ? typeStr : null;

  const totalSeasonsVal = cleanString(raw.totalSeasons);
  const totalSeasons = totalSeasonsVal ? parseInt(totalSeasonsVal, 10) : null;

  return {
    found: true,
    imdbId: cleanString(raw.imdbID),
    title: cleanString(raw.Title),
    year,
    yearText,
    type,
    genres: cleanList(raw.Genre),
    plot: cleanString(raw.Plot),
    runtimeMinutes: parseRuntimeMinutes(raw.Runtime),
    director: cleanString(raw.Director),
    writers: cleanList(raw.Writer),
    actors: cleanList(raw.Actor || raw.Actors),
    languages: cleanList(raw.Language),
    countries: cleanList(raw.Country),
    contentRating: cleanString(raw.Rated),
    awards: cleanString(raw.Awards),
    imdbRating: parseImdbRating(raw.imdbRating),
    imdbVotes: parseImdbVotes(raw.imdbVotes),
    rottenTomatoesRating: extractRottenTomatoesRating(raw.Ratings),
    metascore: extractMetascore(raw.Metascore, raw.Ratings),
    totalSeasons: !isNaN(totalSeasons as number) ? totalSeasons : null,
    source: 'OMDB',
  };
}

export function normalizeTitleText(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

export function validateOmdbIdentity(
  req: OmdbLookupRequest,
  metadata: OmdbTitleMetadata
): boolean {
  if (!metadata.found) return false;

  // 1. IMDb ID match
  if (req.imdbId && metadata.imdbId) {
    if (req.imdbId.toLowerCase() === metadata.imdbId.toLowerCase()) {
      return true;
    }
    // If requested IMDb ID is specified but differs from OMDb's returned IMDb ID, reject!
    return false;
  }

  // 2. Media type match
  if (req.mediaType === 'movie' && metadata.type !== 'movie') return false;
  if (req.mediaType === 'series' && metadata.type !== 'series') return false;

  // 3. Title match
  if (req.title && metadata.title) {
    const normReq = normalizeTitleText(req.title);
    const normRes = normalizeTitleText(metadata.title);
    if (normReq !== normRes) {
      // Allow minor variations if title starts/ends identical or has high similarity
      if (!normReq.startsWith(normRes) && !normRes.startsWith(normReq)) {
        return false;
      }
    }
  }

  // 4. Year check
  if (req.year != null && metadata.year != null) {
    const diff = Math.abs(req.year - metadata.year);
    if (req.mediaType === 'movie' && diff > 1) return false;
    if (req.mediaType === 'series' && diff > 2) return false;
  }

  return true;
}

export function getKvCacheKey(req: OmdbLookupRequest): string {
  if (req.imdbId && /^tt\d{7,10}$/i.test(req.imdbId.trim())) {
    return `omdb:id:${req.imdbId.trim().toLowerCase()}`;
  }
  const normTitle = req.title ? normalizeTitleText(req.title).replace(/\s+/g, '-') : 'unknown';
  const yearStr = req.year ? String(req.year) : 'any';
  return `omdb:${req.mediaType}:${normTitle}:${yearStr}`;
}

export interface KvCacheEntry {
  status: 'VERIFIED' | 'NOT_FOUND';
  metadata: OmdbTitleMetadata | null;
  storedAtMillis: number;
}

export async function getFromKvCache(
  env: Env,
  cacheKey: string
): Promise<{ entry: KvCacheEntry | null; isStale: boolean }> {
  if (!env.OMDB_CACHE) return { entry: null, isStale: false };
  try {
    const raw = await env.OMDB_CACHE.get(cacheKey);
    if (!raw) return { entry: null, isStale: false };
    const entry: KvCacheEntry = JSON.parse(raw);
    const ageSeconds = (Date.now() - entry.storedAtMillis) / 1000;

    if (entry.status === 'NOT_FOUND') {
      if (ageSeconds <= NOT_FOUND_TTL_SECONDS) {
        return { entry, isStale: false };
      }
      return { entry: null, isStale: false };
    }

    if (entry.status === 'VERIFIED') {
      if (ageSeconds <= VERIFIED_TTL_SECONDS) {
        return { entry, isStale: false };
      }
      if (ageSeconds <= STALE_LIMIT_SECONDS) {
        return { entry, isStale: true };
      }
    }

    return { entry: null, isStale: false };
  } catch (_err) {
    return { entry: null, isStale: false };
  }
}

export async function putToKvCache(
  env: Env,
  cacheKey: string,
  status: 'VERIFIED' | 'NOT_FOUND',
  metadata: OmdbTitleMetadata | null
): Promise<void> {
  if (!env.OMDB_CACHE) return;
  try {
    const entry: KvCacheEntry = {
      status,
      metadata,
      storedAtMillis: Date.now(),
    };
    const expirationTtl = status === 'VERIFIED' ? STALE_LIMIT_SECONDS : NOT_FOUND_TTL_SECONDS;
    await env.OMDB_CACHE.put(cacheKey, JSON.stringify(entry), { expirationTtl });
  } catch (_err) {
    // Ignore KV write failure
  }
}

export async function fetchOmdbUpstream(
  env: Env,
  req: OmdbLookupRequest,
  attempt: number = 1
): Promise<{ raw: any; httpStatus: number; errorType?: string }> {
  if (!env.OMDB_API_KEY) {
    return { raw: null, httpStatus: 500, errorType: 'INVALID_CONFIGURATION' };
  }

  let url = `https://www.omdbapi.com/?apikey=${encodeURIComponent(env.OMDB_API_KEY)}&plot=full&r=json`;
  if (req.imdbId && /^tt\d{7,10}$/i.test(req.imdbId.trim())) {
    url += `&i=${encodeURIComponent(req.imdbId.trim())}`;
  } else if (req.title) {
    url += `&t=${encodeURIComponent(req.title.trim())}`;
    if (req.year) url += `&y=${req.year}`;
    if (req.mediaType) url += `&type=${req.mediaType}`;
  } else {
    return { raw: null, httpStatus: 400, errorType: 'INVALID_INPUT' };
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);

  try {
    const response = await fetch(url, { signal: controller.signal });
    clearTimeout(timeoutId);

    if (response.status === 401 || response.status === 403) {
      return { raw: null, httpStatus: response.status, errorType: 'INVALID_CONFIGURATION' };
    }
    if (response.status === 429) {
      return { raw: null, httpStatus: 429, errorType: 'RATE_LIMITED' };
    }
    if (response.status >= 500) {
      if (attempt === 1) {
        return fetchOmdbUpstream(env, req, 2);
      }
      return { raw: null, httpStatus: response.status, errorType: 'UPSTREAM_ERROR' };
    }

    const json: any = await response.json();
    return { raw: json, httpStatus: response.status };
  } catch (error: any) {
    clearTimeout(timeoutId);
    if (error.name === 'AbortError') {
      if (attempt === 1) {
        return fetchOmdbUpstream(env, req, 2);
      }
      return { raw: null, httpStatus: 504, errorType: 'TIMEOUT' };
    }
    if (attempt === 1) {
      return fetchOmdbUpstream(env, req, 2);
    }
    return { raw: null, httpStatus: 500, errorType: 'NETWORK_FAILURE' };
  }
}

export async function lookupOmdbTitle(
  env: Env,
  req: OmdbLookupRequest
): Promise<OmdbLookupResult> {
  const cacheKey = getKvCacheKey(req);

  // 1. Check KV Cache
  const { entry: cachedEntry, isStale } = await getFromKvCache(env, cacheKey);
  if (cachedEntry && !isStale) {
    return {
      metadata: cachedEntry.metadata,
      status: cachedEntry.status,
      cacheHit: true,
    };
  }

  // 2. Fetch Upstream
  const upstream = await fetchOmdbUpstream(env, req);
  if (upstream.errorType || !upstream.raw) {
    // If upstream failed but we have a stale cached entry (up to 30 days), use it as fallback!
    if (cachedEntry && cachedEntry.status === 'VERIFIED') {
      return {
        metadata: cachedEntry.metadata,
        status: 'VERIFIED',
        cacheHit: true,
      };
    }
    return {
      metadata: null,
      status: 'UNAVAILABLE',
      cacheHit: false,
    };
  }

  const raw = upstream.raw;
  if (raw.Response === 'False' || raw.Response === false) {
    // Cache NOT_FOUND in KV
    await putToKvCache(env, cacheKey, 'NOT_FOUND', null);
    return {
      metadata: null,
      status: 'NOT_FOUND',
      cacheHit: false,
    };
  }

  const normalized = normalizeOmdbResponse(raw);
  const isValid = validateOmdbIdentity(req, normalized);

  if (!isValid) {
    // Identity mismatch! Do NOT cache as NOT_FOUND or VERIFIED.
    return {
      metadata: null,
      status: 'NOT_FOUND',
      cacheHit: false,
    };
  }

  // Also write secondary KV cache key if both IMDb ID and title were resolved
  await putToKvCache(env, cacheKey, 'VERIFIED', normalized);
  if (normalized.imdbId && cacheKey !== `omdb:id:${normalized.imdbId.toLowerCase()}`) {
    await putToKvCache(env, `omdb:id:${normalized.imdbId.toLowerCase()}`, 'VERIFIED', normalized);
  }

  return {
    metadata: normalized,
    status: 'VERIFIED',
    cacheHit: false,
  };
}
