import { summary } from './catalog';
import { TmdbClient, TmdbPage } from './tmdb';
import { MediaType, RecommendationEnv, ServiceError, TmdbListItem } from './types';

// Verified against TMDB keyword pages, 2026-09-13. Keep canonical IDs here,
// never infer them from a model. Sources: https://www.themoviedb.org/keyword/{id}-{slug}/movie
export const KEYWORDS = {
  psychologicalThriller: 12565, timeTravel: 4379, quantumMechanics: 8056,
  comingOfAge: 10683, unlikelyFriendship: 167982, maleFriendship: 3230,
  dystopia: 4565, revenge: 9748, heist: 10051, survival: 10349, martialArts: 779,
} as const;
export const DISCOVER_CATEGORIES = ['trending', 'new', 'top-rated', 'mind-bending', 'feel-good', 'dark', 'fast-paced'] as const;
export type DiscoverCategory = typeof DISCOVER_CATEGORIES[number];
type Recipe = { movie: number[]; tv: number[]; exclude: number[]; keywords: number[]; withoutKeywords?: number[] };
export const MOODS: Record<string, Recipe> = {
  'mind-bending': { movie: [878, 9648, 53], tv: [10765, 9648], exclude: [99, 10764, 10767, 10763],
    keywords: [KEYWORDS.psychologicalThriller, KEYWORDS.timeTravel, KEYWORDS.quantumMechanics] },
  'feel-good': { movie: [35, 10751, 10749, 12, 10402], tv: [35, 10751, 10759], exclude: [27, 53, 10752, 10768, 80, 99, 10764],
    keywords: [KEYWORDS.comingOfAge, KEYWORDS.unlikelyFriendship, KEYWORDS.maleFriendship],
    withoutKeywords: [KEYWORDS.psychologicalThriller, KEYWORDS.dystopia, KEYWORDS.revenge] },
  dark: { movie: [53, 80, 9648, 27, 18], tv: [80, 9648, 18, 10765], exclude: [10751, 10762, 99, 10764],
    keywords: [KEYWORDS.psychologicalThriller, KEYWORDS.dystopia, KEYWORDS.revenge] },
  'fast-paced': { movie: [28, 12, 53, 80], tv: [10759, 80], exclude: [99, 10764, 10767],
    keywords: [KEYWORDS.heist, KEYWORDS.survival, KEYWORDS.martialArts] },
};

