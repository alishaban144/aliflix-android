import { embedForSearch, interpretQuery } from './gemini';
import { buildKeywordExpressions, cosineSimilarity, mergeFilters, normalize, rankCandidates } from './ranking';
import { ParsedRecommendationRequest } from './schemas';
import { TmdbClient, TmdbDetails, TmdbPage } from './tmdb';
import { Candidate, InterpretedIntent, MediaType, RecommendationEnv, RecommendationFilters, RecommendationResult, ServiceError, TmdbGenre, TmdbKeyword, TmdbListItem } from './types';

export interface EngineDependencies {
  interpret?: typeof interpretQuery;
  embed?: typeof embedForSearch;
  tmdb?: Pick<TmdbClient, 'callsRemaining' | 'genres' | 'searchKeyword' | 'discover' | 'recommendations' | 'similar' | 'details'>;
}

const MAX_CANDIDATES = 360;
const MAX_SEMANTIC_CANDIDATES = 160;
const TMDB_DETAIL_RESERVE = 36;
const DISCOVERY_CONCURRENCY = 4;
const DETAIL_CONCURRENCY = 4;
const MAX_KEYWORD_SEARCHES = 18;

async function optionalTmdbCall<T>(operation: string, call: () => Promise<T>): Promise<T | undefined> {
  try {
    return await call();
  } catch (error) {
    if (error instanceof ServiceError && error.code === 'TMDB_UNAVAILABLE' && error.retryable) {
      console.warn(JSON.stringify({ event: 'tmdb_optional_call_skipped', operation }));
      return undefined;
    }
    throw error;
  }
}

function toCandidate(item: TmdbListItem, mediaType: MediaType, genreNames: Map<number, string>): Candidate | undefined {
  const title = (mediaType === 'movie' ? item.title : item.name)?.trim();
  if (!item.id || !title) return undefined;
  return {
    key: `${mediaType}:${item.id}`, tmdbId: item.id, mediaType, title,
    originalTitle: mediaType === 'movie' ? item.original_title : item.original_name,
    overview: item.overview || undefined, posterPath: item.poster_path || undefined, backdropPath: item.backdrop_path || undefined,
    releaseDate: (mediaType === 'movie' ? item.release_date : item.first_air_date) || undefined,
    originalLanguage: item.original_language, originCountries: item.origin_country || [], genreIds: item.genre_ids || [],
    genres: (item.genre_ids || []).map(id => genreNames.get(id)).filter((name): name is string => Boolean(name)),
    tmdbRating: item.vote_average, tmdbVoteCount: item.vote_count, popularity: item.popularity, keywords: [],
    matchedKeywordIds: new Set(), matchedConceptGroupIndexes: new Set(),
    retrievalSources: new Set(), hardFiltersVerified: false,
    directRelationshipScore: 0, anchorOverlapScore: 0, matchReasons: [],
  };
}

function mergeDetails(candidate: Candidate, details: TmdbDetails): void {
  candidate.title = (candidate.mediaType === 'movie' ? details.title : details.name) || candidate.title;
  candidate.originalTitle = (candidate.mediaType === 'movie' ? details.original_title : details.original_name) || candidate.originalTitle;
  candidate.overview = details.overview || candidate.overview;
  candidate.posterPath = details.poster_path || candidate.posterPath;
  candidate.backdropPath = details.backdrop_path || candidate.backdropPath;
  candidate.releaseDate = (candidate.mediaType === 'movie' ? details.release_date : details.first_air_date) || candidate.releaseDate;
  candidate.originalLanguage = details.original_language || candidate.originalLanguage;
  candidate.originCountries = details.origin_country || candidate.originCountries;
  if (details.genres?.length) { candidate.genreIds = details.genres.map(g => g.id); candidate.genres = details.genres.map(g => g.name); }
  candidate.runtimeMinutes = details.runtime ?? details.episode_run_time?.find(value => value > 0) ?? candidate.runtimeMinutes;
  candidate.tmdbRating = details.vote_average ?? candidate.tmdbRating;
  candidate.tmdbVoteCount = details.vote_count ?? candidate.tmdbVoteCount;
  candidate.keywords = details.keywords?.keywords || details.keywords?.results || candidate.keywords;
}

