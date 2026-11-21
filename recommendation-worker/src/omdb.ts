import { z } from 'zod';
import { RecommendationEnv, ServiceError } from './types';

const lookupSchema = z.object({
  imdbId: z.string().trim().toLowerCase().regex(/^tt\d{5,12}$/).optional(),
  title: z.string().trim().min(1).max(200).optional(),
  year: z.number().int().min(1800).max(2200).optional(),
  mediaType: z.enum(['movie', 'tv', 'series']).transform(type => type === 'tv' ? 'series' : type),
}).refine(value => Boolean(value.imdbId || (value.title && value.year)));

export type OmdbLookup = z.output<typeof lookupSchema>;

export async function parseOmdbLookup(request: Request): Promise<OmdbLookup> {
  const raw = await request.text();
  if (raw.length > 4096) throw new ServiceError('PAYLOAD_TOO_LARGE', 'OMDb lookup is too large', 413, false);
  return lookupSchema.parse(JSON.parse(raw));
}

function clean(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const text = value.trim();
  return text && !/^(?:N\/A|null)$/i.test(text) ? text : undefined;
}

function numeric(value: unknown, maximum = Number.MAX_SAFE_INTEGER): number | undefined {
  const text = clean(value)?.replace(/,/g, '');
  if (!text || !/^\d+(?:\.\d+)?$/.test(text)) return undefined;
  const number = Number(text);
  return number <= maximum ? number : undefined;
}

function list(value: unknown): string[] {
  return clean(value)?.split(',').map(item => item.trim()).filter(Boolean) ?? [];
}

function normalizedTitle(value: string): string {
  return value.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

export function omdbCacheKey(origin: string, lookup: OmdbLookup): URL {
  const key = new URL('/v1/metadata/omdb', origin);
  key.searchParams.set('type', lookup.mediaType);
  if (lookup.imdbId) key.searchParams.set('i', lookup.imdbId);
  else {
    key.searchParams.set('t', lookup.title!.toLowerCase());
    key.searchParams.set('y', String(lookup.year));
  }
  return key;
}

export async function lookupOmdb(env: RecommendationEnv, lookup: OmdbLookup) {
  const key = env.OMDB_API_KEY?.trim();
  if (!key) throw new ServiceError('OMDB_NOT_CONFIGURED', 'OMDb is not configured', 503, false);

  const url = new URL('https://www.omdbapi.com/');
  url.searchParams.set('apikey', key);
  url.searchParams.set('type', lookup.mediaType);
  url.searchParams.set('plot', 'full');
  if (lookup.imdbId) url.searchParams.set('i', lookup.imdbId);
  else {
    url.searchParams.set('t', lookup.title!);
    url.searchParams.set('y', String(lookup.year));
  }

  let raw: Record<string, unknown>;
  try {
    const response = await fetch(url, {
      headers: { accept: 'application/json' },
      redirect: 'manual',
      signal: AbortSignal.timeout(4_000),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error('OMDb upstream failure');
    }
    raw = await response.json() as Record<string, unknown>;
  } catch {
    // Fetch errors may contain the upstream URL and its query credential.
    throw new ServiceError('OMDB_UNAVAILABLE', 'OMDb metadata is temporarily unavailable', 503, true);
  }

  const missing = { found: false, source: 'OMDB' } as const;
  if (raw?.Response !== 'True' && raw?.Response !== true) {
    const error = clean(raw?.Error);
    if (error && /^(?:movie|series) not found[.!]?$/i.test(error)) return missing;
    throw new ServiceError('OMDB_UNAVAILABLE', 'OMDb metadata is temporarily unavailable', 503, true);
  }

  const imdbId = clean(raw.imdbID);
  const title = clean(raw.Title);
  const type = clean(raw.Type)?.toLowerCase();
  const yearText = clean(raw.Year);
  const year = numeric(yearText?.match(/^\d{4}/)?.[0]);
  if (!imdbId || !/^tt\d{5,12}$/.test(imdbId) || !title || type !== lookup.mediaType) return missing;
  if (lookup.imdbId ? imdbId !== lookup.imdbId :
      normalizedTitle(title) !== normalizedTitle(lookup.title!) || year !== lookup.year) return missing;

  const ratings = Array.isArray(raw.Ratings) ? raw.Ratings : [];
  const tomatoes = ratings.find(rating => rating?.Source === 'Rotten Tomatoes')?.Value;
  return {
    found: true,
    source: 'OMDB',
    imdbId,
    title,
    type,
    year,
    yearText,
    awards: clean(raw.Awards),
    genres: list(raw.Genre),
    plot: clean(raw.Plot),
    runtimeMinutes: numeric(clean(raw.Runtime)?.match(/^\d+/)?.[0]),
    director: clean(raw.Director),
    writers: list(raw.Writer),
    actors: list(raw.Actors),
    languages: list(raw.Language),
    countries: list(raw.Country),
    contentRating: clean(raw.Rated),
    imdbRating: numeric(raw.imdbRating, 10),
    imdbVotes: numeric(raw.imdbVotes),
    rottenTomatoesRating: numeric(clean(tomatoes)?.replace(/%$/, ''), 100),
    metascore: numeric(raw.Metascore, 100),
    totalSeasons: numeric(raw.totalSeasons),
  };
}
