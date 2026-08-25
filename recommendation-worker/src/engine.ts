import {
  assessPremiseCandidates,
  assessSimilarCandidates,
  embedForSearch,
  interpretQuery,
  recommendDescribeTitles,
  recommendSimilarTitles,
} from './gemini';
import { buildKeywordExpressions, canonicalConceptPhrase, cosineSimilarity, mergeFilters, normalize, passesHardFilters, rankCandidates } from './ranking';
import { ParsedRecommendationRequest } from './schemas';
import { TmdbClient, TmdbDetails, TmdbPage } from './tmdb';
import { Candidate, DescribeRecommendation, InterpretedIntent, MediaType, PremiseAssessment, PremiseCandidateDocument, RecommendationEnv, RecommendationFilters, RecommendationResult, ServiceError, SimilarAnchorDocument, TmdbGenre, TmdbListItem } from './types';

export interface EngineDependencies {
  interpret?: typeof interpretQuery;
  recommendDescribe?: typeof recommendDescribeTitles;
  recommendSimilar?: typeof recommendSimilarTitles;
  embed?: typeof embedForSearch;
  verifyPremises?: (
    env: RecommendationEnv,
    query: string,
    mediaType: MediaType,
    groups: InterpretedIntent['requiredConceptGroups'],
    candidates: Array<{
      index: number; title: string; originalTitle?: string; releaseYear?: number; overview: string;
      genres: string[]; keywords: string[]; geminiReason: string; geminiConfidence: number;
    }>,
  ) => Promise<PremiseAssessment[]>;
  verifySimilarity?: (
    env: RecommendationEnv,
    anchors: SimilarAnchorDocument[],
    refinement: string,
    mediaType: MediaType,
    candidates: PremiseCandidateDocument[],
  ) => Promise<PremiseAssessment[]>;
  tmdb?: Pick<TmdbClient, 'callsRemaining' | 'genres' | 'searchKeyword' | 'searchPerson' | 'searchCompany' | 'discover' | 'details'> &
    Partial<Pick<TmdbClient, 'searchTitle'>>;
}

const MAX_CANDIDATES = 220;
const MAX_SEMANTIC_CANDIDATES = 64;
const MAX_DETAIL_CANDIDATES = 64;
const MAX_GENERATED_CANDIDATES = 24;
const MAX_GENERATED_RESULTS = 20;
const TMDB_DETAIL_RESERVE = 24;
// Cloudflare permits six simultaneous outbound connections per invocation.
// Filling all six lanes keeps Gemini-grounded requests interactive without
// increasing the total subrequest budget.
const DISCOVERY_CONCURRENCY = 6;
const DETAIL_CONCURRENCY = 6;
const MAX_KEYWORD_SEARCHES = 18;
const MAX_KEYWORD_SEARCHES_PER_GROUP = 2;

const GENERIC_SUBJECT_CONCEPTS = new Set([
  'character', 'characters', 'individual', 'individuals', 'movie', 'movies', 'people', 'person', 'persons',
  'protagonist', 'protagonists', 'series', 'show', 'shows', 'someone', 'story', 'stories',
]);

const NARRATIVE_CONNECTOR_CONCEPTS = new Set([
  'deal', 'dealing', 'deals', 'discover', 'discovering', 'discovers', 'face', 'faces', 'facing',
  'find', 'finding', 'finds', 'hunt', 'hunting', 'hunts', 'investigate', 'investigates', 'investigating',
  'pursue', 'pursues', 'pursuing', 'search', 'searches', 'searching', 'solve', 'solves', 'solving',
  'track', 'tracking', 'tracks', 'tries', 'trying', 'uncover', 'uncovering', 'uncovers',
  'become', 'becomes', 'becoming', 'develop', 'developing', 'develops', 'get', 'gets', 'having',
]);

