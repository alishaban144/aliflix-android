import { TmdbClient, TmdbDetails, TmdbPage } from './tmdb';
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

export interface CatalogTitleDetails extends CatalogMediaSummary {
  imdbId?: string;
  status?: string;
  creators: CatalogPerson[];
  cast: CatalogPerson[];
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
  };
}

export async function titleDetails(env: RecommendationEnv, mediaType: MediaType, id: number): Promise<CatalogTitleDetails> {
  return detailsSummary(await new TmdbClient(env, 4).details(mediaType, id), mediaType);
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
