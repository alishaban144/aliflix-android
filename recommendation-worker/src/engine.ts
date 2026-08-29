import {
  assessRecommendationPremise,
  assessRecommendationSimilarity,
  aiProviderName,
  embedForSearch,
  fallbackIntentFromQuery,
  isGroqAiModel,
  isRetryableAiProviderError,
  interpretQuery,
  recommendDescribeTitles,
  recommendSimilarTitles,
  selectedAiModel,
} from './ai';
import { buildKeywordExpressions, canonicalConceptPhrase, cosineSimilarity, mergeFilters, normalize, passesHardFilters, rankCandidates } from './ranking';
import { ParsedRecommendationRequest } from './schemas';
import { TmdbClient, TmdbDetails, TmdbPage } from './tmdb';
import { Candidate, DescribeRecommendation, InterpretedIntent, MediaType, PremiseAssessment, PremiseCandidateDocument, RecommendationEnv, RecommendationFilters, RecommendationResult, ServiceError, SimilarAnchorDocument, TmdbGenre, TmdbListItem } from './types';

export interface EngineDependencies {
  interpret?: typeof interpretQuery;
  recommendDescribe?: typeof recommendDescribeTitles;
  recommendSimilar?: typeof recommendSimilarTitles;
  assessPremise?: typeof assessRecommendationPremise;
  assessSimilarity?: typeof assessRecommendationSimilarity;
  embed?: typeof embedForSearch;
  tmdb?: Pick<TmdbClient, 'callsRemaining' | 'genres' | 'searchKeyword' | 'searchPerson' | 'searchCompany' | 'discover' | 'details'> &
    Partial<Pick<TmdbClient, 'searchTitle'>>;
}

const MAX_CANDIDATES = 220;
const MAX_SEMANTIC_CANDIDATES = 64;
const MAX_DETAIL_CANDIDATES = 64;
const MAX_GENERATED_CANDIDATES = 28;
const MAX_GENERATED_RESULTS = 16;
const MAX_NEW_GENERATED_CANDIDATES_PER_KEYWORD = 8;
const MIN_GENERATED_CANDIDATE_CONFIDENCE = .45;
const MIN_VERIFIED_RELEVANCE = .70;
const TMDB_DETAIL_RESERVE = 24;
// Cloudflare permits six simultaneous outbound connections per invocation.
// Filling all six lanes keeps AI-grounded requests interactive without
// increasing the total subrequest budget.
const DISCOVERY_CONCURRENCY = 6;
const DETAIL_CONCURRENCY = 6;
const MAX_KEYWORD_SEARCHES = 18;
const MAX_KEYWORD_SEARCHES_PER_GROUP = 2;
const TMDB_PAGE_SIZE = 20;
const TMDB_MAX_PAGE = 500;
const FILTER_WINDOW_PAGES = 6;
const FILTER_WINDOW_SIZE = TMDB_PAGE_SIZE * FILTER_WINDOW_PAGES;

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

