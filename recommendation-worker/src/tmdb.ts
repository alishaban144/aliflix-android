import { MediaType, RecommendationEnv, ServiceError, TmdbGenre, TmdbKeyword, TmdbListItem } from './types';

const BASE_URL = 'https://api.themoviedb.org/3';

export interface TmdbPage<T = TmdbListItem> { page: number; results: T[]; total_pages: number; total_results: number }
export interface TmdbDetails extends TmdbListItem {
  genres?: TmdbGenre[]; runtime?: number | null; episode_run_time?: number[]; keywords?: { keywords?: TmdbKeyword[]; results?: TmdbKeyword[] };
  credits?: { cast?: Array<{ id: number; name: string }>; crew?: Array<{ id: number; name: string; job?: string }> };
  aggregate_credits?: { cast?: Array<{ id: number; name: string }> };
}

export class TmdbClient {
  private used = 0;
  constructor(private readonly env: RecommendationEnv, private readonly budget = 40) {}
  get callsUsed(): number { return this.used; }
  get callsRemaining(): number { return this.budget - this.used; }

  private async request<T>(path: string, params: Record<string, string | number | boolean | undefined> = {}): Promise<T> {
    if (!this.env.TMDB_API_KEY && !this.env.TMDB_READ_ACCESS_TOKEN) {
      throw new ServiceError('TMDB_AUTH_FAILED', 'TMDB is not configured', 503, true);
    }
    const url = new URL(`${BASE_URL}${path}`);
    for (const [key, value] of Object.entries(params)) if (value !== undefined) url.searchParams.set(key, String(value));
    const headers: HeadersInit = { accept: 'application/json' };
    applyTmdbAuthentication(this.env, url, headers);

    for (let attempt = 0; attempt < 3; attempt++) {
      if (++this.used > this.budget) throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB request budget exhausted', 503, true);
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 8_000);
      try {
        const response = await fetch(url, { headers, signal: controller.signal });
        if (response.ok) return await response.json() as T;
        if (response.status === 401 || response.status === 403) {
          throw new ServiceError('TMDB_AUTH_FAILED', 'TMDB rejected the configured credential', 503, false);
        }
        if (response.status === 404) throw new ServiceError('TMDB_NOT_FOUND', 'TMDB resource was not found', 404, false);
        if (response.status !== 429 && response.status < 500) {
          throw new ServiceError('TMDB_UNAVAILABLE', `TMDB request failed (${response.status})`, 502, false);
        }
        if (attempt === 2) throw new ServiceError('TMDB_UNAVAILABLE', `TMDB request failed (${response.status})`, 503, true);
      } catch (error) {
        if (error instanceof ServiceError) throw error;
        if (attempt === 2) throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB request timed out or failed', 503, true);
      } finally { clearTimeout(timeout); }
      await new Promise(resolve => setTimeout(resolve, 100 * (attempt + 1)));
    }
    throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB request failed', 503, true);
  }

  searchTitle(type: MediaType, query: string, page = 1): Promise<TmdbPage> { return this.request(`/search/${type}`, { query, page, include_adult: false }); }
  searchKeyword(query: string): Promise<TmdbPage<TmdbKeyword>> { return this.request('/search/keyword', { query, page: 1 }); }
  discover(type: MediaType, params: Record<string, string | number | boolean | undefined>): Promise<TmdbPage> {
    return this.request(`/discover/${type}`, { include_adult: false, ...params });
  }
  recommendations(type: MediaType, id: number, page: number): Promise<TmdbPage> { return this.request(`/${type}/${id}/recommendations`, { page }); }
  similar(type: MediaType, id: number, page: number): Promise<TmdbPage> { return this.request(`/${type}/${id}/similar`, { page }); }
  details(type: MediaType, id: number): Promise<TmdbDetails> {
    return this.request(`/${type}/${id}`, { append_to_response: type === 'tv' ? 'keywords,aggregate_credits' : 'keywords,credits' });
  }
  genres(type: MediaType): Promise<{ genres: TmdbGenre[] }> { return this.request(`/genre/${type}/list`); }
}

export function applyTmdbAuthentication(env: Pick<RecommendationEnv, 'TMDB_API_KEY' | 'TMDB_READ_ACCESS_TOKEN'>, url: URL, headers: HeadersInit): void {
  const explicitReadToken = env.TMDB_READ_ACCESS_TOKEN?.trim();
  const configuredApiKey = env.TMDB_API_KEY?.trim();
  const legacyMisnamedReadToken = configuredApiKey?.startsWith('eyJ') && configuredApiKey.length > 100 ? configuredApiKey : undefined;
  const readToken = explicitReadToken || legacyMisnamedReadToken;
  if (readToken) {
    (headers as Record<string, string>).authorization = `Bearer ${readToken}`;
  } else if (configuredApiKey) {
    url.searchParams.set('api_key', configuredApiKey);
  }
}
