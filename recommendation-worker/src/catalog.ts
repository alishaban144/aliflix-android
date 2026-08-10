import { TmdbClient, TmdbDetails } from './tmdb';
import { MediaType, RecommendationEnv, TmdbGenre, TmdbListItem } from './types';

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
  status?: string;
  creators: CatalogPerson[];
  cast: CatalogPerson[];
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

export async function personCredits(env: RecommendationEnv, id: number, name: string): Promise<{ person: CatalogPerson; results: CatalogMediaSummary[] }> {
  const tmdb = new TmdbClient(env, 6);
  const [credits, movieGenres, tvGenres] = await Promise.all([
    tmdb.personCombinedCredits(id),
    tmdb.genres('movie'),
    tmdb.genres('tv'),
  ]);
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
  return { person: { tmdbId: id, name }, results: values };
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
