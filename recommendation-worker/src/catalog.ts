import { TmdbClient, TmdbCompany, TmdbDetails, TmdbPage, TmdbWatchProvider } from './tmdb';
import { MediaType, RecommendationEnv, ServiceError, TmdbGenre, TmdbListItem } from './types';

export interface CatalogPerson {
  tmdbId: number;
  name: string;
  profilePath?: string;
}

export interface CatalogMediaSummary {
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  originalTitle?: string;
  overview?: string;
  posterPath?: string;
  backdropPath?: string;
  releaseDate?: string;
  genres: string[];
  originalLanguage?: string;
  originCountries: string[];
  runtimeMinutes?: number;
  tmdbRating?: number;
  tmdbVoteCount?: number;
}

export interface CatalogReview {
  id: string;
  author: string;
  authorName?: string;
  authorUsername?: string;
  avatarPath?: string;
  rating?: number;
  content: string;
  createdAt?: string;
  url?: string;
}

export interface CatalogTitleDetails extends CatalogMediaSummary {
  imdbId?: string;
  status?: string;
  creators: CatalogPerson[];
  cast: CatalogPerson[];
  reviews: CatalogReview[];
  recommendations: CatalogMediaSummary[];
}

export interface CatalogHomeRail {
  title: string;
  items: CatalogMediaSummary[];
}

export interface CatalogHomeFeed {
  hero: CatalogMediaSummary;
  rails: CatalogHomeRail[];
  editorialPicks: CatalogMediaSummary[];
}

export interface CatalogCompany {
  tmdbId: number;
  name: string;
  logoPath?: string;
  originCountry?: string;
}

export type CatalogTvEntityCategory = 'network' | 'streaming_provider' | 'production_company';

export interface CatalogTvEntityRail extends CatalogHomeRail {
  entityId: number;
  category: CatalogTvEntityCategory;
  logoPath?: string;
  popularityScore: number;
}

export interface CatalogTvNetworkFeed {
  region: string;
  attribution: string;
  rails: CatalogTvEntityRail[];
}

interface HomeGenreRailSpec {
  title: string;
  mediaType: MediaType;
  genreIds: number[];
}

const HOME_GENRE_RAILS: HomeGenreRailSpec[] = [
  { title: 'Action movies', mediaType: 'movie', genreIds: [28] },
  { title: 'Comedy movies', mediaType: 'movie', genreIds: [35] },
  { title: 'Crime series', mediaType: 'tv', genreIds: [80] },
  { title: 'Drama series', mediaType: 'tv', genreIds: [18] },
  { title: 'Science fiction movies', mediaType: 'movie', genreIds: [878] },
  { title: 'Science fiction & fantasy series', mediaType: 'tv', genreIds: [10765] },
  { title: 'Horror movies', mediaType: 'movie', genreIds: [27] },
  { title: 'Romance movies', mediaType: 'movie', genreIds: [10749] },
  { title: 'Mystery series', mediaType: 'tv', genreIds: [9648] },
  { title: 'Animated movies', mediaType: 'movie', genreIds: [16] },
  { title: 'Documentaries', mediaType: 'movie', genreIds: [99] },
  { title: 'Action thrillers', mediaType: 'movie', genreIds: [28, 53] },
  { title: 'Romantic comedies', mediaType: 'movie', genreIds: [10749, 35] },
  { title: 'Crime dramas', mediaType: 'tv', genreIds: [80, 18] },
];