async function generatedRecommendations(
  limit: number,
  generate: (excludedTitles: string[]) => Promise<DescribeRecommendation[]>,
): Promise<DescribeRecommendation[]> {
  const recommendations = new Map<string, DescribeRecommendation>();
  // One explicit generation request keeps Describe/Similar predictable under
  // low daily Gemini quotas. The model is already asked for the full target
  // count; a hidden expansion request could double quota use for one tap.
  const generated = await generate([]);
  for (const item of generated) {
    // Keep plausible real candidates for the independent evidence judge.
    // First-pass self-confidence must not become the final relevance gate.
    if (item.confidence < MIN_GENERATED_CANDIDATE_CONFIDENCE) continue;
    const key = `${normalize(item.title)}:${item.releaseYear}`;
    const existing = recommendations.get(key);
    if (!existing || item.confidence > existing.confidence) recommendations.set(key, item);
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
  supplementKeywordTerms?: string[];
  generate: (excludedTitles: string[]) => Promise<DescribeRecommendation[]>;
  verify: (candidates: PremiseCandidateDocument[]) => Promise<PremiseAssessment[]>;
}

async function supplementGeneratedCandidatesFromTmdb(
  request: ParsedRecommendationRequest,
  tmdb: NonNullable<EngineDependencies['tmdb']>,
  context: GeneratedRecommendationContext,
  candidates: Map<string, Candidate>,
): Promise<void> {
  if (!context.supplementKeywordTerms?.length || candidates.size >= MAX_GENERATED_CANDIDATES) return;
  if (typeof tmdb.searchKeyword !== 'function' || typeof tmdb.discover !== 'function') return;

  const terms = [...new Set(context.supplementKeywordTerms.map(canonicalConceptPhrase).filter(Boolean))]
    .slice(0, 6);
  for (const term of terms) {
    if (candidates.size >= MAX_GENERATED_CANDIDATES || tmdb.callsRemaining <= MAX_GENERATED_RESULTS + 1) break;
    const keywordResponse = await optionalTmdbCall(
      `generated-keyword:${term}`,
      () => tmdb.searchKeyword(term),
    );
    const keywordIds = (keywordResponse?.results || [])
      .filter(keyword => keywordMatchesSearchTerm(keyword.name, term))
      .map(keyword => keyword.id)
      .filter((id, index, values) => values.indexOf(id) === index)
      .slice(0, 2);
    if (!keywordIds.length || tmdb.callsRemaining <= MAX_GENERATED_RESULTS) continue;

    const page = await optionalTmdbCall(
      `generated-keyword-discover:${term}`,
      () => tmdb.discover(request.mediaType, { with_keywords: keywordIds.join('|'), page: 1 }),
    );
    let newCandidatesForTerm = 0;
    for (const item of page?.results || []) {
      if (
        candidates.size >= MAX_GENERATED_CANDIDATES ||
        newCandidatesForTerm >= MAX_NEW_GENERATED_CANDIDATES_PER_KEYWORD
      ) break;
      const candidate = toCandidate(item, request.mediaType, new Map());
      if (!candidate || context.excludedCandidateKeys.has(candidate.key)) continue;
      const existing = candidates.get(candidate.key);
      if (existing) {
        keywordIds.forEach(id => existing.matchedKeywordIds.add(id));
        existing.retrievalSources.add('tmdb:keyword-supplement');
        existing.aiRecommendationConfidence = Math.max(existing.aiRecommendationConfidence || 0, .55);
        continue;
      }
      candidate.aiRecommendationConfidence = MIN_GENERATED_CANDIDATE_CONFIDENCE;
      candidate.aiRecommendationReason = `TMDB keyword evidence for ${term}.`;
      candidate.matchedKeywordIds = new Set(keywordIds);
      candidate.retrievalSources.add('tmdb:keyword-supplement');
      candidates.set(candidate.key, candidate);
      newCandidatesForTerm += 1;
    }
  }
}

function candidateReleaseYear(candidate: Candidate): number | undefined {
  const year = candidate.releaseDate ? Number(candidate.releaseDate.slice(0, 4)) : NaN;
  return Number.isInteger(year) ? year : undefined;
}

function premiseDocument(candidate: Candidate, index: number): PremiseCandidateDocument {
  return {
    index,
    title: candidate.title,
    originalTitle: candidate.originalTitle,
    releaseYear: candidateReleaseYear(candidate),
    overview: candidate.overview || '',
    genres: candidate.genres,
    keywords: candidate.keywords.map(keyword => keyword.name),
    aiReason: candidate.aiRecommendationReason || '',
    aiConfidence: candidate.aiRecommendationConfidence || 0,
  };
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
  const recommendations = await generatedRecommendations(context.candidateLimit, context.generate);
  if (!recommendations.length) {
    console.log(JSON.stringify({
      event: 'generated_recommendation_pipeline',
      mode: request.mode,
      model: selectedAiModel(env),
      generated: 0,
      resolved: 0,
      hydrated: 0,
      assessed: 0,
      verified: 0,
    }));
    return [];
  }

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
      candidate.aiRecommendationConfidence = recommendation.confidence;
      candidate.aiRecommendationReason = recommendation.reason;
      const existing = candidates.get(candidate.key);
      if (!existing || (existing.aiRecommendationConfidence || 0) < recommendation.confidence) {
        candidates.set(candidate.key, candidate);
      }
    });
  }

  await supplementGeneratedCandidatesFromTmdb(request, tmdb, context, candidates);

  const resolved = [...candidates.values()]
    .sort((left, right) => (right.aiRecommendationConfidence || 0) - (left.aiRecommendationConfidence || 0)
      || left.title.localeCompare(right.title));
  if (!resolved.length) {
    console.log(JSON.stringify({
      event: 'generated_recommendation_pipeline',
      mode: request.mode,
      model: selectedAiModel(env),
      generated: recommendations.length,
      resolved: 0,
      hydrated: 0,
      assessed: 0,
      verified: 0,
    }));
    return [];
  }

  // TMDB authoritatively verifies identity, plot metadata, and every hard
  // filter before the independent premise judge sees a candidate.
  const hydrated: Candidate[] = [];
  const detailPool = resolved.slice(0, MAX_GENERATED_RESULTS);
  for (let start = 0; start < detailPool.length; start += DETAIL_CONCURRENCY) {
    const batch = detailPool.slice(start, start + DETAIL_CONCURRENCY);
    const details = await Promise.all(batch.map(candidate => optionalTmdbCall(
      `details:${request.mediaType}:${candidate.tmdbId}`,
      () => tmdb.details(request.mediaType, candidate.tmdbId),
    )));
    details.forEach((detail, index) => {
      if (!detail) return;
      const candidate = batch[index];
      mergeDetails(candidate, detail);
      candidate.retrievalSources.add('tmdb:details');
      if (passesHardFilters(candidate, context.filters)) hydrated.push(candidate);
    });
  }

  if (!hydrated.length) {
    console.log(JSON.stringify({
      event: 'generated_recommendation_pipeline',
      mode: request.mode,
      model: selectedAiModel(env),
      generated: recommendations.length,
      resolved: resolved.length,
      hydrated: 0,
      assessed: 0,
      verified: 0,
    }));
    return [];
  }
  let assessments: PremiseAssessment[];
  try {
    assessments = await context.verify(hydrated.map(premiseDocument));
  } catch (error) {
    // A failed final judge must not be disguised as a genuine empty result by
    // the broader deterministic Describe fallback.
    if (isRetryableAiProviderError(error)) {
      throw new ServiceError(
        'AI_VERIFICATION_UNAVAILABLE',
        'The final recommendation quality check is temporarily unavailable. Please try again.',
        error.status,
        true,
      );
    }
    throw error;
  }
  const assessmentByIndex = new Map(assessments.map(assessment => [assessment.index, assessment]));
  const verified = hydrated.flatMap((candidate, index) => {
    const assessment = assessmentByIndex.get(index);
    // Verification is deliberately fail-closed: a missing assessment, title
    // overlap, or a merely adjacent premise must never reach the UI.
    if (!assessment || assessment.relevanceScore < MIN_VERIFIED_RELEVANCE) return [];
    candidate.retrievalSources.add(context.verificationSource);
    candidate.premiseScore = assessment.relevanceScore;
    candidate.premiseReason = assessment.reason;
    candidate.premiseMatchedGroupIndexes = new Set(assessment.matchedGroupIndexes);
    candidate.finalScore = assessment.relevanceScore;
    return [{ candidate, score: assessment.relevanceScore }];
  }).sort((left, right) => right.score - left.score || left.candidate.title.localeCompare(right.candidate.title));

  console.log(JSON.stringify({
    event: 'generated_recommendation_pipeline',
    mode: request.mode,
    model: selectedAiModel(env),
    generated: recommendations.length,
    resolved: resolved.length,
    hydrated: hydrated.length,
    assessed: assessments.length,
    verified: verified.length,
    assessmentSummary: hydrated.map((candidate, index) => ({
      title: candidate.title,
      score: assessmentByIndex.get(index)?.relevanceScore,
    })),
  }));

  const collectionCounts = new Map<number, number>();
  return verified.flatMap(({ candidate, score }): RecommendationResult[] => {
    if (candidate.collectionId) {
      const count = collectionCounts.get(candidate.collectionId) || 0;
      if (count >= 2) return [];
      collectionCounts.set(candidate.collectionId, count + 1);
    }
    const matchLevel = score >= .88 ? 'Exceptional' : score >= .76 ? 'Strong' : 'Relevant';
    const reasons = [candidate.premiseReason]
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
  const premiseIntent = cleanInterpretedIntent(fallbackIntentFromQuery(query));
  const premiseLabels = premiseIntent.requiredConceptGroups.map(group => group.label);
  applyQueryDateConstraints(query, filters);
  const candidateLimit = Math.min(MAX_GENERATED_RESULTS, request.pageSize);
  return processGeneratedRecommendations(env, request, tmdb, {
    filters,
    candidateLimit,
    excludedCandidateKeys: new Set(
      filters.excludedTmdbIds.map(tmdbId => `${request.mediaType}:${tmdbId}`),
    ),
    recommendationSource: `${aiProviderName(selectedAiModel(env))}:describe-recommendation`,
    verificationSource: `${aiProviderName(selectedAiModel(env))}:premise-verification`,
    supplementKeywordTerms: [
      ...(premiseLabels.length === 2 && premiseLabels.every(label => !label.includes(' ')) ? [premiseLabels.join(' ')] : []),
      ...premiseLabels,
      ...premiseIntent.requiredConceptGroups.map(group => (
        group.synonyms.find(value => canonicalConceptPhrase(value) !== canonicalConceptPhrase(group.label)) || group.label
      )),
    ],
    generate: excludedTitles => (dependencies.recommendDescribe || recommendDescribeTitles)(
      env,
      query,
      request.mediaType,
      filters,
      [...new Set([...filters.excludedTitles, ...excludedTitles])],
      candidateLimit,
    ),
    verify: candidates => (dependencies.assessPremise || assessRecommendationPremise)(
      env,
      query,
      request.mediaType,
      premiseIntent.requiredConceptGroups,
      candidates,
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
    [
      ...filters.excludedTmdbIds.map(tmdbId => `${request.mediaType}:${tmdbId}`),
      ...requestedAnchors
        .filter(anchor => anchor.mediaType === request.mediaType)
        .map(anchor => `${request.mediaType}:${anchor.tmdbId}`),
    ],
  );
  const candidateLimit = Math.min(MAX_GENERATED_RESULTS, request.pageSize);
  return processGeneratedRecommendations(env, request, tmdb, {
    filters,
    candidateLimit,
    excludedCandidateKeys,
    recommendationSource: `${aiProviderName(selectedAiModel(env))}:similar-recommendation`,
    verificationSource: `${aiProviderName(selectedAiModel(env))}:similarity-verification`,
    generate: excludedTitles => (dependencies.recommendSimilar || recommendSimilarTitles)(
      env,
      anchors,
      request.mediaType,
      refinement,
      filters,
      [...new Set([
        ...anchors.map(anchor => `${anchor.title} (${anchor.releaseYear || 'unknown'})`),
        ...filters.excludedTitles,
        ...excludedTitles,
      ])],
      candidateLimit,
    ),
    verify: candidates => (dependencies.assessSimilarity || assessRecommendationSimilarity)(
      env,
      anchors,
      refinement,
      request.mediaType,
      candidates,
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

function tmdbSort(type: MediaType, sortBy: RecommendationFilters['sortBy']): string {
  switch (sortBy) {
    case 'most_popular': return 'popularity.desc';
    case 'highest_rated': return 'vote_average.desc';
    case 'most_voted': return 'vote_count.desc';
    case 'newest_first': return type === 'movie' ? 'primary_release_date.desc' : 'first_air_date.desc';
    case 'oldest_first': return type === 'movie' ? 'primary_release_date.asc' : 'first_air_date.asc';
    // TMDB Discover filters by runtime but does not expose runtime as a native
    // sort. Direct movie-filter pagination handles this mode separately.
    case 'runtime_short_to_long': return 'popularity.desc';
    default: return 'vote_count.desc';
  }
}

function discoverParams(
  type: MediaType,
  filters: RecommendationFilters,
  genreIds: Map<string, number>,
  applyRecommendationVoteFloor = true,
): Record<string, string | number | boolean | undefined> {
  const included = filters.includedGenres.map(name => genreIds.get(normalize(name))).filter((id): id is number => id !== undefined);
  const excluded = filters.excludedGenres.map(name => genreIds.get(normalize(name))).filter((id): id is number => id !== undefined);
  const params: Record<string, string | number | boolean | undefined> = {
    sort_by: tmdbSort(type, filters.sortBy), with_original_language: filters.originalLanguage,
    with_origin_country: filters.originCountries.length ? filters.originCountries.join('|') : undefined,
    with_genres: included.length ? included.join(',') : undefined, without_genres: excluded.length ? excluded.join(',') : undefined,
    with_status: type === 'tv'
      ? filters.seriesStatus === 'ended' ? 3 : filters.seriesStatus === 'returning' ? 0 : undefined
      : undefined,
    'with_runtime.gte': filters.minimumRuntimeMinutes, 'with_runtime.lte': filters.maximumRuntimeMinutes,
    'vote_average.gte': filters.minimumTmdbRating,
    'vote_count.gte': applyRecommendationVoteFloor ? (type === 'movie' ? 20 : 10) : undefined,
  };
  if (filters.minimumYear) params[type === 'movie' ? 'primary_release_date.gte' : 'first_air_date.gte'] = `${filters.minimumYear}-01-01`;
  const releaseDateMaximumKey = type === 'movie' ? 'primary_release_date.lte' : 'first_air_date.lte';
  const today = new Date().toISOString().slice(0, 10);
  params[releaseDateMaximumKey] = filters.maximumYear
    ? [`${filters.maximumYear}-12-31`, today].sort()[0]
    : today;
  return params;
}

type FilterDiscoveryTmdb = Pick<TmdbClient, 'callsRemaining' | 'genres' | 'discover'>;

export interface FilterDiscoveryDependencies {
  tmdb?: FilterDiscoveryTmdb;
}

export interface FilterDiscoveryPage {
  results: RecommendationResult[];
  totalResults: number;
  nextOffset: number | null;
}

export function supportsDirectFilterPagination(request: ParsedRecommendationRequest): boolean {
  return request.mode === 'filters' &&
    !request.query.trim() &&
    !request.previousQuery?.trim() &&
    !request.refinementQuery?.trim();
}

function filterResult(candidate: Candidate): RecommendationResult {
  return {
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
    matchLevel: 'Strong',
    finalScore: 1,
    matchReasons: ['Matches your selected filters'],
    retrievalSources: [...candidate.retrievalSources].sort(),
  };
}

async function processRuntimeSortedMoviePage(
  tmdb: FilterDiscoveryTmdb,
  request: ParsedRecommendationRequest,
  offset: number,
  genresById: Map<number, string>,
  baseParams: Record<string, string | number | boolean | undefined>,
): Promise<FilterDiscoveryPage> {
  const lowerRuntime = Math.max(1, request.filters.minimumRuntimeMinutes || 1);
  const upperRuntime = Math.min(1_000, request.filters.maximumRuntimeMinutes || 1_000);
  const pageCache = new Map<string, TmdbPage>();
  const fetchRange = async (minimum: number, maximum: number, page = 1): Promise<TmdbPage> => {
    const key = `${minimum}:${maximum}:${page}`;
    const cached = pageCache.get(key);
    if (cached) return cached;
    const response = await tmdb.discover('movie', {
      ...baseParams,
      sort_by: 'popularity.desc',
      'with_runtime.gte': minimum,
      'with_runtime.lte': maximum,
      page,
    });
    pageCache.set(key, response);
    return response;
  };
  const resultCount = (page: TmdbPage): number => {
    const reachablePages = Math.min(TMDB_MAX_PAGE, Math.max(0, page.total_pages || 0));
    return Math.min(Math.max(0, page.total_results || 0), reachablePages * TMDB_PAGE_SIZE);
  };
  const countRange = async (minimum: number, maximum: number): Promise<number> =>
    resultCount(await fetchRange(minimum, maximum));

  const totalResults = await countRange(lowerRuntime, upperRuntime);
  if (!totalResults || offset >= totalResults) return { results: [], totalResults, nextOffset: null };

  const findRuntimeAtOffset = async (targetOffset: number): Promise<{ runtime: number; withinRuntime: number; count: number } | null> => {
    let low = lowerRuntime;
    let high = upperRuntime;
    let residual = targetOffset;
    while (low < high) {
      const midpoint = Math.floor((low + high) / 2);
      const leftCount = await countRange(low, midpoint);
      if (residual < leftCount) {
        high = midpoint;
      } else {
        residual -= leftCount;
        low = midpoint + 1;
      }
    }
    const count = await countRange(low, low);
    return residual < count ? { runtime: low, withinRuntime: residual, count } : null;
  };

  const results: RecommendationResult[] = [];
  let cursorOffset = offset;
  let runtimeGroupsVisited = 0;
  // Two exact-runtime groups keep this path below the Worker's subrequest
  // ceiling while still returning a full page for normal movie catalogues.
  while (results.length < request.pageSize && cursorOffset < totalResults && runtimeGroupsVisited < 2) {
    const located = await findRuntimeAtOffset(cursorOffset);
    if (!located) break;
    runtimeGroupsVisited++;
    const groupStart = cursorOffset - located.withinRuntime;
    let withinRuntime = located.withinRuntime;
    while (withinRuntime < located.count && results.length < request.pageSize) {
      const pageNumber = Math.floor(withinRuntime / TMDB_PAGE_SIZE) + 1;
      const pageOffset = withinRuntime % TMDB_PAGE_SIZE;
      const response = await fetchRange(located.runtime, located.runtime, pageNumber);
      const rows = (response.results || []).slice(pageOffset);
      if (!rows.length) {
        cursorOffset = groupStart + located.count;
        break;
      }
      const remainingSlots = request.pageSize - results.length;
      const consumedRows = rows.slice(0, remainingSlots);
      for (const item of consumedRows) {
        cursorOffset++;
        withinRuntime++;
        const candidate = toCandidate(item, 'movie', genresById);
        if (!candidate) continue;
        candidate.runtimeMinutes = located.runtime;
        candidate.hardFiltersVerified = true;
        candidate.retrievalSources.add(`discover:runtime-${located.runtime}:page-${response.page}`);
        if (passesKnownFilters(candidate, request.filters)) results.push(filterResult(candidate));
      }
      if (consumedRows.length < remainingSlots && withinRuntime < located.count && response.results.length < TMDB_PAGE_SIZE) {
        cursorOffset = groupStart + located.count;
        break;
      }
    }
  }

  return {
    results,
    totalResults,
    nextOffset: cursorOffset < totalResults ? cursorOffset : null,
  };
}

/**
 * Pages plain structured filters directly through TMDB instead of freezing the
 * first few popularity pages into a small, detail-hydrated recommendation pool.
 * The signed cursor addresses a deterministic 120-row TMDB window. Every row in
 * each window remains reachable, while each 24-row display chunk limits a single
 * original language to half the chunk when alternatives exist.
 */
export async function processFilterDiscoveryPage(
  env: RecommendationEnv,
  request: ParsedRecommendationRequest,
  offset: number,
  dependencies: FilterDiscoveryDependencies = {},
): Promise<FilterDiscoveryPage> {
  const tmdb = dependencies.tmdb || new TmdbClient(env, 38);
  const genreResponse = await tmdb.genres(request.mediaType);
  const genresById = new Map(genreResponse.genres.map(genre => [genre.id, genre.name]));
  const genresByName = genreLookup(genreResponse.genres);
  const baseParams = discoverParams(request.mediaType, request.filters, genresByName, false);
  if (request.mediaType === 'movie' && request.filters.sortBy === 'runtime_short_to_long') {
    return processRuntimeSortedMoviePage(tmdb, request, offset, genresById, baseParams);
  }
  const metadataPage = await tmdb.discover(request.mediaType, { ...baseParams, page: 1 });
  const totalPages = Math.min(TMDB_MAX_PAGE, Math.max(0, metadataPage.total_pages || 0));
  const totalResults = Math.min(metadataPage.total_results || 0, totalPages * TMDB_PAGE_SIZE);
  if (!totalPages || !totalResults || offset >= totalResults) {
    return { results: [], totalResults, nextOffset: null };
  }

  let windowStart = Math.floor(offset / FILTER_WINDOW_SIZE) * FILTER_WINDOW_SIZE;
  let withinWindow = offset - windowStart;
  while (windowStart < totalResults) {
    const startPage = Math.floor(windowStart / TMDB_PAGE_SIZE) + 1;
    const endPage = Math.min(totalPages, startPage + FILTER_WINDOW_PAGES - 1);
    const pageNumbers = Array.from({ length: endPage - startPage + 1 }, (_, index) => startPage + index);
    const pages = await Promise.all(pageNumbers.map(page => page === 1
      ? Promise.resolve(metadataPage)
      : tmdb.discover(request.mediaType, { ...baseParams, page })));

    const candidates: Candidate[] = [];
    for (const page of pages) {
      for (const item of page.results || []) {
        const candidate = toCandidate(item, request.mediaType, genresById);
        if (!candidate) continue;
        if (request.filters.seriesStatus) {
          candidate.status = request.filters.seriesStatus === 'ended' ? 'Ended' : 'Returning Series';
        }
        candidate.hardFiltersVerified = true;
        candidate.retrievalSources.add(`discover:filtered-catalogue:page-${page.page}`);
        if (passesKnownFilters(candidate, request.filters)) candidates.push(candidate);
      }
    }
    const pageResults = candidates
      .slice(withinWindow, withinWindow + request.pageSize)
      .map(filterResult);
    const consumedInWindow = withinWindow + pageResults.length;
    const nextWindowStart = windowStart + FILTER_WINDOW_SIZE;
    const nextOffset = consumedInWindow < candidates.length
      ? windowStart + consumedInWindow
      : nextWindowStart < totalResults ? nextWindowStart : null;
    if (pageResults.length || nextOffset === null) {
      return { results: pageResults, totalResults, nextOffset };
    }
    windowStart = nextWindowStart;
    withinWindow = 0;
  }
  return { results: [], totalResults, nextOffset: null };
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
  // Generated modes make one candidate call and one evidence-grounded
  // verification call. Forty TMDB attempts keep the worst case below the
  // Cloudflare Free plan's 50 external-subrequest limit.
  const tmdb = dependencies.tmdb || new TmdbClient(env, request.mode === 'filters' ? 38 : 40);
  // The request can select one allow-listed model. Both AI stages use that same
  // provider and never fall back to another model behind the user's back.
  const requestedAiModel = request.aiModel || request.geminiModel;
  const recommendationEnv = requestedAiModel
    ? { ...env, AI_GENERATION_MODEL: requestedAiModel }
    : env;
  let providerFallbackIntent: InterpretedIntent | undefined;
  let disableGeminiEmbeddings = isGroqAiModel(selectedAiModel(recommendationEnv));
  if (request.mode === 'describe') {
    try {
      return await processDescribeRecommendation(recommendationEnv, request, tmdb, dependencies);
    } catch (error) {
      if (!isRetryableAiProviderError(error)) throw error;
      const fallbackQuery = request.query || request.refinementQuery || '';
      providerFallbackIntent = fallbackIntentFromQuery(fallbackQuery);
      disableGeminiEmbeddings = true;
      console.warn(JSON.stringify({
        event: 'describe_tmdb_fallback',
        model: selectedAiModel(recommendationEnv),
        reason: error.message,
      }));
    }
  }
  if (request.mode === 'similar') {
    return processSimilarRecommendation(recommendationEnv, request, tmdb, dependencies);
  }
  const interpret = dependencies.interpret || interpretQuery;
  const queryToInterpret = request.previousQuery && request.refinementQuery
    ? `${request.previousQuery} [Refinement adjustment: ${request.refinementQuery}]`
    : (request.query || request.refinementQuery || '');
  let intent = providerFallbackIntent || (queryToInterpret.trim()
    ? await interpret(recommendationEnv, queryToInterpret, request.mediaType)
    : emptyIntent());
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
  if (hydrated.length && !disableGeminiEmbeddings) {
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
