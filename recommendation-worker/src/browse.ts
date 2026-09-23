import { summary } from './catalog';
import { TmdbClient } from './tmdb';
import { MediaType, RecommendationEnv, ServiceError } from './types';

export async function categories(env: RecommendationEnv) {
  const tmdb = new TmdbClient(env, 2);
  return { categories: (await Promise.all((['movie', 'tv'] as const).map(async type =>
    (await tmdb.genres(type)).genres.map(g => ({ id: g.id, name: g.name, type }))))).flat() };
}

// TMDB has a flat genre taxonomy. These are explicit Discover refinements, not invented TMDB genres.
export function refinements(type: MediaType, genres: Array<{ id: number; name: string }>, selected: number) {
  return [
    { id: 'popular', name: 'Popular', params: { sort_by: 'popularity.desc' } },
    { id: 'rated', name: 'Top rated', params: { sort_by: 'vote_average.desc', 'vote_count.gte': 100 } },
    { id: 'latest', name: 'Latest releases', params: { sort_by: type === 'movie' ? 'primary_release_date.desc' : 'first_air_date.desc',
      [type === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte']: new Date().toISOString().slice(0, 10) } },
    ...genres.filter(g => g.id !== selected).map(g => ({ id: `genre-${g.id}`, name: g.name, params: { with_genres: `${selected},${g.id}`, sort_by: 'popularity.desc' } })),
    ...[2020, 2010, 2000, 1990, 1980, 1970].map(year => ({ id: `decade-${year}`, name: `${year}s`, params: {
      sort_by: 'popularity.desc', [type === 'movie' ? 'primary_release_date.gte' : 'first_air_date.gte']: `${year}-01-01`,
      [type === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte']: `${year + 9}-12-31`,
    } })),
  ];
}

export async function browse(env: RecommendationEnv, token: string, page: number) {
  const match = /^(genre|keyword):(movie|tv):(\d+)(?::([a-z0-9-]+))?$/.exec(token);
  if (!match) throw new ServiceError('INVALID_REQUEST', 'Invalid category', 400, false);
  const [, kind, rawType, rawId, refinement = 'popular'] = match;
  const type = rawType as MediaType, id = Number(rawId);
  const tmdb = new TmdbClient(env, 6);
  const genres = (await tmdb.genres(type)).genres;
  if (kind === 'genre' && !genres.some(g => g.id === id)) throw new ServiceError('INVALID_REQUEST', 'Unknown genre', 400, false);
  const sections = refinements(type, genres, id);
  const selected = sections.find(s => s.id === refinement);
  if (!selected) throw new ServiceError('INVALID_REQUEST', 'Unknown refinement', 400, false);
  const map = new Map(genres.map(g => [g.id, g.name]));
  const results: ReturnType<typeof summary>[] = [];
  let cursor = page, totalPages = page;
  // Fill sparse pages without duplicating cards or leaving the requested category.
  for (let count = 0; count < 4 && results.length < 20 && cursor <= Math.min(totalPages, 500); count++, cursor++) {
    const data = await tmdb.discover(type, { ...(kind === 'genre' ? { with_genres: String(id), ...selected.params } : { with_keywords: String(id), sort_by: 'popularity.desc' }), page: cursor });
    totalPages = data.total_pages;
    for (const row of data.results) if (!results.some(r => r.tmdbId === row.id)) results.push(summary(row, type, map));
  }
  return { results, page: cursor - 1, hasMore: cursor <= Math.min(totalPages, 500), sections: sections.map(({ id, name }) => ({ id, name })) };
}
