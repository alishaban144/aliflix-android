import { DurableObject } from 'cloudflare:workers';
import { CatalogHomeFeed, homeFeed } from './catalog';
import { RecommendationEnv, ServiceError } from './types';

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const HOME_FRESH_MS = 30 * MINUTE * 1000;
const HOME_RETENTION_MS = 7 * DAY * 1000;
const HOME_RETRY_MS = 2 * MINUTE * 1000;
const CACHE_CREATED = 'x-catalogue-created';

interface HomeSnapshot {
  createdAt: number;
  feed: CatalogHomeFeed;
}

/** One shared, restart-safe Home snapshot across Worker locations. */
export class CatalogueHome extends DurableObject<RecommendationEnv> {
  private refresh?: Promise<HomeSnapshot>;

  async get(): Promise<HomeSnapshot> {
    const saved = await this.ctx.storage.get<HomeSnapshot>('snapshot');
    const age = saved ? Date.now() - saved.createdAt : Infinity;
    if (saved && age < HOME_FRESH_MS) return saved;

    if (!this.refresh) {
      const retryAt = await this.ctx.storage.get<number>('retry-at');
      if (!this.refresh && (!retryAt || Date.now() >= retryAt)) {
        this.refresh = this.rebuild().finally(() => { this.refresh = undefined; });
      }
    }

    if (saved && age < HOME_RETENTION_MS) {
      if (this.refresh) this.ctx.waitUntil(this.refresh.catch(() => undefined));
      return saved;
    }
    if (this.refresh) return this.refresh;
    throw new ServiceError('TMDB_UNAVAILABLE', 'Home refresh is temporarily paused', 503, true);
  }

  private async rebuild(): Promise<HomeSnapshot> {
    await this.ctx.storage.put('retry-at', Date.now() + HOME_RETRY_MS);
    const feed = await homeFeed(this.env);
    const snapshot = { createdAt: Date.now(), feed };
    await this.ctx.storage.put('snapshot', snapshot);
    return snapshot;
  }
}

interface CacheOptions {
  freshSeconds: number;
  retainSeconds: number;
}

/** Retain public catalogue responses so a failed refresh can still serve the last good data. */
export async function cachedCatalogue(
  keyUrl: URL,
  options: CacheOptions,
  ctx: ExecutionContext | undefined,
  load: () => Promise<unknown>,
): Promise<Response> {
  keyUrl.searchParams.set('_catalogue_cache', '1');
  const key = new Request(keyUrl);
  const cached = await caches.default.match(key).catch(() => undefined);
  const createdAt = Number(cached?.headers.get(CACHE_CREATED));
  const age = (Date.now() - createdAt) / 1000;
  const clientHeaders = (ttl: number) => ({ 'cache-control': `public, max-age=${ttl}` });
  const fromCache = (ttl: number) => new Response(cached!.body, {
    headers: { 'content-type': 'application/json; charset=utf-8', ...clientHeaders(ttl) },
  });
  if (cached && age < options.freshSeconds) {
    return fromCache(Math.min(300, Math.max(0, Math.floor(options.freshSeconds - age))));
  }

  try {
    const response = Response.json(await load(), { headers: clientHeaders(Math.min(300, options.freshSeconds)) });
    const stored = new Response(response.clone().body, {
      headers: {
        'content-type': 'application/json; charset=utf-8',
        ...clientHeaders(options.retainSeconds),
        [CACHE_CREATED]: String(Date.now()),
      },
    });
    const write = caches.default.put(key, stored).catch(() => undefined);
    if (ctx) ctx.waitUntil(write);
    else await write;
    return response;
  } catch (error) {
    if (!cached || !(error instanceof ServiceError) || (!error.retryable && error.status < 500)) throw error;
    return fromCache(30);
  }
}

export const HOME_EDGE_CACHE = { freshSeconds: 5 * MINUTE, retainSeconds: DAY };
export const METADATA_CACHE = { freshSeconds: 6 * HOUR, retainSeconds: 7 * DAY };
export const DISCOVER_CACHE = { freshSeconds: 15 * MINUTE, retainSeconds: DAY };
export const SEARCH_CACHE = { freshSeconds: 5 * MINUTE, retainSeconds: HOUR };