const GENRE_CONCEPT_ALIASES = new Map<string, Set<string>>([
  ['Animation', new Set(['animated', 'animation', 'anime'])],
  ['Comedy', new Set(['comedic', 'comedy', 'comic', 'funny', 'humor', 'humorous', 'humour'])],
  ['Crime', new Set(['crime', 'criminal'])],
  ['Documentary', new Set(['documentary', 'nonfiction'])],
  ['Fantasy', new Set(['fantasy'])],
  ['History', new Set(['historical', 'history'])],
  ['Horror', new Set(['horror', 'scary'])],
  ['Music', new Set(['music', 'musical'])],
  ['Mystery', new Set(['mystery', 'whodunit'])],
  ['Romance', new Set(['romance', 'romantic'])],
  ['Science Fiction', new Set(['sci fi', 'science fiction'])],
  ['Thriller', new Set(['suspense', 'thriller'])],
  ['War', new Set(['war', 'wartime'])],
  ['Western', new Set(['cowboy', 'western'])],
]);

const CONCEPT_SYNONYM_FAMILIES: string[][] = [
  ['adolescent', 'child', 'kid', 'teen', 'teenage protagonist', 'teenager', 'young adult', 'young people', 'youth'],
  ['detective', 'investigator', 'police detective'],
  ['psychic ability', 'psychic power', 'supernatural ability', 'supernatural force', 'supernatural power', 'superpower', 'extraordinary ability', 'paranormal ability', 'superhuman ability'],
  ['serial killer', 'serial murderer', 'serial murder'],
  ['small town', 'rural town'],
  ['android', 'artificial intelligence', 'ai', 'robot', 'synthetic being'],
  ['aware', 'conscious', 'gain consciousness', 'self aware', 'sentient'],
  ['alien', 'extraterrestrial', 'ufo'],
  ['abducted', 'abduction', 'alien abduction', 'kidnapped by aliens'],
  ['cannot escape', 'cut off', 'inescapable', 'isolated', 'stranded', 'trapped'],
  ['mysterious location', 'mysterious place', 'unknown place'],
  ['natural catastrophe', 'natural disaster', 'earthquake', 'extreme storm', 'flood', 'hurricane', 'tornado', 'tsunami', 'volcanic eruption', 'wildfire'],
  ['cold case', 'unsolved case'],
  ['time loop', 'temporal loop'],
  ['time travel', 'travel through time'],
];

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
    matchReasons: [],
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
  candidate.status = details.status || candidate.status;
  candidate.keywords = details.keywords?.keywords || details.keywords?.results || candidate.keywords;
  candidate.certifications = [...new Set([
    ...(details.release_dates?.results || []).filter(country => country.iso_3166_1 === 'US').flatMap(country => country.release_dates || []).map(release => release.certification?.trim()),
    ...(details.content_ratings?.results || []).filter(country => country.iso_3166_1 === 'US').map(rating => rating.rating?.trim()),
  ].filter((value): value is string => Boolean(value)))];
  candidate.hardFiltersVerified = true;
  candidate.detailsLoaded = true;
}

function emptyIntent(): InterpretedIntent {
  return {
    hardFilters: { originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [] },
    requiredConceptGroups: [], softConcepts: [], excludedConcepts: [], excludedKeywords: [], crewNames: [],
    castNames: [], studioNames: [], certifications: [], genreHints: [], toneAndMood: [], broadSearchPhrases: [],
  };
}

function tmdbItemYear(item: TmdbListItem, mediaType: MediaType): number | undefined {
  const value = mediaType === 'movie' ? item.release_date : item.first_air_date;
  const year = value ? Number(value.slice(0, 4)) : NaN;
  return Number.isInteger(year) ? year : undefined;
}

