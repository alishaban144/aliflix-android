import { Env } from './gemini';

const TMDB_BASE_URL = 'https://api.themoviedb.org/3';

export class TmdbError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'TmdbError';
  }
}

async function fetchTmdb<T>(env: Env, endpoint: string, params: Record<string, any> = {}): Promise<T> {
  if (!env.TMDB_API_KEY) {
    throw new Error('TMDB_API_KEY is not configured');
  }

  const url = new URL(`${TMDB_BASE_URL}${endpoint}`);
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      url.searchParams.append(key, String(value));
    }
  });

  const response = await fetch(url.toString(), {
    headers: {
      'Authorization': `Bearer ${env.TMDB_API_KEY}`,
      'Accept': 'application/json'
    },
    // Cloudflare Workers fetch timeout is 100s, but we can't easily configure it per request in standard fetch without AbortController
    // We'll rely on the worker's overall timeout or implement a manual timeout if strictly needed.
  });

  if (!response.ok) {
    throw new TmdbError(response.status, `TMDB API error: ${response.statusText}`);
  }

  return response.json() as Promise<T>;
}

// SEARCH
export async function searchMovie(env: Env, query: string, page: number = 1) {
  return fetchTmdb<any>(env, '/search/movie', { query, page, include_adult: false });
}

export async function searchTv(env: Env, query: string, page: number = 1) {
  return fetchTmdb<any>(env, '/search/tv', { query, page, include_adult: false });
}

export async function searchKeyword(env: Env, query: string, page: number = 1) {
  return fetchTmdb<any>(env, '/search/keyword', { query, page });
}

// DISCOVERY
export async function discoverMovie(env: Env, params: Record<string, any>) {
  return fetchTmdb<any>(env, '/discover/movie', { include_adult: false, ...params });
}

export async function discoverTv(env: Env, params: Record<string, any>) {
  return fetchTmdb<any>(env, '/discover/tv', { include_adult: false, ...params });
}

// SIMILARITY
export async function getRecommendations(env: Env, mediaType: 'movie' | 'tv', id: number, page: number = 1) {
  return fetchTmdb<any>(env, `/${mediaType}/${id}/recommendations`, { page });
}

export async function getSimilar(env: Env, mediaType: 'movie' | 'tv', id: number, page: number = 1) {
  return fetchTmdb<any>(env, `/${mediaType}/${id}/similar`, { page });
}

// DETAILS
export async function getDetails(env: Env, mediaType: 'movie' | 'tv', id: number) {
  return fetchTmdb<any>(env, `/${mediaType}/${id}`);
}

// KEYWORDS
export async function getKeywords(env: Env, mediaType: 'movie' | 'tv', id: number) {
  return fetchTmdb<any>(env, `/${mediaType}/${id}/keywords`);
}

// CREDITS
export async function getCredits(env: Env, mediaType: 'movie' | 'tv', id: number) {
  const endpoint = mediaType === 'tv' ? `/tv/${id}/aggregate_credits` : `/movie/${id}/credits`;
  return fetchTmdb<any>(env, endpoint);
}

// EXTERNAL IDS
export async function getExternalIds(env: Env, mediaType: 'movie' | 'tv', id: number) {
  return fetchTmdb<any>(env, `/${mediaType}/${id}/external_ids`);
}