function genreLookup(genres: TmdbGenre[]): Map<string, number> {
  return new Map(genres.flatMap(genre => [[normalize(genre.name), genre.id], [normalize(genre.name.replace(/&/g, 'and')), genre.id]]));
}

function discoverParams(type: MediaType, filters: RecommendationFilters, genreIds: Map<string, number>): Record<string, string | number | boolean | undefined> {
  const included = filters.includedGenres.map(name => genreIds.get(normalize(name))).filter((id): id is number => id !== undefined);
  const excluded = filters.excludedGenres.map(name => genreIds.get(normalize(name))).filter((id): id is number => id !== undefined);
  const params: Record<string, string | number | boolean | undefined> = {
    sort_by: 'vote_count.desc', with_original_language: filters.originalLanguage,
    with_origin_country: filters.originCountries.length ? filters.originCountries.join('|') : undefined,
    with_genres: included.length ? included.join(',') : undefined, without_genres: excluded.length ? excluded.join(',') : undefined,
    'with_runtime.gte': filters.minimumRuntimeMinutes, 'with_runtime.lte': filters.maximumRuntimeMinutes,
    'vote_average.gte': filters.minimumTmdbRating, 'vote_count.gte': filters.minimumTmdbRating !== undefined ? 10 : undefined,
  };
  if (filters.minimumYear) params[type === 'movie' ? 'primary_release_date.gte' : 'first_air_date.gte'] = `${filters.minimumYear}-01-01`;
  if (filters.maximumYear) params[type === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte'] = `${filters.maximumYear}-12-31`;
  return params;
}

function passesKnownFilters(candidate: Candidate, filters: RecommendationFilters): boolean {
  if (filters.excludedTmdbIds.includes(candidate.tmdbId)) return false;
  if (filters.excludedTitles.some(title => normalize(title) === normalize(candidate.title) || normalize(title) === normalize(candidate.originalTitle || ''))) return false;
  const year = candidate.releaseDate ? Number(candidate.releaseDate.slice(0, 4)) : undefined;
  if (Number.isInteger(year)) {
    if (filters.minimumYear !== undefined && year! < filters.minimumYear) return false;
    if (filters.maximumYear !== undefined && year! > filters.maximumYear) return false;
  }
  if (candidate.originalLanguage && filters.originalLanguage && candidate.originalLanguage !== filters.originalLanguage) return false;
  if (candidate.originCountries.length && filters.originCountries.length && !filters.originCountries.some(country => candidate.originCountries.includes(country))) return false;
  if (candidate.runtimeMinutes !== undefined) {
    if (filters.minimumRuntimeMinutes !== undefined && candidate.runtimeMinutes < filters.minimumRuntimeMinutes) return false;
    if (filters.maximumRuntimeMinutes !== undefined && candidate.runtimeMinutes > filters.maximumRuntimeMinutes) return false;
  }
  if (candidate.genres.length) {
    const genres = new Set(candidate.genres.map(normalize));
    if (filters.includedGenres.some(genre => !genres.has(normalize(genre)))) return false;
    if (filters.excludedGenres.some(genre => genres.has(normalize(genre)))) return false;
  }
  if (filters.minimumTmdbRating !== undefined && candidate.tmdbRating !== undefined && candidate.tmdbRating < filters.minimumTmdbRating) return false;
  return true;
}

export async function processRecommendation(env: RecommendationEnv, request: ParsedRecommendationRequest, dependencies: EngineDependencies = {}): Promise<RecommendationResult[]> {
  const tmdb = dependencies.tmdb || new TmdbClient(env);
  const interpret = dependencies.interpret || interpretQuery;
  const intent = await interpret(env, request.query, request.mediaType);
  const filters = mergeFilters(request.filters, intent.hardFilters);
  const effectiveIntent: InterpretedIntent = { ...intent, hardFilters: filters };

  const genreResponse = await tmdb.genres(request.mediaType);
  const genresById = new Map(genreResponse.genres.map(genre => [genre.id, genre.name]));
  const genresByName = genreLookup(genreResponse.genres);
  for (const hint of intent.genreHints) {
    const id = genresByName.get(normalize(hint));
    if (id !== undefined && !filters.includedGenres.some(g => normalize(g) === normalize(hint))) {
      // Genre hints guide retrieval but remain soft: they are not merged into hard filters.
    }
  }
  const candidates = new Map<string, Candidate>();
  const addPage = (
    page: TmdbPage,
    source: string,
    matchedIds: number[] = [],
    matchedConceptGroups: number[] = [],
    direct = 0,
  ) => {
    for (const [position, item] of (page.results || []).entries()) {
      const fresh = toCandidate(item, request.mediaType, genresById);
      if (!fresh) continue;
      const candidate = candidates.get(fresh.key) || fresh;
      candidate.retrievalSources.add(source);
      matchedIds.forEach(id => candidate.matchedKeywordIds.add(id));
      matchedConceptGroups.forEach(index => candidate.matchedConceptGroupIndexes.add(index));
      const orderedRelationship = direct > 0 ? Math.max(0, direct - position * .01) : 0;
      candidate.directRelationshipScore = Math.max(candidate.directRelationshipScore, orderedRelationship);
      candidate.hardFiltersVerified ||= source.startsWith('discover:');
      candidates.set(candidate.key, candidate);
    }
  };

  const baseParams = discoverParams(request.mediaType, filters, genresByName);
  const runDiscover = async (
    source: string,
    extra: Record<string, string | number | boolean | undefined>,
    pages: number,
    matched: number[] = [],
    matchedConceptGroups: number[] = [],
  ) => {
    if (tmdb.callsRemaining <= TMDB_DETAIL_RESERVE || candidates.size >= MAX_CANDIDATES) return;
    const discoverPage = async (page: number): Promise<TmdbPage | undefined> => {
      // A broad sweep can span well over one hundred TMDB pages. Do not discard
      // every useful candidate because one page exhausted its transient retries.
      return optionalTmdbCall(
        `${source}:page-${page}`,
        () => tmdb.discover(request.mediaType, { ...baseParams, ...extra, page }),
      );
    };
    const first = await discoverPage(1);
    if (!first) return;
    addPage(first, `${source}:page-1`, matched, matchedConceptGroups);
    const lastPage = Math.min(pages, first.total_pages || 1);
    if (!first.results?.length || lastPage <= 1) return;

    for (let page = 2; page <= lastPage && candidates.size < MAX_CANDIDATES;) {
      const usableCalls = Math.max(0, tmdb.callsRemaining - TMDB_DETAIL_RESERVE);
      const batchSize = Math.min(DISCOVERY_CONCURRENCY, usableCalls, lastPage - page + 1);
      if (batchSize <= 0) break;
      const pageNumbers = Array.from({ length: batchSize }, (_, index) => page + index);
      const results = await Promise.all(pageNumbers.map(discoverPage));
      results.forEach((result, index) => {
        if (!result) return;
        addPage(
          result,
          `${source}:page-${pageNumbers[index]}`,
          matched,
          matchedConceptGroups,
        );
      });
      if (results.every(result => !result?.results?.length)) break;
      page += batchSize;
    }
  };

  let anchorDetails: TmdbDetails | undefined;
  let anchorId: number | undefined;
  if (request.mode === 'similar') {
    const anchor = request.anchor!;
    anchorId = anchor.tmdbId;
    anchorDetails = await tmdb.details(anchor.mediaType, anchorId);
    if (anchor.mediaType === request.mediaType) {
      for (let page = 1; page <= 3 && tmdb.callsRemaining > 12; page++) {
        const recommendations = await optionalTmdbCall(
          `recommendations:page-${page}`,
          () => tmdb.recommendations(anchor.mediaType, anchorId!, page),
        );
        if (recommendations) addPage(recommendations, `recommendations:page-${page}`, [], [], 1);
        const similar = await optionalTmdbCall(
          `similar:page-${page}`,
          () => tmdb.similar(anchor.mediaType, anchorId!, page),
        );
        if (similar) addPage(similar, `similar:page-${page}`, [], [], .85);
      }
    }
    const anchorKeywords = anchorDetails.keywords?.keywords || anchorDetails.keywords?.results || [];
    const keywordIds = anchorKeywords.slice(0, 8).map(keyword => keyword.id);
    const genreIds = (anchorDetails.genres || []).map(genre => genre.id);
    if (keywordIds.length) await runDiscover('discover:anchor-keywords', { with_keywords: keywordIds.join('|') }, 5, keywordIds);
    if (genreIds.length) await runDiscover('discover:anchor-genres', { with_genres: genreIds.join('|') }, 4);
    for (const candidate of candidates.values()) {
      const candidateGenres = new Set(candidate.genreIds);
      const genreOverlap = genreIds.length ? genreIds.filter(id => candidateGenres.has(id)).length / genreIds.length : 0;
      candidate.anchorOverlapScore = Math.min(1, genreOverlap * .65 + (candidate.matchedKeywordIds.size ? .35 : 0));
    }
    if (anchor.mediaType === request.mediaType) candidates.delete(`${request.mediaType}:${anchorId}`);
  } else {
    const groupKeywordIds: number[][] = [];
    let keywordSearches = 0;
    for (const group of intent.requiredConceptGroups) {
      const ids: number[] = [];
      for (const phrase of group.synonyms) {
        if (keywordSearches++ >= MAX_KEYWORD_SEARCHES || tmdb.callsRemaining <= 18) break;
        const response = await optionalTmdbCall(
          `keyword:${phrase}`,
          () => tmdb.searchKeyword(phrase),
        );
        if (!response) continue;
        const exact = response.results.filter(keyword => normalize(keyword.name) === normalize(phrase));
        for (const keyword of [...exact, ...response.results].slice(0, 2)) if (!ids.includes(keyword.id)) ids.push(keyword.id);
      }
      groupKeywordIds.push(ids.slice(0, 4));
    }
    const expressions = buildKeywordExpressions(groupKeywordIds, 8);
    for (const expression of expressions.slice(0, 4)) {
      const matched = expression.split(/[|,]/).map(Number).filter(Boolean);
      const matchedGroups = groupKeywordIds.flatMap((ids, index) =>
        ids.some(id => matched.includes(id)) ? [index] : [],
      );
      await runDiscover(
        'discover:concept-intersection',
        { with_keywords: expression },
        expressions.length === 1 ? 24 : 10,
        matched,
        matchedGroups,
      );
    }
    for (const [groupIndex, ids] of groupKeywordIds.entries()) {
      if (groupIndex >= 4 || !ids.length || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE) break;
      await runDiscover(
        `discover:concept-${groupIndex + 1}`,
        { with_keywords: ids.join('|') },
        6,
        ids,
        [groupIndex],
      );
    }
    for (const phrase of intent.broadSearchPhrases.slice(0, 3)) {
      if (keywordSearches++ >= MAX_KEYWORD_SEARCHES || tmdb.callsRemaining <= 14) break;
      const response = await optionalTmdbCall(
        `broad-keyword:${phrase}`,
        () => tmdb.searchKeyword(phrase),
      );
      if (!response) continue;
      const exact = response.results.filter(keyword => normalize(keyword.name) === normalize(phrase));
      const ids = [...exact, ...response.results]
        .map(keyword => keyword.id)
        .filter((id, index, values) => values.indexOf(id) === index)
        .slice(0, 3);
      if (ids.length) {
        await runDiscover('discover:broad-phrase', { with_keywords: ids.join('|') }, 6, ids);
      }
    }
    const hintedGenreIds = intent.genreHints.map(name => genresByName.get(normalize(name))).filter((id): id is number => id !== undefined);
    if (hintedGenreIds.length && tmdb.callsRemaining > TMDB_DETAIL_RESERVE) {
      await runDiscover('discover:genre-hints', { with_genres: hintedGenreIds.join('|') }, 30);
      await runDiscover(
        'discover:genre-hints-popular',
        { with_genres: hintedGenreIds.join('|'), sort_by: 'popularity.desc' },
        18,
      );
    }
    if (tmdb.callsRemaining > TMDB_DETAIL_RESERVE && candidates.size < MAX_CANDIDATES) {
      await runDiscover('discover:hard-filters', {}, 60);
      await runDiscover('discover:hard-filters-popular', { sort_by: 'popularity.desc' }, 40);
      await runDiscover(
        'discover:hard-filters-recent',
        { sort_by: request.mediaType === 'movie' ? 'primary_release_date.desc' : 'first_air_date.desc' },
        30,
      );
      await runDiscover(
        'discover:hard-filters-rated',
        { sort_by: 'vote_average.desc', 'vote_count.gte': Math.max(25, Number(baseParams['vote_count.gte']) || 0) },
        20,
      );
    }
  }

  // Details are authoritative enrichment. Keep it bounded so discovery can use most of the request budget.
  const preliminary = [...candidates.values()]
    .filter(candidate => passesKnownFilters(candidate, filters))
    .sort((a, b) => b.directRelationshipScore - a.directRelationshipScore ||
      b.retrievalSources.size - a.retrievalSources.size ||
      (b.tmdbVoteCount || 0) - (a.tmdbVoteCount || 0));
  const detailsCount = Math.min(preliminary.length, Math.max(0, Math.min(48, request.pageSize + 12, tmdb.callsRemaining)));
  for (let start = 0; start < detailsCount; start += DETAIL_CONCURRENCY) {
    await Promise.all(preliminary.slice(start, start + DETAIL_CONCURRENCY).map(async candidate => {
      try {
        const details = await optionalTmdbCall(
          `details:${request.mediaType}:${candidate.tmdbId}`,
          () => tmdb.details(request.mediaType, candidate.tmdbId),
        );
        if (details) mergeDetails(candidate, details);
      } catch (error) {
        if (!(error instanceof ServiceError && error.code === 'TMDB_NOT_FOUND')) throw error;
      }
    }));
  }

  let embeddingsAvailable = false;
  if (preliminary.length) {
    const queryText = [
      request.query,
      ...intent.requiredConceptGroups.map(group => `${group.label}: ${group.synonyms.join(', ')}`),
      ...intent.softConcepts,
      ...intent.toneAndMood,
      ...intent.broadSearchPhrases,
    ].filter(Boolean).join('\n');
    const semanticCandidates = preliminary.slice(0, MAX_SEMANTIC_CANDIDATES);
    const docs = semanticCandidates.map(candidate => [candidate.title, candidate.originalTitle, candidate.overview, candidate.genres.join(', '), candidate.keywords.map(k => k.name).join(', ')].filter(Boolean).join('\n'));
    try {
      const vectors = await (dependencies.embed || embedForSearch)(env, queryText || request.anchor?.title || '', docs);
      semanticCandidates.forEach((candidate, index) => { candidate.semanticScore = cosineSimilarity(vectors.query, vectors.documents[index]); });
      embeddingsAvailable = true;
    } catch (error) {
      console.warn('Gemini embeddings unavailable; using deterministic relevance ranking', error instanceof Error ? error.message : error);
    }
  }

  return rankCandidates(
    preliminary,
    effectiveIntent,
    filters,
    request.mode === 'similar',
    embeddingsAvailable,
  ).slice(0, MAX_CANDIDATES);
}