function resolveTmdbIdentity(
  recommendation: DescribeRecommendation,
  mediaType: MediaType,
  results: TmdbListItem[],
): TmdbListItem | undefined {
  const expectedTitle = normalize(recommendation.title);
  const exactTitles = results.filter(item => {
    const primary = mediaType === 'movie' ? item.title : item.name;
    const original = mediaType === 'movie' ? item.original_title : item.original_name;
    return normalize(primary || '') === expectedTitle || normalize(original || '') === expectedTitle;
  });
  const exactYear = exactTitles.find(item => tmdbItemYear(item, mediaType) === recommendation.releaseYear);
  if (exactYear) return exactYear;
  const nearbyYear = exactTitles.find(item => {
    const year = tmdbItemYear(item, mediaType);
    return year !== undefined && Math.abs(year - recommendation.releaseYear) <= 2;
  });
  if (nearbyYear) return nearbyYear;
  return exactTitles.length === 1 && tmdbItemYear(exactTitles[0], mediaType) === undefined
    ? exactTitles[0]
    : undefined;
}

async function expandedGeneratedRecommendations(
  limit: number,
  generate: (excludedTitles: string[]) => Promise<DescribeRecommendation[]>,
): Promise<DescribeRecommendation[]> {
  const recommendations = new Map<string, DescribeRecommendation>();
  for (let pass = 0; pass < 2 && recommendations.size < limit; pass++) {
    const excludedTitles = [...recommendations.values()].map(item => `${item.title} (${item.releaseYear})`);
    const generated = await generate(excludedTitles);
    for (const item of generated) {
      if (item.confidence < .60) continue;
      const key = `${normalize(item.title)}:${item.releaseYear}`;
      const existing = recommendations.get(key);
      if (!existing || item.confidence > existing.confidence) recommendations.set(key, item);
    }
  }
  return [...recommendations.values()]
    .sort((left, right) => right.confidence - left.confidence || left.title.localeCompare(right.title))
    .slice(0, limit);
}

interface GeneratedRecommendationContext {
  filters: RecommendationFilters;
  candidateLimit: number;
  excludedCandidateKeys: Set<string>;
  recommendationSource: string;
  verificationSource: string;
  generate: (excludedTitles: string[]) => Promise<DescribeRecommendation[]>;
  verify: (documents: PremiseCandidateDocument[]) => Promise<PremiseAssessment[]>;
}

