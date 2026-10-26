import { summary } from './catalog';
import { TmdbClient } from './tmdb';
import { MediaType, RecommendationEnv, ServiceError } from './types';
import { subgenres } from './subgenres';

export async function categories(env: RecommendationEnv) {
  const tmdb = new TmdbClient(env, 2);
  return { categories: (await Promise.all((['movie', 'tv'] as const).map(async type =>
    (await tmdb.genres(type)).genres.map(g => ({ id: g.id, name: g.name, type }))))).flat() };
}

// Keep the public helper stable, while replacing arbitrary genre cross-products.
export function refinements(type: MediaType, genres: Array<{ id: number; name: string }>, selected: number) {
  return subgenres(type, selected);
}

export async function browse(env: RecommendationEnv, token: string, page: number, excludedTmdbIds: number[] = []) {
  const match = /^(genre|keyword):(movie|tv):(\d+)(?::([a-z0-9-]+))?$/.exec(token);
  if (!match) throw new ServiceError('INVALID_REQUEST', 'Invalid category', 400, false);
  const [, kind, rawType, rawId, refinement = 'popular'] = match;
  const type = rawType as MediaType, id = Number(rawId);
  const tmdb = new TmdbClient(env, 16);
  const genres = (await tmdb.genres(type)).genres;
  if (kind === 'genre' && !genres.some(g => g.id === id)) throw new ServiceError('INVALID_REQUEST', 'Unknown genre', 400, false);
  const keyword = kind === 'keyword';
  const sections = keyword ? [] : refinements(type, genres, id);
  const selected = keyword ? null : sections.find(s => s.id === refinement);
  if (!keyword && !selected && refinement !== 'popular') throw new ServiceError('INVALID_REQUEST', 'Unknown refinement', 400, false);
  const params: Record<string, string | number> = { sort_by: 'popularity.desc' };
  if (keyword) params.with_keywords = String(id);
  else {
    params.with_genres = [...new Set([id, ...(selected?.genres ?? [])])].join(',');
    if (selected?.keywords.length) {
      const normalize = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
      const matches = await Promise.all(selected.keywords.map(async name =>
        (await tmdb.searchKeyword(name)).results.filter(k => normalize(k.name) === normalize(name)).map(k => k.id)));
      const ids = [...new Set(matches.flat())];
      if (!ids.length) return { results: [], page, hasMore: false, sections: sections.map(({ id, name }) => ({ id, name })) };
      params.with_keywords = ids.join('|');
    }
  }
  const map = new Map(genres.map(g => [g.id, g.name]));
  const excluded = new Set(excludedTmdbIds.filter(id => Number.isInteger(id) && id > 0).slice(0, 500));
  const results: ReturnType<typeof summary>[] = [];
  const minimumResults = keyword ? 24 : 20;
  const maximumPages = keyword ? 4 : 5;
  let cursor = page, totalPages = page;
  // Fill sparse pages without duplicating cards or leaving the requested category.
  for (let count = 0; count < maximumPages && results.length < minimumResults && cursor <= Math.min(totalPages, 500); count++, cursor++) {
    const data = await tmdb.discover(type, { ...params, page: cursor });
    totalPages = data.total_pages;
    for (const row of data.results) if (!excluded.has(row.id) && !results.some(r => r.tmdbId === row.id)) results.push(summary(row, type, map));
  }
  return { results, page: cursor - 1, hasMore: cursor <= Math.min(totalPages, 500), sections: sections.map(({ id, name }) => ({ id, name })) };
}
