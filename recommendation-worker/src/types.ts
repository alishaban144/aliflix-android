export type MediaType = 'movie' | 'tv';
export type RequestMode = 'describe' | 'similar' | 'filters';
export type RecommendationSort = 'most_popular' | 'highest_rated' | 'most_voted' | 'newest_first' | 'oldest_first' | 'runtime_short_to_long';
export type GeminiAiModel = 'gemini-3.5-flash' | 'gemini-3.7-flash';
export type GroqAiModel = 'groq-qwen-3.8-27b';
export type RecommendationAiModel = GeminiAiModel | GroqAiModel;

export interface RecommendationFilters {
  minimumYear?: number;
  maximumYear?: number;
  originalLanguage?: string;
  originCountries: string[];
  minimumRuntimeMinutes?: number;
  maximumRuntimeMinutes?: number;
  includedGenres: string[];
  excludedGenres: string[];
  minimumTmdbRating?: number;
  productionCompanyIds: number[];
  seriesStatus?: 'returning' | 'ended';
  sortBy?: RecommendationSort;
  excludedTmdbIds: number[];
  excludedTitles: string[];
}

export interface RecommendationAnchor {
  tmdbId: number;
  title: string;
  mediaType: MediaType;
}

export interface RecommendationRequest {
  requestId: string;
  mode: RequestMode;
  aiModel?: RecommendationAiModel;
  /** @deprecated Kept while older Android clients migrate to aiModel. */
  geminiModel?: RecommendationAiModel;
  query: string;
  mediaType: MediaType;
  anchor?: RecommendationAnchor;
  anchors?: RecommendationAnchor[];
  previousQuery?: string;
  refinementQuery?: string;
  filters: RecommendationFilters;
  pageSize: number;
  cursor?: string;
}

export interface ConceptGroup {
  label: string;
  synonyms: string[];
  weight: number;
}

export interface InterpretedIntent {
  hardFilters: RecommendationFilters;
  requiredConceptGroups: ConceptGroup[];
  softConcepts: string[];
  excludedConcepts: string[];
  excludedKeywords: string[];
  crewNames: string[];
  castNames: string[];
  studioNames: string[];
  certifications: string[];
  discoveryProfile?: 'hidden_gems' | 'blockbusters' | 'standard';
  genreHints: string[];
  toneAndMood: string[];
  broadSearchPhrases: string[];
}

export interface DescribeRecommendation {
  title: string;
  releaseYear: number;
  confidence: number;
  reason: string;
}

export interface SimilarAnchorDocument {
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  originalTitle?: string;
  releaseYear?: number;
  overview: string;
  genres: string[];
  keywords: string[];
}

export interface TmdbListItem {
  id: number;
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  overview?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  release_date?: string;
  first_air_date?: string;
  original_language?: string;
  origin_country?: string[];
  genre_ids?: number[];
  vote_average?: number;
  vote_count?: number;
  popularity?: number;
  status?: string;
}

export interface TmdbGenre { id: number; name: string }
export interface TmdbKeyword { id: number; name: string }

export interface Candidate {
  key: string;
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  originalTitle?: string;
  overview?: string;
  posterPath?: string;
  backdropPath?: string;
  releaseDate?: string;
  originalLanguage?: string;
  originCountries: string[];
  genreIds: number[];
  genres: string[];
  runtimeMinutes?: number;
  tmdbRating?: number;
  tmdbVoteCount?: number;
  popularity?: number;
  status?: string;
  collectionId?: number;
  certifications: string[];
  productionCompanyIds: number[];
  keywords: TmdbKeyword[];
  matchedKeywordIds: Set<number>;
  matchedConceptGroupIndexes: Set<number>;
  retrievalSources: Set<string>;
  hardFiltersVerified: boolean;
  detailsLoaded: boolean;
  semanticScore?: number;
  premiseScore?: number;
  premiseMatchedGroupIndexes?: Set<number>;
  premiseReason?: string;
  aiRecommendationConfidence?: number;
  aiRecommendationReason?: string;
  finalScore?: number;
  matchLevel?: MatchLevel;
  matchReasons: string[];
}

export type MatchLevel = 'Exceptional' | 'Strong' | 'Relevant' | 'Broader but still relevant' | 'Reject';

export interface RecommendationResult {
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  originalTitle?: string;
  overview?: string;
  posterPath?: string;
  backdropPath?: string;
  releaseDate?: string;
  genres: string[];
  runtimeMinutes?: number;
  originalLanguage?: string;
  originCountries: string[];
  tmdbRating?: number;
  tmdbVoteCount?: number;
  status?: string;
  matchLevel: Exclude<MatchLevel, 'Reject'>;
  finalScore: number;
  matchReasons: string[];
  retrievalSources: string[];
}

export interface PremiseCandidateDocument {
  index: number;
  title: string;
  originalTitle?: string;
  releaseYear?: number;
  overview: string;
  genres: string[];
  keywords: string[];
  aiReason: string;
  aiConfidence: number;
  retrievalSources?: string[];
}

export interface PremiseAssessment {
  index: number;
  relevanceScore: number;
  matchedGroupIndexes: number[];
  reason: string;
}

export interface RecommendationResponse {
  requestId: string;
  results: RecommendationResult[];
  totalResults: number;
  nextCursor: string | null;
  hasMore: boolean;
}

export interface SecretBindings {
  GEMINI_API_KEY?: string;
  GROQ_API_KEY?: string;
  TMDB_API_KEY?: string;
  TMDB_READ_ACCESS_TOKEN?: string;
  SUBDL_API_KEY?: string;
  CURSOR_SIGNING_SECRET: string;
}

export type RecommendationEnv = Omit<Env, 'GEMINI_GENERATION_MODEL' | 'AI_GENERATION_MODEL'> & {
  GEMINI_GENERATION_MODEL?: GeminiAiModel;
  AI_GENERATION_MODEL?: RecommendationAiModel;
} & SecretBindings;

export class ServiceError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly retryable: boolean,
  ) {
    super(message);
    this.name = 'ServiceError';
  }
}