async function processGeneratedRecommendations(
  env: RecommendationEnv,
  request: ParsedRecommendationRequest,
  tmdb: NonNullable<EngineDependencies['tmdb']>,
  context: GeneratedRecommendationContext,
): Promise<RecommendationResult[]> {
  if (!tmdb.searchTitle) {
    throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB title verification is unavailable', 503, true);
  }
  const recommendations = await expandedGeneratedRecommendations(context.candidateLimit, context.generate);
  if (!recommendations.length) return [];

  const candidates = new Map<string, Candidate>();
  for (let start = 0; start < recommendations.length; start += DISCOVERY_CONCURRENCY) {
    const batch = recommendations.slice(start, start + DISCOVERY_CONCURRENCY);
    const responses = await Promise.all(batch.map(item => optionalTmdbCall(
      `identity:${request.mediaType}:${item.title}`,
      () => tmdb.searchTitle!(request.mediaType, item.title),
    )));
    responses.forEach((response, index) => {
      if (!response) return;
      const recommendation = batch[index];
      const identity = resolveTmdbIdentity(recommendation, request.mediaType, response.results || []);
      if (!identity) return;
      const candidate = toCandidate(identity, request.mediaType, new Map());
      if (!candidate || context.excludedCandidateKeys.has(candidate.key)) return;
      candidate.retrievalSources.add(context.recommendationSource);
      candidate.retrievalSources.add('tmdb:identity-search');
      candidate.geminiRecommendationConfidence = recommendation.confidence;
      candidate.geminiRecommendationReason = recommendation.reason;
      const existing = candidates.get(candidate.key);
      if (!existing || (existing.geminiRecommendationConfidence || 0) < recommendation.confidence) {
        candidates.set(candidate.key, candidate);
      }
    });
  }

  const resolved = [...candidates.values()];
  if (!resolved.length) return [];
  const documents = resolved.map((candidate, index) => ({
    index,
    title: candidate.title,
    originalTitle: candidate.originalTitle,
    releaseYear: tmdbItemYear({
      id: candidate.tmdbId,
      release_date: request.mediaType === 'movie' ? candidate.releaseDate : undefined,
      first_air_date: request.mediaType === 'tv' ? candidate.releaseDate : undefined,
    }, request.mediaType),
    overview: candidate.overview || '',
    genres: candidate.genres,
    keywords: candidate.keywords.map(keyword => keyword.name),
    geminiReason: candidate.geminiRecommendationReason || '',
    geminiConfidence: candidate.geminiRecommendationConfidence || 0,
  }));
  const assessments = await context.verify(documents);
  const byIndex = new Map(assessments.map(assessment => [assessment.index, assessment]));
  if (byIndex.size !== documents.length) {
    throw new ServiceError(
      'GEMINI_UNAVAILABLE',
      `Gemini returned an incomplete premise assessment (${byIndex.size}/${documents.length})`,
      502,
      true,
    );
  }

  const scored = resolved.flatMap((candidate, index) => {
    const assessment = byIndex.get(index);
    if (!assessment || assessment.relevanceScore < .70) return [];
    const score = assessment.relevanceScore * .72 + (candidate.geminiRecommendationConfidence || 0) * .28;
    candidate.premiseScore = assessment.relevanceScore;
    candidate.premiseReason = assessment.reason;
    candidate.finalScore = score;
    candidate.retrievalSources.add(context.verificationSource);
    return [{ candidate, assessment, score }];
  }).sort((left, right) => right.score - left.score || left.candidate.title.localeCompare(right.candidate.title));

  // Gemini precision scoring happens before detail hydration so the finite TMDB
  // budget is spent only on the strongest candidates. TMDB then authoritatively
  // verifies every hard filter, including Returning/Ended, without affecting score.
  const hydrated: typeof scored = [];
  const detailPool = scored.slice(0, MAX_GENERATED_RESULTS);
  for (let start = 0; start < detailPool.length; start += DETAIL_CONCURRENCY) {
    const batch = detailPool.slice(start, start + DETAIL_CONCURRENCY);
    const details = await Promise.all(batch.map(({ candidate }) => optionalTmdbCall(
      `details:${request.mediaType}:${candidate.tmdbId}`,
      () => tmdb.details(request.mediaType, candidate.tmdbId),
    )));
    details.forEach((detail, index) => {
      if (!detail) return;
      const item = batch[index];
      mergeDetails(item.candidate, detail);
      item.candidate.retrievalSources.add('tmdb:details');
      if (passesHardFilters(item.candidate, context.filters)) hydrated.push(item);
    });
  }

  const collectionCounts = new Map<number, number>();
  return hydrated.flatMap(({ candidate, assessment, score }): RecommendationResult[] => {
    if (candidate.collectionId) {
      const count = collectionCounts.get(candidate.collectionId) || 0;
      if (count >= 2) return [];
      collectionCounts.set(candidate.collectionId, count + 1);
    }
    const matchLevel = score >= .88 ? 'Exceptional' : score >= .76 ? 'Strong' : 'Relevant';
    const reasons = [assessment.reason, candidate.geminiRecommendationReason]
      .filter((value): value is string => Boolean(value?.trim()))
      .filter((value, reasonIndex, values) => values.findIndex(other => normalize(other) === normalize(value)) === reasonIndex)
      .slice(0, 2);
    return [{
      tmdbId: candidate.tmdbId,
      mediaType: candidate.mediaType,
      title: candidate.title,
      originalTitle: candidate.originalTitle,
      overview: candidate.overview,
      posterPath: candidate.posterPath,
      backdropPath: candidate.backdropPath,
      releaseDate: candidate.releaseDate,
      genres: candidate.genres,
      runtimeMinutes: candidate.runtimeMinutes,
      originalLanguage: candidate.originalLanguage,
      originCountries: candidate.originCountries,
      tmdbRating: candidate.tmdbRating,
      tmdbVoteCount: candidate.tmdbVoteCount,
      status: candidate.status,
      matchLevel,
      finalScore: Number(score.toFixed(6)),
      matchReasons: reasons,
      retrievalSources: [...candidate.retrievalSources].sort(),
    }];
  });
}

