import { embedForSearch, fallbackIntentFromQuery, interpretQuery } from './gemini';
import { buildKeywordExpressions, cosineSimilarity, mergeFilters, normalize, rankCandidates } from './ranking';
import { ParsedRecommendationRequest } from './schemas';
import { TmdbClient, TmdbDetails, TmdbPage } from './tmdb';
import { Candidate, InterpretedIntent, MediaType, RecommendationEnv, RecommendationFilters, RecommendationResult, ServiceError, TmdbGenre, TmdbKeyword, TmdbListItem } from './types';

export interface EngineDependencies {
  interpret?: typeof interpretQuery;
  embed?: typeof embedForSearch;
  tmdb?: Pick<TmdbClient, 'callsRemaining' | 'genres' | 'searchKeyword' | 'searchPerson' | 'searchCompany' | 'discover' | 'recommendations' | 'similar' | 'details'>;
}

const MAX_CANDIDATES = 220;
const MAX_SEMANTIC_CANDIDATES = 64;
const MAX_DETAIL_CANDIDATES = 64;
const TMDB_DETAIL_RESERVE = 28;
const DISCOVERY_CONCURRENCY = 4;
const DETAIL_CONCURRENCY = 4;
const MAX_KEYWORD_SEARCHES = 18;
const MAX_KEYWORD_SEARCHES_PER_GROUP = 2;

interface AnchorProfile {
  keywords: TmdbKeyword[];
  genreNames: string[];
  overview?: string;
}

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
    tmdbRating: item.vote_average, tmdbVoteCount: item.vote_count, popularity: item.popularity, certifications: [], keywords: [],
    matchedKeywordIds: new Set(), matchedConceptGroupIndexes: new Set(),
    retrievalSources: new Set(), hardFiltersVerified: false, detailsLoaded: false,
    directRelationshipScore: 0, anchorOverlapScore: 0, anchorEvidenceAvailable: false,
    sharedAnchorKeywordCount: 0, anchorGenreScore: 0, matchReasons: [],
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
  candidate.collectionId = details.belongs_to_collection?.id;
  candidate.keywords = details.keywords?.keywords || details.keywords?.results || candidate.keywords;
  candidate.certifications = [...new Set([
    ...(details.release_dates?.results || []).filter(country => country.iso_3166_1 === 'US').flatMap(country => country.release_dates || []).map(release => release.certification?.trim()),
    ...(details.content_ratings?.results || []).filter(country => country.iso_3166_1 === 'US').map(rating => rating.rating?.trim()),
  ].filter((value): value is string => Boolean(value)))];
  candidate.hardFiltersVerified = true;
  candidate.detailsLoaded = true;
}

function sourceFamilyCount(candidate: Candidate): number {
  return new Set([...candidate.retrievalSources].map(source => source.replace(/:page-\d+$/, ''))).size;
}

function detailPriority(candidate: Candidate): number {
  const sourceFamilies = new Set([...candidate.retrievalSources].map(source => source.replace(/:page-\d+$/, '')));
  const targetedBonus = [...sourceFamilies].reduce((score, source) => score + (
    source.includes('concept-intersection') ? 45 :
      source.includes('anchor-fusion') ? 40 :
        source.includes('anchor-keywords') ? 32 :
          source.includes('concept-') ? 24 :
            source.includes('people') || source.includes('studios') ? 18 :
              source.includes('genre') ? 4 : -8
  ), 0);
  return candidate.directRelationshipScore * 200 + candidate.matchedConceptGroupIndexes.size * 70 +
    candidate.matchedKeywordIds.size * 12 + sourceFamilyCount(candidate) * 8 + targetedBonus +
    Math.log10((candidate.tmdbVoteCount || 0) + 1);
}

function buildAnchorKeywordExpressions(keywordIds: number[], limit = 6): string[] {
  const ids = [...new Set(keywordIds)].slice(0, 8);
  if (!ids.length) return [];
  if (ids.length === 1) return [String(ids[0])];
  const expressions: string[] = [];
  if (ids.length >= 3) {
    for (let first = 0; first < ids.length && expressions.length < limit; first++) {
      for (let second = first + 1; second < ids.length && expressions.length < limit; second++) {
        for (let third = second + 1; third < ids.length && expressions.length < Math.min(3, limit); third++) {
          expressions.push(`${ids[first]},${ids[second]},${ids[third]}`);
        }
        if (expressions.length >= Math.min(3, limit)) break;
      }
    }
  }
  for (let first = 0; first < ids.length && expressions.length < limit; first++) {
    for (let second = first + 1; second < ids.length && expressions.length < limit; second++) {
      expressions.push(`${ids[first]},${ids[second]}`);
    }
  }
  return [...new Set(expressions)].slice(0, limit);
}