export function discoveryParams(category: DiscoverCategory, type: MediaType, page: number, today: string, relaxed = false) {
  const recipe = MOODS[category];
  const params: Record<string, string | number | boolean | undefined> = {
    page, language: 'en-US', include_adult: false, include_video: false,
    sort_by: category === 'top-rated' ? 'vote_average.desc' : 'popularity.desc',
    'vote_count.gte': category === 'top-rated' ? (type === 'movie' ? 500 : 200) : (relaxed ? 30 : 80),
    'vote_average.gte': category === 'top-rated' ? 7 : (relaxed ? 6 : 6.5),
    [type === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte']: today,
    ...(type === 'tv' ? { with_status: '0|3|4', without_genres: '99,10764,10767,10763' } : {}),
  };
  if (category === 'new') {
    const since = new Date(today); since.setUTCDate(since.getUTCDate() - 120);
    params['primary_release_date.gte'] = since.toISOString().slice(0, 10);
    params['vote_count.gte'] = 20;
    params['vote_average.gte'] = 5.5;
    params.sort_by = 'primary_release_date.desc';
  }
  if (recipe) {
    // OR within the allowed genre/concept sets; AND between filters. Relax only
    // confidence thresholds, never remove the mood's concepts or exclusions.
    params.with_genres = recipe[type].join('|');
    params.with_keywords = recipe.keywords.join('|');
    params.without_genres = recipe.exclude.join(',');
    params.without_keywords = recipe.withoutKeywords?.join(',');
  }
  return params;
}

export function validDiscoveryItem(item: TmdbListItem & { adult?: boolean }, type: MediaType, category: DiscoverCategory, today: string): boolean {
  const date = type === 'movie' ? item.release_date : item.first_air_date;
  const votes = item.vote_count || 0;
  const minVotes = category === 'top-rated' ? (type === 'movie' ? 500 : 200) : category === 'new' || category === 'trending' ? 20 : 30;
  const recipe = MOODS[category];
  return !item.adult && item.id > 0 && Boolean((item.title || item.name)?.trim()) && Boolean(item.poster_path && item.backdrop_path)
    && Boolean(date && /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(Date.parse(date)) && date <= today)
    && votes >= minVotes && (item.vote_average || 0) >= (category === 'new' || category === 'trending' ? 5.5 : 6)
    && (!recipe || (item.genre_ids || []).some(id => recipe[type].includes(id)))
    && (!recipe || !(item.genre_ids || []).some(id => recipe.exclude.includes(id)));
}

export function confidenceScore(item: TmdbListItem, category: DiscoverCategory): number {
  const votes = item.vote_count || 0;
  const weighted = ((item.vote_average || 0) * votes + 6.5 * 250) / (votes + 250);
  return weighted + (category === 'top-rated' ? 0 : Math.log1p(item.popularity || 0) * 0.22);
}

export async function discoverCategory(env: RecommendationEnv, category: DiscoverCategory, filter: 'all' | MediaType, page: number, today = new Date().toISOString().slice(0, 10)) {
  const tmdb = new TmdbClient(env, 24);
  const types: MediaType[] = filter === 'all' ? ['movie', 'tv'] : [filter];
  const batches = await Promise.all(types.map(async type => {
    const genres = await tmdb.genres(type);
    const fetchPage = (): Promise<TmdbPage> => category === 'trending' ? tmdb.trending(type, page)
      : category === 'new' && type === 'tv' ? tmdb.onTheAirTv(page)
      : tmdb.discover(type, discoveryParams(category, type, page, today));
    const first = await fetchPage();
    let rows = first.results.filter(item => validDiscoveryItem(item, type, category, today));
    let totalPages = first.total_pages;
    if (MOODS[category] && rows.length < 8) {
      const relaxed = await tmdb.discover(type, discoveryParams(category, type, page, today, true));
      rows = [...rows, ...relaxed.results.filter(item => validDiscoveryItem(item, type, category, today))];
      totalPages = Math.max(totalPages, relaxed.total_pages);
    }
    const map = new Map(genres.genres.map(genre => [genre.id, genre.name]));
    return { rows: rows.map(item => ({ item, type, media: summary(item, type, map) })), totalPages };
  }));
  const seen = new Set<string>();
  const results = batches.flatMap(batch => batch.rows).sort((a, b) => confidenceScore(b.item, category) - confidenceScore(a.item, category) || a.item.id - b.item.id)
    .filter(row => { const key = `${row.type}:${row.item.id}`; if (seen.has(key)) return false; seen.add(key); return true; }).map(row => row.media);
  return { category, mediaType: filter, page, hasMore: page < Math.min(500, Math.max(...batches.map(b => b.totalPages))), results };
}

export async function catalogueSearch(env: RecommendationEnv, query: string, filter: 'all' | MediaType, page: number) {
  query = query.trim().slice(0, 160);
  if (!query) return { results: [], people: [], page, hasMore: false };
  const tmdb = new TmdbClient(env, 9);
  const response = filter === 'all' ? await tmdb.searchMulti(query, page) : await tmdb.searchTitle(filter, query, page);
  const rows = response.results as Array<TmdbListItem & { media_type?: MediaType | 'person'; profile_path?: string; adult?: boolean }>;
  const types: MediaType[] = filter === 'all' ? ['movie', 'tv'] : [filter];
  const maps = new Map(await Promise.all(types.map(async type => [type, new Map((await tmdb.genres(type)).genres.map(g => [g.id, g.name]))] as const)));
  return {
    page, hasMore: page < Math.min(500, response.total_pages),
    results: rows.filter(item => !item.adult && (filter !== 'all' || item.media_type === 'movie' || item.media_type === 'tv'))
      .map(item => { const type = filter === 'all' ? item.media_type as MediaType : filter; return summary(item, type, maps.get(type)!); }),
    people: rows.filter(item => !item.adult && item.media_type === 'person').map(item => ({ tmdbId: item.id, name: item.name, profilePath: item.profile_path })),
  };
}

export function discoverArguments(url: URL) {
  const filter = url.searchParams.get('type') || 'all';
  const page = Number(url.searchParams.get('page') || '1');
  if (!['all', 'movie', 'tv'].includes(filter) || !Number.isInteger(page) || page < 1 || page > 500)
    throw new ServiceError('INVALID_REQUEST', 'Invalid catalogue filter or page', 400, false);
  return { filter: filter as 'all' | MediaType, page };
}