async function processDescribeRecommendation(
  env: RecommendationEnv,
  request: ParsedRecommendationRequest,
  tmdb: NonNullable<EngineDependencies['tmdb']>,
  dependencies: EngineDependencies,
): Promise<RecommendationResult[]> {
  const query = request.previousQuery && request.refinementQuery
    ? `${request.previousQuery}. Additional requirement: ${request.refinementQuery}`
    : (request.query || request.refinementQuery || '').trim();
  const filters = mergeFilters(request.filters, emptyIntent().hardFilters);
  applyQueryDateConstraints(query, filters);
  return processGeneratedRecommendations(env, request, tmdb, {
    filters,
    candidateLimit: MAX_GENERATED_CANDIDATES,
    excludedCandidateKeys: new Set(),
    recommendationSource: 'gemini:describe-recommendation',
    verificationSource: 'gemini:premise-verification',
    generate: excludedTitles => (dependencies.recommendDescribe || recommendDescribeTitles)(
      env, query, request.mediaType, filters, excludedTitles,
    ),
    verify: documents => (dependencies.verifyPremises || assessPremiseCandidates)(
      env, query, request.mediaType, [], documents,
    ),
  });
}

async function processSimilarRecommendation(
  env: RecommendationEnv,
  request: ParsedRecommendationRequest,
  tmdb: NonNullable<EngineDependencies['tmdb']>,
  dependencies: EngineDependencies,
): Promise<RecommendationResult[]> {
  const requestedAnchors = request.anchors?.length ? request.anchors : (request.anchor ? [request.anchor] : []);
  const anchorDetails = await Promise.all(requestedAnchors.map(anchor => optionalTmdbCall(
    `anchor-details:${anchor.mediaType}:${anchor.tmdbId}`,
    () => tmdb.details(anchor.mediaType, anchor.tmdbId),
  )));
  const anchors = anchorDetails.flatMap((details, index): SimilarAnchorDocument[] => {
    if (!details) return [];
    const requested = requestedAnchors[index];
    const title = (requested.mediaType === 'movie' ? details.title : details.name)?.trim() || requested.title;
    const date = requested.mediaType === 'movie' ? details.release_date : details.first_air_date;
    const parsedYear = date ? Number(date.slice(0, 4)) : NaN;
    return [{
      tmdbId: requested.tmdbId,
      mediaType: requested.mediaType,
      title,
      originalTitle: requested.mediaType === 'movie' ? details.original_title : details.original_name,
      releaseYear: Number.isInteger(parsedYear) ? parsedYear : undefined,
      overview: details.overview || '',
      genres: (details.genres || []).map(genre => genre.name),
      keywords: (details.keywords?.keywords || details.keywords?.results || []).map(keyword => keyword.name),
    }];
  });
  if (anchors.length !== requestedAnchors.length) {
    throw new ServiceError('TMDB_UNAVAILABLE', 'TMDB could not verify every Similar anchor', 503, true);
  }
  const refinement = (request.refinementQuery || request.query || '').trim();
  const filters = mergeFilters(request.filters, emptyIntent().hardFilters);
  applyQueryDateConstraints(refinement, filters);
  const excludedCandidateKeys = new Set(
    requestedAnchors
      .filter(anchor => anchor.mediaType === request.mediaType)
      .map(anchor => `${request.mediaType}:${anchor.tmdbId}`),
  );
  const candidateLimit = Math.max(18, MAX_GENERATED_CANDIDATES - anchors.length);
  return processGeneratedRecommendations(env, request, tmdb, {
    filters,
    candidateLimit,
    excludedCandidateKeys,
    recommendationSource: 'gemini:similar-recommendation',
    verificationSource: 'gemini:similarity-verification',
    generate: excludedTitles => (dependencies.recommendSimilar || recommendSimilarTitles)(
      env,
      anchors,
      request.mediaType,
      refinement,
      filters,
      [...anchors.map(anchor => `${anchor.title} (${anchor.releaseYear || 'unknown'})`), ...excludedTitles],
    ),
    verify: documents => (dependencies.verifySimilarity || assessSimilarCandidates)(
      env, anchors, refinement, request.mediaType, documents,
    ),
  });
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
            source.includes('premise-seed') ? 50 :
              source.includes('concept-') ? 24 :
            source.includes('people') || source.includes('studios') ? 18 :
              source.includes('genre') ? 4 : -8
  ), 0);
  return candidate.matchedConceptGroupIndexes.size * 70 +
    candidate.matchedKeywordIds.size * 12 + sourceFamilyCount(candidate) * 8 + targetedBonus +
    Math.log10((candidate.tmdbVoteCount || 0) + 1);
}