function exactKeywordIds(expression: string): number[] {
  return expression.split(',').filter(segment => !segment.includes('|')).map(Number).filter(Number.isFinite);
}

function canonicalKeywordPhrase(value: string): string {
  const singularize = (word: string): string => {
    if (word.length > 4 && word.endsWith('ies')) return `${word.slice(0, -3)}y`;
    if (word.length > 5 && /(ches|shes|xes|zes)$/.test(word)) return word.slice(0, -2);
    if (word.length > 4 && word.endsWith('s') && !word.endsWith('ss')) return word.slice(0, -1);
    return word;
  };
  return normalize(value).split(' ').map(singularize).join(' ');
}

function keywordMatchesSearchTerm(keywordName: string, searchTerm: string): boolean {
  // TMDB commonly stores a singular tag while natural-language queries use a
  // plural ("teenagers" -> "teenager"). Treat only that grammatical variant
  // as exact grounding; never accept a merely nearby/fuzzy search result.
  return canonicalKeywordPhrase(keywordName) === canonicalKeywordPhrase(searchTerm);
}

function conceptKeywordQueries(groupKeywordIds: number[][], limit = 8): Array<{ expression: string; groupIndexes: number[] }> {
  const activeGroups = groupKeywordIds
    .map((ids, index) => ({ ids: [...new Set(ids)].slice(0, 4), index }))
    .filter(group => group.ids.length);
  if (!activeGroups.length) return [];
  const minimumGroups = activeGroups.length <= 2 ? activeGroups.length : Math.ceil(activeGroups.length * .66);
  const queries: Array<{ expression: string; groupIndexes: number[] }> = [];
  const addCombinations = (size: number, start = 0, selected: typeof activeGroups = []): void => {
    if (queries.length >= limit) return;
    if (selected.length === size) {
      queries.push({
        expression: selected.map(group => group.ids.join('|')).join(','),
        groupIndexes: selected.map(group => group.index),
      });
      return;
    }
    for (let index = start; index <= activeGroups.length - (size - selected.length) && queries.length < limit; index++) {
      addCombinations(size, index + 1, [...selected, activeGroups[index]]);
    }
  };
  for (let size = activeGroups.length; size >= minimumGroups && queries.length < limit; size--) addCombinations(size);
  return queries;
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
  const releaseDateMaximumKey = type === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte';
  const today = new Date().toISOString().slice(0, 10);
  params[releaseDateMaximumKey] = filters.maximumYear
    ? [`${filters.maximumYear}-12-31`, today].sort()[0]
    : today;
  return params;
}