async function discoverHomeGenreRails(
  tmdb: TmdbClient,
  today: string,
): Promise<Array<{ spec: HomeGenreRailSpec; page: TmdbPage }>> {
  const results: Array<{ spec: HomeGenreRailSpec; page: TmdbPage }> = [];
  for (let start = 0; start < HOME_GENRE_RAILS.length; start += 6) {
    const batch = HOME_GENRE_RAILS.slice(start, start + 6);
    results.push(...await Promise.all(batch.map(async spec => ({
      spec,
      page: await tmdb.discover(spec.mediaType, {
        page: 1,
        sort_by: 'popularity.desc',
        with_genres: spec.genreIds.join(','),
        'vote_count.gte': spec.mediaType === 'movie' ? 50 : 20,
        [spec.mediaType === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte']: today,
      }),
    }))));
  }
  return results;
}

const present = (value: string | null | undefined): string | undefined => value?.trim() || undefined;
const releaseDate = (item: TmdbListItem): string | undefined => present(item.release_date || item.first_air_date);
const mediaTitle = (item: TmdbListItem): string => present(item.title || item.name) || 'Untitled';

function genreMap(genres: TmdbGenre[]): Map<number, string> {
  return new Map(genres.map(genre => [genre.id, genre.name]));
}

function summary(
  item: TmdbListItem,
  mediaType: MediaType,
  genresById: Map<number, string>,
  explicitGenres: TmdbGenre[] = [],
): CatalogMediaSummary {
  const genres = explicitGenres.length > 0
    ? explicitGenres.map(genre => genre.name)
    : (item.genre_ids || []).map(id => genresById.get(id)).filter((name): name is string => Boolean(name));
  return {
    tmdbId: item.id,
    mediaType,
    title: mediaTitle(item),
    originalTitle: present(item.original_title || item.original_name),
    overview: present(item.overview),
    posterPath: present(item.poster_path),
    backdropPath: present(item.backdrop_path),
    releaseDate: releaseDate(item),
    genres,
    originalLanguage: present(item.original_language),
    originCountries: item.origin_country || [],
    tmdbRating: item.vote_average,
    tmdbVoteCount: item.vote_count,
  };
}

function detailsSummary(details: TmdbDetails, mediaType: MediaType): CatalogTitleDetails {
  const creators = mediaType === 'tv'
    ? (details.created_by || [])
    : (details.credits?.crew || []).filter(person =>
        person.job === 'Director' || person.job === 'Writer' || person.job === 'Screenplay' || person.job === 'Story',
      );
  const cast = mediaType === 'tv' ? details.aggregate_credits?.cast || [] : details.credits?.cast || [];

  const rawRecommendations = [
    ...(details.recommendations?.results || []).map(item => ({ item, priority: 2 })),
    ...(details.similar?.results || []).map(item => ({ item, priority: 1 })),
  ];

  const sourceGenreIds = new Set((details.genres || []).map(g => g.id));
  const seenIds = new Set<number>([details.id]);
  const candidateScores: Array<{ candidate: CatalogMediaSummary; score: number }> = [];

  for (const { item, priority } of rawRecommendations) {
    if (!item.id || seenIds.has(item.id)) continue;
    seenIds.add(item.id);
    const itemPoster = present(item.poster_path);
    if (!itemPoster) continue;
    const voteCount = item.vote_count || 0;
    const voteAverage = item.vote_average || 0;
    if (voteCount < 3 && voteAverage === 0) continue;

    const itemGenreIds = item.genre_ids || [];
    const sharedGenres = itemGenreIds.filter(id => sourceGenreIds.has(id)).length;
    const genreScore = sourceGenreIds.size > 0 ? (sharedGenres / sourceGenreIds.size) * 30 : 10;
    const score = (priority * 25) + (voteAverage * 4) + genreScore + Math.log10(Math.max(1, voteCount));

    candidateScores.push({
      candidate: summary(item, mediaType, new Map(), []),
      score,
    });
  }

  const recommendations = candidateScores
    .sort((a, b) => b.score - a.score)
    .map(entry => entry.candidate)
    .slice(0, 24);

  return {
    ...summary(details, mediaType, new Map(), details.genres || []),
    imdbId: present(details.external_ids?.imdb_id || details.imdb_id),
    runtimeMinutes: mediaType === 'movie'
      ? details.runtime || undefined
      : details.episode_run_time?.find(value => value > 0),
    status: present(details.status),
    creators: creators
      .filter(person => person.id > 0 && present(person.name))
      .filter((person, index, values) => values.findIndex(other => other.id === person.id) === index)
      .slice(0, 12)
      .map(person => ({ tmdbId: person.id, name: person.name, profilePath: present('profile_path' in person ? person.profile_path : undefined) })),
    cast: cast
      .filter(person => person.id > 0 && present(person.name))
      .filter((person, index, values) => values.findIndex(other => other.id === person.id) === index)
      .slice(0, 12)
      .map(person => ({ tmdbId: person.id, name: person.name })),
    reviews: (details.reviews?.results || [])
      .filter(review => Boolean(present(review.id) && present(review.content)))
      .slice(0, 10)
      .map(review => ({
        id: review.id,
        author: review.author || review.author_details?.name || review.author_details?.username || 'Anonymous',
        authorName: present(review.author_details?.name),
        authorUsername: present(review.author_details?.username),
        avatarPath: present(review.author_details?.avatar_path),
        rating: typeof review.author_details?.rating === 'number' ? review.author_details.rating : undefined,
        content: review.content.trim(),
        createdAt: present(review.created_at),
        url: present(review.url),
      })),
    recommendations,
  };
}

export async function titleDetails(env: RecommendationEnv, mediaType: MediaType, id: number): Promise<CatalogTitleDetails> {
  return detailsSummary(await new TmdbClient(env, 4).details(mediaType, id), mediaType);
}

function normalizedSearchText(value: string): string {
  return value.trim().toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

function titleSearchScore(item: TmdbListItem, mediaType: MediaType, query: string): number {
  const title = normalizedSearchText(mediaType === 'movie' ? item.title || '' : item.name || '');
  const originalTitle = normalizedSearchText(mediaType === 'movie' ? item.original_title || '' : item.original_name || '');
  const exact = title === query || originalTitle === query;
  const prefix = title.startsWith(query) || originalTitle.startsWith(query);
  const contains = title.includes(query) || originalTitle.includes(query);
  return (exact ? 10_000 : prefix ? 5_000 : contains ? 2_000 : 0) +
    (item.popularity || 0) * 2 + Math.log10(Math.max(1, item.vote_count || 0)) * 20;
}

export async function titleSearch(env: RecommendationEnv, rawQuery: string): Promise<{ results: CatalogMediaSummary[] }> {
  const query = rawQuery.trim();
  const normalizedQuery = normalizedSearchText(query);
  if (normalizedQuery.length < 2) throw new ServiceError('INVALID_QUERY', 'Enter at least two characters', 400, false);
  const tmdb = new TmdbClient(env, 4);
  const [movies, tv, movieGenres, tvGenres] = await Promise.all([
    tmdb.searchTitle('movie', query),
    tmdb.searchTitle('tv', query),
    tmdb.genres('movie'),
    tmdb.genres('tv'),
  ]);
  const movieGenreMap = genreMap(movieGenres.genres);
  const tvGenreMap = genreMap(tvGenres.genres);
  const ranked = [
    ...movies.results.map(item => ({ item, mediaType: 'movie' as const, score: titleSearchScore(item, 'movie', normalizedQuery) })),
    ...tv.results.map(item => ({ item, mediaType: 'tv' as const, score: titleSearchScore(item, 'tv', normalizedQuery) })),
  ]
    .filter(entry => entry.item.id > 0 && mediaTitle(entry.item) !== 'Untitled')
    .sort((left, right) => right.score - left.score || mediaTitle(left.item).localeCompare(mediaTitle(right.item)))
    .filter((entry, index, values) => values.findIndex(other => (
      other.mediaType === entry.mediaType && other.item.id === entry.item.id
    )) === index)
    .slice(0, 30)
    .map(entry => summary(
      entry.item,
      entry.mediaType,
      entry.mediaType === 'movie' ? movieGenreMap : tvGenreMap,
    ));
  return { results: ranked };
}

export async function companySearch(env: RecommendationEnv, rawQuery: string): Promise<{ results: CatalogCompany[] }> {
  const query = rawQuery.trim();
  const normalizedQuery = normalizedSearchText(query);
  if (normalizedQuery.length < 2) throw new ServiceError('INVALID_QUERY', 'Enter at least two characters', 400, false);
  const response = await new TmdbClient(env, 2).searchCompany(query);
  const results = (response.results || [])
    .filter(company => company.id > 0 && present(company.name))
    .filter((company, index, values) => values.findIndex(other => other.id === company.id) === index)
    .sort((left, right) => {
      const leftName = normalizedSearchText(left.name);
      const rightName = normalizedSearchText(right.name);
      const leftScore = leftName === normalizedQuery ? 3 : leftName.startsWith(normalizedQuery) ? 2 : 1;
      const rightScore = rightName === normalizedQuery ? 3 : rightName.startsWith(normalizedQuery) ? 2 : 1;
      return rightScore - leftScore || left.name.localeCompare(right.name);
    })
    .slice(0, 20)
    .map(company => ({
      tmdbId: company.id,
      name: company.name.trim(),
      logoPath: present(company.logo_path),
      originCountry: present(company.origin_country),
    }));
  return { results };
}

interface TvEntityCandidate {
  id: number;
  name: string;
  category: CatalogTvEntityCategory;
  logoPath?: string;
  seedPopularity: number;
}

function displayPriority(provider: TmdbWatchProvider, region: string): number {
  return provider.display_priorities?.[region] ?? provider.display_priority ?? Number.MAX_SAFE_INTEGER;
}

function addTvEntity(
  entities: Map<string, TvEntityCandidate>,
  candidate: Omit<TvEntityCandidate, 'seedPopularity'>,
  popularity: number,
): void {
  if (candidate.id <= 0 || !present(candidate.name)) return;
  const key = `${candidate.category}:${candidate.id}`;
  const existing = entities.get(key);
  entities.set(key, {
    ...candidate,
    name: candidate.name.trim(),
    seedPopularity: (existing?.seedPopularity || 0) + Math.max(0, popularity),
  });
}

export async function tvNetworkFeed(env: RecommendationEnv, region: string): Promise<CatalogTvNetworkFeed> {
  const tmdb = new TmdbClient(env, 48);
  const today = new Date().toISOString().slice(0, 10);
  const [popular1, popular2, tvGenres, providerResponse] = await Promise.all([
    tmdb.popular('tv', 1),
    tmdb.popular('tv', 2),
    tmdb.genres('tv'),
    tmdb.watchProviders('tv', region),
  ]);
  const popular = [...popular1.results, ...popular2.results]
    .filter((item, index, values) => values.findIndex(other => other.id === item.id) === index)
    .slice(0, 18);
  const details: TmdbDetails[] = [];
  for (let start = 0; start < popular.length; start += 6) {
    details.push(...await Promise.all(popular.slice(start, start + 6).map(item => tmdb.tvAttributionDetails(item.id))));
  }

  const entities = new Map<string, TvEntityCandidate>();
  details.forEach(detail => {
    const popularity = (detail.popularity || 0) + Math.log10(Math.max(1, detail.vote_count || 0)) * 4;
    (detail.networks || []).forEach(network => addTvEntity(entities, {
      id: network.id,
      name: network.name,
      logoPath: present(network.logo_path),
      category: 'network',
    }, popularity));
    (detail.production_companies || []).forEach(company => addTvEntity(entities, {
      id: company.id,
      name: company.name,
      logoPath: present(company.logo_path),
      category: 'production_company',
    }, popularity));
  });

  const featuredCompanyNames = ['A24', 'Apple Studios'];
  const featuredCompanies = await Promise.all(featuredCompanyNames.map(name => tmdb.searchCompany(name)));
  const featuredCompanyEntities = featuredCompanyNames.flatMap((name, index) => {
    const exact = featuredCompanies[index].results.find(company => (
      normalizedSearchText(company.name) === normalizedSearchText(name)
    ));
    return exact ? [{
      id: exact.id,
      name: exact.name,
      logoPath: present(exact.logo_path),
      category: 'production_company' as const,
      seedPopularity: Number.MAX_SAFE_INTEGER,
    }] : [];
  });

  const networks = [...entities.values()]
    .filter(entity => entity.category === 'network')
    .sort((left, right) => right.seedPopularity - left.seedPopularity || left.name.localeCompare(right.name))
    .slice(0, 6);
  const discoveredCompanies = [...entities.values()]
    .filter(entity => entity.category === 'production_company')
    .sort((left, right) => right.seedPopularity - left.seedPopularity || left.name.localeCompare(right.name));
  const companies = [...featuredCompanyEntities, ...discoveredCompanies]
    .filter((entity, index, values) => values.findIndex(other => other.id === entity.id) === index)
    .slice(0, 6);
  const providers: TvEntityCandidate[] = (providerResponse.results || [])
    .filter(provider => provider.provider_id > 0 && present(provider.provider_name))
    .sort((left, right) => displayPriority(left, region) - displayPriority(right, region) || left.provider_name.localeCompare(right.provider_name))
    .slice(0, 8)
    .map(provider => ({
      id: provider.provider_id,
      name: provider.provider_name.trim(),
      logoPath: present(provider.logo_path),
      category: 'streaming_provider',
      seedPopularity: Math.max(0, 1_000 - displayPriority(provider, region)),
    }));
  const selectedEntities = [...networks, ...companies, ...providers];
  const tvGenreMap = genreMap(tvGenres.genres);
  const rails: CatalogTvEntityRail[] = [];
  for (let start = 0; start < selectedEntities.length; start += 6) {
    const batch = selectedEntities.slice(start, start + 6);
    const pages = await Promise.all(batch.map(entity => tmdb.discover('tv', {
      page: 1,
      sort_by: 'popularity.desc',
      'first_air_date.lte': today,
      'vote_count.gte': 5,
      with_networks: entity.category === 'network' ? entity.id : undefined,
      with_companies: entity.category === 'production_company' ? entity.id : undefined,
      with_watch_providers: entity.category === 'streaming_provider' ? entity.id : undefined,
      watch_region: entity.category === 'streaming_provider' ? region : undefined,
      with_watch_monetization_types: entity.category === 'streaming_provider' ? 'flatrate|free|ads' : undefined,
    })));
    pages.forEach((page, index) => {
      const entity = batch[index];
      const items = (page.results || [])
        .filter(item => item.id > 0 && item.poster_path && item.first_air_date && item.first_air_date <= today)
        .filter((item, itemIndex, values) => values.findIndex(other => other.id === item.id) === itemIndex)
        .slice(0, 20)
        .map(item => summary(item, 'tv', tvGenreMap));
      if (!items.length) return;
      const popularityScore = (page.results || []).slice(0, 10).reduce(
        (score, item) => score + (item.popularity || 0) + Math.log10(Math.max(1, item.vote_count || 0)),
        0,
      );
      rails.push({
        entityId: entity.id,
        title: entity.name,
        category: entity.category,
        logoPath: entity.logoPath,
        popularityScore: Number(popularityScore.toFixed(3)),
        items,
      });
    });
  }
  rails.sort((left, right) => right.popularityScore - left.popularityScore || left.title.localeCompare(right.title));
  return {
    region,
    attribution: 'Streaming availability data by JustWatch',
    rails,
  };
}

export async function personCredits(env: RecommendationEnv, id: number): Promise<{ person: CatalogPerson; results: CatalogMediaSummary[] }> {
  const tmdb = new TmdbClient(env, 8);
  const [person, credits, movieGenres, tvGenres] = await Promise.all([
    tmdb.person(id),
    tmdb.personCombinedCredits(id),
    tmdb.genres('movie'),
    tmdb.genres('tv'),
  ]);
  if (person.id !== id || !present(person.name)) {
    throw new ServiceError('TMDB_NOT_FOUND', 'TMDB person identity could not be verified', 404, false);
  }
  const movieGenreMap = genreMap(movieGenres.genres);
  const tvGenreMap = genreMap(tvGenres.genres);
  const values = [...(credits.crew || []), ...(credits.cast || [])]
    .filter(item => item.media_type === 'movie' || item.media_type === 'tv')
    .map(item => summary(item, item.media_type!, item.media_type === 'movie' ? movieGenreMap : tvGenreMap))
    .filter(item => item.title !== 'Untitled')
    .filter((item, index, items) => items.findIndex(other => other.mediaType === item.mediaType && other.tmdbId === item.tmdbId) === index)
    .sort((left, right) => {
      const date = (right.releaseDate || '').localeCompare(left.releaseDate || '');
      if (date !== 0) return date;
      return (right.tmdbRating || 0) - (left.tmdbRating || 0);
    })
    .slice(0, 120);
  return {
    person: {
      tmdbId: person.id,
      name: person.name,
      profilePath: present(person.profile_path),
    },
    results: values,
  };
}

export async function editorialPicks(env: RecommendationEnv): Promise<{ results: CatalogMediaSummary[] }> {
  const tmdb = new TmdbClient(env, 12);
  const today = new Date().toISOString().slice(0, 10);
  const from = `${new Date().getUTCFullYear() - 4}-01-01`;
  const discover = (mediaType: MediaType, page: number) => tmdb.discover(mediaType, {
    page,
    sort_by: 'vote_average.desc',
    'vote_average.gte': 7,
    'vote_count.gte': mediaType === 'movie' ? 500 : 250,
    [mediaType === 'movie' ? 'primary_release_date.gte' : 'first_air_date.gte']: from,
    [mediaType === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte']: today,
  });
  const [movies1, movies2, tv1, tv2, movieGenres, tvGenres] = await Promise.all([
    discover('movie', 1),
    discover('movie', 2),
    discover('tv', 1),
    discover('tv', 2),
    tmdb.genres('movie'),
    tmdb.genres('tv'),
  ]);
  const movieGenreMap = genreMap(movieGenres.genres);
  const tvGenreMap = genreMap(tvGenres.genres);
  const score = (item: CatalogMediaSummary): number => {
    const year = Number(item.releaseDate?.slice(0, 4)) || new Date().getUTCFullYear() - 4;
    const recency = Math.max(0, year - (new Date().getUTCFullYear() - 4));
    return (item.tmdbRating || 0) * 10 + recency * 1.4 + Math.log10(Math.max(1, item.tmdbVoteCount || 0));
  };
  const values = [
    ...movies1.results.map(item => summary(item, 'movie', movieGenreMap)),
    ...movies2.results.map(item => summary(item, 'movie', movieGenreMap)),
    ...tv1.results.map(item => summary(item, 'tv', tvGenreMap)),
    ...tv2.results.map(item => summary(item, 'tv', tvGenreMap)),
  ]
    .filter(item => item.posterPath && item.releaseDate && item.releaseDate <= today)
    .filter((item, index, items) => items.findIndex(other => other.mediaType === item.mediaType && other.tmdbId === item.tmdbId) === index)
    .sort((left, right) => score(right) - score(left))
    .slice(0, 40);
  return { results: values };
}

export async function homeFeed(env: RecommendationEnv): Promise<CatalogHomeFeed> {
  const tmdb = new TmdbClient(env, 40);
  const today = new Date().toISOString().slice(0, 10);
  const currentYear = new Date().getUTCFullYear();
  const recentFrom = `${currentYear - 4}-01-01`;

  // Keep each batch at six concurrent requests. This avoids request bursts while
  // still delivering one complete, internally consistent Home snapshot.
  const [movieGenres, tvGenres, trending1, trending2, nowPlaying, onTheAir] = await Promise.all([
    tmdb.genres('movie'),
    tmdb.genres('tv'),
    tmdb.trendingAll(1),
    tmdb.trendingAll(2),
    tmdb.nowPlayingMovies(1),
    tmdb.onTheAirTv(1),
  ]);
  const [popularMovies1, popularMovies2, popularTv1, popularTv2, ratedMovies, ratedTv] = await Promise.all([
    tmdb.popular('movie', 1),
    tmdb.popular('movie', 2),
    tmdb.popular('tv', 1),
    tmdb.popular('tv', 2),
    tmdb.discover('movie', {
      page: 1,
      sort_by: 'vote_average.desc',
      'vote_average.gte': 7,
      'vote_count.gte': 500,
      'primary_release_date.gte': recentFrom,
      'primary_release_date.lte': today,
    }),
    tmdb.discover('tv', {
      page: 1,
      sort_by: 'vote_average.desc',
      'vote_average.gte': 7,
      'vote_count.gte': 250,
      'first_air_date.gte': recentFrom,
      'first_air_date.lte': today,
    }),
  ]);
  const genreRailPages = await discoverHomeGenreRails(tmdb, today);

  const movieGenreMap = genreMap(movieGenres.genres);
  const tvGenreMap = genreMap(tvGenres.genres);
  const released = (item: CatalogMediaSummary): boolean =>
    Boolean(item.posterPath && item.releaseDate && item.releaseDate <= today && item.title !== 'Untitled');
  const unique = (items: CatalogMediaSummary[], limit = 20): CatalogMediaSummary[] => items
    .filter(released)
    .filter((item, index, values) =>
      values.findIndex(other => other.mediaType === item.mediaType && other.tmdbId === item.tmdbId) === index,
    )
    .slice(0, limit);
  const movieSummaries = (items: TmdbListItem[]): CatalogMediaSummary[] =>
    items.map(item => summary(item, 'movie', movieGenreMap));
  const tvSummaries = (items: TmdbListItem[]): CatalogMediaSummary[] =>
    items.map(item => summary(item, 'tv', tvGenreMap));

  const trending = unique([...trending1.results, ...trending2.results].flatMap(item => {
    if (item.media_type === 'movie') return [summary(item, 'movie', movieGenreMap)];
    if (item.media_type === 'tv') return [summary(item, 'tv', tvGenreMap)];
    return [];
  }));
  const primaryRails: CatalogHomeRail[] = [
    { title: 'Trending this week', items: trending },
    { title: 'Now playing', items: unique(movieSummaries(nowPlaying.results)) },
    { title: 'Airing now', items: unique(tvSummaries(onTheAir.results)) },
    {
      title: 'Popular movies',
      items: unique(movieSummaries([...popularMovies1.results, ...popularMovies2.results])),
    },
    {
      title: 'Popular series',
      items: unique(tvSummaries([...popularTv1.results, ...popularTv2.results])),
    },
  ].filter(rail => rail.items.length > 0);
  const genreRails: CatalogHomeRail[] = genreRailPages.map(({ spec, page }) => ({
    title: spec.title,
    items: unique(
      spec.mediaType === 'movie'
        ? movieSummaries(page.results)
        : tvSummaries(page.results),
    ),
  }));
  const seenHomeTitles = new Set<string>();
  const rails = [...primaryRails, ...genreRails]
    .map(rail => ({
      ...rail,
      items: rail.items.filter(item => {
        const key = `${item.mediaType}:${item.tmdbId}`;
        if (seenHomeTitles.has(key)) return false;
        seenHomeTitles.add(key);
        return true;
      }),
    }))
    .filter(rail => rail.items.length > 0);

  const qualityScore = (item: CatalogMediaSummary): number => {
    const year = Number(item.releaseDate?.slice(0, 4)) || currentYear - 4;
    const recency = Math.max(0, year - (currentYear - 4));
    return (item.tmdbRating || 0) * 10 + recency * 1.4 + Math.log10(Math.max(1, item.tmdbVoteCount || 0));
  };
  const editorial = unique([
    ...movieSummaries(ratedMovies.results),
    ...tvSummaries(ratedTv.results),
  ], 40).sort((left, right) => qualityScore(right) - qualityScore(left));
  const hero = trending.find(item => item.backdropPath)
    || rails.flatMap(rail => rail.items).find(item => item.backdropPath)
    || rails.flatMap(rail => rail.items)[0];
  if (!hero) throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB returned no usable Home titles', 503, true);

  return { hero, rails, editorialPicks: editorial };
}