function exactKeywordIds(expression: string): number[] {
  return expression.split(',').filter(segment => !segment.includes('|')).map(Number).filter(Number.isFinite);
}

function keywordMatchesSearchTerm(keywordName: string, searchTerm: string): boolean {
  // TMDB commonly stores a singular tag while natural-language queries use a
  // plural ("teenagers" -> "teenager"). Treat only that grammatical variant
  // as exact grounding; never accept a merely nearby/fuzzy search result.
  return canonicalConceptPhrase(keywordName) === canonicalConceptPhrase(searchTerm);
}

function groupTerms(group: InterpretedIntent['requiredConceptGroups'][number]): Set<string> {
  return new Set([group.label, ...group.synonyms].map(canonicalConceptPhrase).filter(Boolean));
}

function expandConceptSynonyms(group: InterpretedIntent['requiredConceptGroups'][number]): InterpretedIntent['requiredConceptGroups'][number] {
  const terms = groupTerms(group);
  const expansions = CONCEPT_SYNONYM_FAMILIES
    .filter(family => family.some(value => terms.has(canonicalConceptPhrase(value))))
    .flat();
  return { ...group, synonyms: [...new Set([...group.synonyms, ...expansions])] };
}

function genreEquivalentForGroup(
  group: InterpretedIntent['requiredConceptGroups'][number],
  genreHints: string[],
): string | undefined {
  const terms = groupTerms(group);
  return genreHints.find(genre => {
    const aliases = GENRE_CONCEPT_ALIASES.get(genre);
    return aliases && terms.size > 0 && [...terms].every(term => aliases.has(term));
  });
}