function passesKnownFilters(candidate: Candidate, filters: RecommendationFilters): boolean {
  if (filters.excludedTmdbIds.includes(candidate.tmdbId)) return false;
  if (filters.excludedTitles.some(title => normalize(title) === normalize(candidate.title) || normalize(title) === normalize(candidate.originalTitle || ''))) return false;
  const year = candidate.releaseDate ? Number(candidate.releaseDate.slice(0, 4)) : undefined;
  if (candidate.releaseDate && candidate.releaseDate > new Date().toISOString().slice(0, 10)) return false;
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

function applyQueryDateConstraints(query: string, filters: RecommendationFilters): void {
  const q = query.toLowerCase();
  const afterMatch = /\b(?:after|post-?)\s*(19\d\d|20\d\d)\b/.exec(q);
  if (afterMatch && filters.minimumYear === undefined) {
    filters.minimumYear = Number(afterMatch[1]) + 1;
  }
  const fromMatch = /\b(?:since|from)\s*(19\d\d|20\d\d)\b/.exec(q);
  if (fromMatch && filters.minimumYear === undefined) {
    filters.minimumYear = Number(fromMatch[1]);
  }
  const beforeMatch = /\b(?:before|prior to|up to)\s*(19\d\d|20\d\d)\b/.exec(q);
  if (beforeMatch && filters.maximumYear === undefined) {
    filters.maximumYear = Number(beforeMatch[1]) - 1;
  }
  if (/\b(?:90s|1990s)\b/.test(q)) {
    if (filters.minimumYear === undefined) filters.minimumYear = 1990;
    if (filters.maximumYear === undefined) filters.maximumYear = 1999;
  }
  if (/\b(?:80s|1980s)\b/.test(q)) {
    if (filters.minimumYear === undefined) filters.minimumYear = 1980;
    if (filters.maximumYear === undefined) filters.maximumYear = 1989;
  }
  if (/\b(?:2000s)\b/.test(q)) {
    if (filters.minimumYear === undefined) filters.minimumYear = 2000;
    if (filters.maximumYear === undefined) filters.maximumYear = 2009;
  }
}

export async function processRecommendation(env: RecommendationEnv, request: ParsedRecommendationRequest, dependencies: EngineDependencies = {}): Promise<RecommendationResult[]> {
  const tmdb = dependencies.tmdb || new TmdbClient(env);
  const interpret = dependencies.interpret || interpretQuery;
  const queryToInterpret = request.mode === 'similar'
    ? (request.refinementQuery || '')
    : (request.previousQuery && request.refinementQuery)
    ? `${request.previousQuery} [Refinement adjustment: ${request.refinementQuery}]`
    : (request.query || request.refinementQuery || '');
  let intent: InterpretedIntent;
  try {
    intent = await interpret(env, queryToInterpret, request.mediaType);
  } catch (error) {
    console.warn('Gemini interpretation error, applying fallback keyword intent', error instanceof Error ? error.message : error);
    intent = fallbackIntentFromQuery(queryToInterpret);
  }
  applyQueryDateConstraints(queryToInterpret, intent.hardFilters);
  const filters = mergeFilters(request.filters, intent.hardFilters);
  const effectiveIntent: InterpretedIntent = { ...intent, hardFilters: filters };

  const genreResponse = await tmdb.genres(request.mediaType);
  const genresById = new Map(genreResponse.genres.map(genre => [genre.id, genre.name]));
  const genresByName = genreLookup(genreResponse.genres);
  const candidates = new Map<string, Candidate>();
  const anchorProfiles: AnchorProfile[] = [];
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

  const anchors = request.anchors?.length ? request.anchors : (request.anchor ? [request.anchor] : []);
  if (request.mode === 'similar' && anchors.length) {
    const allAnchorKeywordIds: number[][] = [];
    const targetAnchorGenreIds = new Set<number>();
    for (const anchor of anchors) {
      const anchorId = anchor.tmdbId;
      const anchorDetails = await optionalTmdbCall(
        `details:${anchor.mediaType}:${anchorId}`,
        () => tmdb.details(anchor.mediaType, anchorId),
      );
      if (anchor.mediaType === request.mediaType) {
        const pagesToFetch = anchors.length > 1 ? 1 : 2;
        for (let page = 1; page <= pagesToFetch && tmdb.callsRemaining > 12; page++) {
          const recommendations = await optionalTmdbCall(
            `recommendations:${anchorId}:page-${page}`,
            () => tmdb.recommendations(anchor.mediaType, anchorId, page),
          );
          if (recommendations) addPage(recommendations, `recommendations:${anchorId}:page-${page}`, [], [], Math.max(.72, 1 - (page - 1) * .12));
          const similar = await optionalTmdbCall(
            `similar:${anchorId}:page-${page}`,
            () => tmdb.similar(anchor.mediaType, anchorId, page),
          );
          if (similar) addPage(similar, `similar:${anchorId}:page-${page}`, [], [], Math.max(.62, .86 - (page - 1) * .12));
        }
      }
      if (anchorDetails) {
        const anchorKeywords = anchorDetails.keywords?.keywords || anchorDetails.keywords?.results || [];
        const kwIds = anchorKeywords.slice(0, 8).map(keyword => keyword.id);
        if (kwIds.length) allAnchorKeywordIds.push(kwIds);
        const genreNames = (anchorDetails.genres || []).map(genre => genre.name);
        genreNames.forEach(name => {
          const targetId = genresByName.get(normalize(name));
          if (targetId !== undefined) targetAnchorGenreIds.add(targetId);
        });
        anchorProfiles.push({ keywords: anchorKeywords.slice(0, 12), genreNames, overview: anchorDetails.overview });
      }
      if (anchor.mediaType === request.mediaType) candidates.delete(`${request.mediaType}:${anchorId}`);
    }

    const anchorExpressions: Array<{ expression: string; source: string }> = [];
    if (allAnchorKeywordIds.length > 1) {
      buildKeywordExpressions(allAnchorKeywordIds, 5).forEach(expression => {
        anchorExpressions.push({ expression, source: 'discover:anchor-fusion' });
      });
    }
    for (const keywordIds of allAnchorKeywordIds) {
      for (const expression of buildAnchorKeywordExpressions(keywordIds, 5)) {
        anchorExpressions.push({ expression, source: 'discover:anchor-keywords' });
      }
    }
    const seenAnchorExpressions = new Set<string>();
    for (const { expression, source } of anchorExpressions) {
      if (seenAnchorExpressions.size >= 8 || seenAnchorExpressions.has(expression)) continue;
      seenAnchorExpressions.add(expression);
      await runDiscover(source, {
        with_keywords: expression,
        with_genres: targetAnchorGenreIds.size ? [...targetAnchorGenreIds].join('|') : undefined,
      }, 3, exactKeywordIds(expression));
    }

    // A genre-only pool is allowed solely as a tiny semantic-recall pool when
    // exact anchor keywords yielded nothing. It can never pass ranking on genre alone.
    if (!candidates.size && targetAnchorGenreIds.size && anchorProfiles.some(profile => profile.overview)) {
      await runDiscover('discover:anchor-semantic-recall', { with_genres: [...targetAnchorGenreIds].join('|') }, 2);
    }

    for (const anchor of anchors) {
      if (anchor.mediaType === request.mediaType) candidates.delete(`${request.mediaType}:${anchor.tmdbId}`);
    }
  } else {
    const groupKeywordIds: number[][] = [];
    let keywordSearches = 0;
    for (const group of intent.requiredConceptGroups) {
      const ids: number[] = [];
      let groupSearches = 0;
      for (const phrase of group.synonyms) {
        if (keywordSearches >= MAX_KEYWORD_SEARCHES || groupSearches >= MAX_KEYWORD_SEARCHES_PER_GROUP || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE + 8) break;
        const searchTerms = [phrase, phrase.replace(/-/g, ' '), phrase.replace(/\s+/g, '-')].filter((v, i, a) => a.indexOf(v) === i);
        for (const term of searchTerms) {
          if (ids.length >= 4 || keywordSearches >= MAX_KEYWORD_SEARCHES || groupSearches >= MAX_KEYWORD_SEARCHES_PER_GROUP || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE + 8) break;
          keywordSearches++;
          groupSearches++;
          const response = await optionalTmdbCall(
            `keyword:${term}`,
            () => tmdb.searchKeyword(term),
          );
          if (!response?.results?.length) continue;
          const exact = response.results.filter(keyword => keywordMatchesSearchTerm(keyword.name, term));
          for (const keyword of exact.slice(0, 2)) if (!ids.includes(keyword.id)) ids.push(keyword.id);
        }
      }
      groupKeywordIds.push(ids.slice(0, 4));
    }

    const excludedKeywordIds: number[] = [];
    for (const phrase of (intent.excludedKeywords || []).slice(0, 6)) {
      if (keywordSearches >= MAX_KEYWORD_SEARCHES || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE + 8) break;
      keywordSearches++;
      const response = await optionalTmdbCall(
        `excluded-keyword:${phrase}`,
        () => tmdb.searchKeyword(phrase),
      );
      if (response?.results?.length) {
        const exact = response.results.filter(k => keywordMatchesSearchTerm(k.name, phrase));
        for (const kw of exact.slice(0, 2)) {
          if (!excludedKeywordIds.includes(kw.id)) excludedKeywordIds.push(kw.id);
        }
      }
    }
    const negativeKeywordParam = excludedKeywordIds.length ? { without_keywords: excludedKeywordIds.join(',') } : {};

    const groundedQueries = conceptKeywordQueries(groupKeywordIds, 8);
    for (const [queryIndex, query] of groundedQueries.entries()) {
      await runDiscover(
        'discover:concept-intersection',
        { with_keywords: query.expression, ...negativeKeywordParam },
        queryIndex === 0 ? 4 : 2,
        exactKeywordIds(query.expression),
        query.groupIndexes,
      );
    }
    for (const [groupIndex, ids] of groupKeywordIds.entries()) {
      if (groupIndex >= 4 || !ids.length || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE) break;
      await runDiscover(
        `discover:concept-${groupIndex + 1}`,
        { with_keywords: ids.join('|'), ...negativeKeywordParam },
        2,
        [],
        [groupIndex],
      );
    }
    for (const phrase of intent.broadSearchPhrases.slice(0, 3)) {
      if (keywordSearches >= MAX_KEYWORD_SEARCHES || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE + 8) break;
      keywordSearches++;
      const response = await optionalTmdbCall(
        `broad-keyword:${phrase}`,
        () => tmdb.searchKeyword(phrase),
      );
      if (!response) continue;
      const exact = response.results.filter(keyword => keywordMatchesSearchTerm(keyword.name, phrase));
      const ids = exact
        .map(keyword => keyword.id)
        .filter((id, index, values) => values.indexOf(id) === index)
        .slice(0, 3);
      if (ids.length) {
        await runDiscover('discover:broad-phrase', { with_keywords: ids.join('|'), ...negativeKeywordParam }, 2);
      }
    }

    const personIds: number[] = [];
    for (const name of [...(intent.crewNames || []), ...(intent.castNames || [])].slice(0, 4)) {
      const res = await optionalTmdbCall(`person:${name}`, () => tmdb.searchPerson(name));
      const pid = res?.results?.find(person => normalize(person.name) === normalize(name))?.id;
      if (pid && !personIds.includes(pid)) personIds.push(pid);
    }
    if (personIds.length && tmdb.callsRemaining > TMDB_DETAIL_RESERVE) {
      await runDiscover('discover:people', { with_people: personIds.join('|'), ...negativeKeywordParam }, 3);
    }

    const studioIds: number[] = [];
    for (const studio of (intent.studioNames || []).slice(0, 3)) {
      const res = await optionalTmdbCall(`studio:${studio}`, () => tmdb.searchCompany(studio));
      const sid = res?.results?.find(company => normalize(company.name) === normalize(studio))?.id;
      if (sid && !studioIds.includes(sid)) studioIds.push(sid);
    }
    if (studioIds.length && tmdb.callsRemaining > TMDB_DETAIL_RESERVE) {
      await runDiscover('discover:studios', { with_companies: studioIds.join('|'), ...negativeKeywordParam }, 3);
    }

    const hintedGenreIds = intent.genreHints.map(name => genresByName.get(normalize(name))).filter((id): id is number => id !== undefined);
    if (intent.discoveryProfile === 'hidden_gems' && !intent.requiredConceptGroups.length && tmdb.callsRemaining > TMDB_DETAIL_RESERVE) {
      await runDiscover('discover:hidden-gems', {
        with_genres: hintedGenreIds.length ? hintedGenreIds.join('|') : undefined,
        sort_by: 'vote_average.desc',
        'vote_count.gte': 80,
        'vote_count.lte': 3500,
        'vote_average.gte': 7.0,
        ...negativeKeywordParam,
      }, 5);
    }

    if (hintedGenreIds.length && candidates.size < request.pageSize * 2 && tmdb.callsRemaining > TMDB_DETAIL_RESERVE) {
      await runDiscover('discover:genre-hints', { with_genres: hintedGenreIds.join('|'), ...negativeKeywordParam }, 3);
    }
    const targetedEntities = personIds.length > 0 || studioIds.length > 0;
    const needsGeneralDiscovery = request.mode === 'filters' ||
      (!intent.requiredConceptGroups.length && !targetedEntities && intent.discoveryProfile !== 'hidden_gems');
    if (needsGeneralDiscovery && tmdb.callsRemaining > TMDB_DETAIL_RESERVE && candidates.size < MAX_CANDIDATES) {
      const generalSort = intent.discoveryProfile === 'blockbusters' ? 'popularity.desc' : 'vote_count.desc';
      await runDiscover('discover:filtered-catalogue', { sort_by: generalSort, ...negativeKeywordParam }, 6);
    }
  }

  // Details are authoritative enrichment. Evidence-rich retrieval paths get the
  // finite detail budget before broad recall or popularity can influence it.
  const preliminary = [...candidates.values()]
    .filter(candidate => passesKnownFilters(candidate, filters))
    .sort((a, b) => detailPriority(b) - detailPriority(a) ||
      (b.tmdbVoteCount || 0) - (a.tmdbVoteCount || 0));
  const desiredDetails = Math.max(request.pageSize + 16, request.pageSize * 2 + 8);
  const detailsCount = Math.min(preliminary.length, Math.max(0, Math.min(MAX_DETAIL_CANDIDATES, desiredDetails, tmdb.callsRemaining)));
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

  const hydrated = preliminary
    .slice(0, detailsCount)
    .filter(candidate => candidate.detailsLoaded && passesKnownFilters(candidate, filters));

  if (anchorProfiles.length) {
    const allAnchorKeywordIds = new Set(anchorProfiles.flatMap(profile => profile.keywords.map(keyword => keyword.id)));
    for (const candidate of hydrated) {
      const candidateKeywordIds = new Set(candidate.keywords.map(keyword => keyword.id));
      const sharedKeywordIds = [...allAnchorKeywordIds].filter(id => candidateKeywordIds.has(id));
      sharedKeywordIds.forEach(id => candidate.matchedKeywordIds.add(id));
      candidate.anchorEvidenceAvailable = true;
      candidate.sharedAnchorKeywordCount = sharedKeywordIds.length;
      const candidateGenres = new Set(candidate.genres.map(normalize));
      const profileScores = anchorProfiles.map(profile => {
        const profileKeywordIds = new Set(profile.keywords.map(keyword => keyword.id));
        const sharedKeywords = [...profileKeywordIds].filter(id => candidateKeywordIds.has(id)).length;
        const keywordScore = profileKeywordIds.size ? Math.min(1, sharedKeywords / Math.min(3, profileKeywordIds.size)) : 0;
        const profileGenres = new Set(profile.genreNames.map(normalize));
        const sharedGenres = [...profileGenres].filter(genre => candidateGenres.has(genre)).length;
        const genreScore = profileGenres.size ? sharedGenres / profileGenres.size : 0;
        return { keywordScore, genreScore };
      });
      candidate.anchorGenreScore = profileScores.reduce((sum, score) => sum + score.genreScore, 0) / profileScores.length;
      candidate.anchorOverlapScore = profileScores.reduce(
        (sum, score) => sum + score.keywordScore * .78 + score.genreScore * .22,
        0,
      ) / profileScores.length;
    }
  }

  let embeddingsAvailable = false;
  if (hydrated.length) {
    const queryText = request.mode === 'similar'
      ? anchorProfiles.map(profile => [profile.overview, profile.genreNames.join(', '), profile.keywords.map(keyword => keyword.name).join(', ')].filter(Boolean).join('\n')).join('\n---\n')
      : [
        queryToInterpret,
        ...intent.requiredConceptGroups.map(group => `${group.label}: ${group.synonyms.join(', ')}`),
        ...intent.softConcepts,
        ...intent.toneAndMood,
        ...intent.broadSearchPhrases,
      ].filter(Boolean).join('\n');
    const semanticCandidates = hydrated.slice(0, MAX_SEMANTIC_CANDIDATES);
    // Candidate titles are deliberately absent from semantic documents as well.
    const docs = semanticCandidates.map(candidate => [candidate.overview, candidate.genres.join(', '), candidate.keywords.map(k => k.name).join(', ')].filter(Boolean).join('\n'));
    try {
      if (!queryText.trim()) throw new Error('No semantic query evidence was available');
      const vectors = await (dependencies.embed || embedForSearch)(env, queryText, docs);
      semanticCandidates.forEach((candidate, index) => {
        const vector = vectors.candidateVectors[index];
        if (vector) candidate.semanticScore = cosineSimilarity(vectors.queryVector, vector);
      });
      embeddingsAvailable = semanticCandidates.some(candidate => candidate.semanticScore !== undefined);
    } catch (error) {
      console.warn('Gemini embeddings unavailable; using deterministic relevance ranking', error instanceof Error ? error.message : error);
    }
  }

  return rankCandidates(
    hydrated,
    effectiveIntent,
    filters,
    request.mode === 'similar',
    embeddingsAvailable,
  ).slice(0, MAX_CANDIDATES);
}