function cleanInterpretedIntent(intent: InterpretedIntent): InterpretedIntent {
  const merged: Array<{ group: InterpretedIntent['requiredConceptGroups'][number]; genre?: string }> = [];
  for (const originalGroup of intent.requiredConceptGroups) {
    const group = expandConceptSynonyms(originalGroup);
    const terms = groupTerms(group);
    if (terms.size && [...terms].every(term => NARRATIVE_CONNECTOR_CONCEPTS.has(term) || GENERIC_SUBJECT_CONCEPTS.has(term))) continue;
    const genre = genreEquivalentForGroup(group, intent.genreHints);
    const existing = merged.find(entry => {
      const existingTerms = groupTerms(entry.group);
      return (genre !== undefined && entry.genre === genre) || [...terms].some(term => existingTerms.has(term));
    });
    if (!existing) {
      merged.push({
        group: genre
          ? { ...group, label: genre.toLocaleLowerCase(), synonyms: [...new Set([...group.synonyms, genre.toLocaleLowerCase()])] }
          : { ...group, synonyms: [...new Set(group.synonyms)] },
        genre,
      });
      continue;
    }
    existing.group = {
      label: existing.genre ? existing.genre.toLocaleLowerCase() : existing.group.label,
      synonyms: [...new Set([...existing.group.synonyms, ...group.synonyms, ...(genre ? [genre.toLocaleLowerCase()] : [])])],
      weight: Math.max(existing.group.weight, group.weight),
    };
  }
  return { ...intent, requiredConceptGroups: merged.map(entry => entry.group) };
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
    'vote_average.gte': filters.minimumTmdbRating, 'vote_count.gte': type === 'movie' ? 20 : 10,
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
  if (filters.seriesStatus && candidate.detailsLoaded) {
    const expected = filters.seriesStatus === 'returning' ? 'returning series' : 'ended';
    if (!candidate.status || normalize(candidate.status) !== expected) return false;
  }
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
  const tmdb = dependencies.tmdb || new TmdbClient(env, request.mode === 'filters' ? 38 : 44);
  if (request.mode === 'describe') {
    return processDescribeRecommendation(env, request, tmdb, dependencies);
  }
  if (request.mode === 'similar') {
    return processSimilarRecommendation(env, request, tmdb, dependencies);
  }
  const interpret = dependencies.interpret || interpretQuery;
  const queryToInterpret = request.previousQuery && request.refinementQuery
    ? `${request.previousQuery} [Refinement adjustment: ${request.refinementQuery}]`
    : (request.query || request.refinementQuery || '');
  let intent = queryToInterpret.trim()
    ? await interpret(env, queryToInterpret, request.mediaType)
    : emptyIntent();
  intent = cleanInterpretedIntent(intent);
  applyQueryDateConstraints(queryToInterpret, intent.hardFilters);
  const filters = mergeFilters(request.filters, intent.hardFilters);
  const effectiveIntent: InterpretedIntent = { ...intent, hardFilters: filters };

  const genreResponse = await tmdb.genres(request.mediaType);
  const genresById = new Map(genreResponse.genres.map(genre => [genre.id, genre.name]));
  const genresByName = genreLookup(genreResponse.genres);
  const candidates = new Map<string, Candidate>();
  const addPage = (
    page: TmdbPage,
    source: string,
    matchedIds: number[] = [],
    matchedConceptGroups: number[] = [],
  ) => {
    for (const item of page.results || []) {
      const fresh = toCandidate(item, request.mediaType, genresById);
      if (!fresh) continue;
      const candidate = candidates.get(fresh.key) || fresh;
      candidate.retrievalSources.add(source);
      matchedIds.forEach(id => candidate.matchedKeywordIds.add(id));
      matchedConceptGroups.forEach(index => candidate.matchedConceptGroupIndexes.add(index));
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

  const groupKeywordIds: number[][] = [];
    let keywordSearches = 0;
    for (const group of intent.requiredConceptGroups) {
      const ids: number[] = [];
      if (genreEquivalentForGroup(group, intent.genreHints)) {
        groupKeywordIds.push(ids);
        continue;
      }
      let groupSearches = 0;
      const searchedTerms = new Set<string>();
      for (const phrase of group.synonyms) {
        if (keywordSearches >= MAX_KEYWORD_SEARCHES || groupSearches >= MAX_KEYWORD_SEARCHES_PER_GROUP || tmdb.callsRemaining <= TMDB_DETAIL_RESERVE + 8) break;
        const term = phrase.replace(/-/g, ' ').replace(/\s+/g, ' ').trim();
        const canonicalTerm = canonicalConceptPhrase(term);
        if (!canonicalTerm || searchedTerms.has(canonicalTerm)) continue;
        searchedTerms.add(canonicalTerm);
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
      const phraseGroup = { label: phrase, synonyms: [phrase], weight: 1 };
      if (genreEquivalentForGroup(phraseGroup, intent.genreHints)) continue;
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
      (!intent.requiredConceptGroups.length && !targetedEntities && !hintedGenreIds.length && intent.discoveryProfile !== 'hidden_gems');
    if (needsGeneralDiscovery && tmdb.callsRemaining > TMDB_DETAIL_RESERVE && candidates.size < MAX_CANDIDATES) {
      const generalSort = intent.discoveryProfile === 'blockbusters' ? 'popularity.desc' : 'vote_count.desc';
      await runDiscover('discover:filtered-catalogue', { sort_by: generalSort, ...negativeKeywordParam }, 6);
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

  let embeddingsAvailable = false;
  if (hydrated.length) {
    const queryText = [
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
    embeddingsAvailable,
    false,
  ).slice(0, MAX_CANDIDATES);
}
