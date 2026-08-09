import { Candidate, InterpretedIntent, MatchLevel, RecommendationFilters, RecommendationResult, ServiceError } from './types';

export const normalize = (value: string): string => value.trim().toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const unique = <T>(values: T[]): T[] => [...new Set(values)];

export function mergeFilters(structured: RecommendationFilters, interpreted: RecommendationFilters): RecommendationFilters {
  const mergeScalar = <T>(name: string, left: T | undefined, right: T | undefined): T | undefined => {
    if (left !== undefined && right !== undefined && left !== right) throw new ServiceError('CONTRADICTORY_FILTERS', `Conflicting ${name}`, 400, false);
    return left ?? right;
  };
  const result: RecommendationFilters = {
    minimumYear: Math.max(structured.minimumYear ?? 0, interpreted.minimumYear ?? 0) || undefined,
    maximumYear: Math.min(structured.maximumYear ?? 9999, interpreted.maximumYear ?? 9999) === 9999 ? undefined : Math.min(structured.maximumYear ?? 9999, interpreted.maximumYear ?? 9999),
    originalLanguage: mergeScalar('original language', structured.originalLanguage, interpreted.originalLanguage),
    originCountries: unique([...structured.originCountries, ...interpreted.originCountries].map(v => v.toUpperCase())),
    minimumRuntimeMinutes: Math.max(structured.minimumRuntimeMinutes ?? 0, interpreted.minimumRuntimeMinutes ?? 0) || undefined,
    maximumRuntimeMinutes: Math.min(structured.maximumRuntimeMinutes ?? 9999, interpreted.maximumRuntimeMinutes ?? 9999) === 9999 ? undefined : Math.min(structured.maximumRuntimeMinutes ?? 9999, interpreted.maximumRuntimeMinutes ?? 9999),
    includedGenres: unique([...structured.includedGenres, ...interpreted.includedGenres]),
    excludedGenres: unique([...structured.excludedGenres, ...interpreted.excludedGenres]),
    minimumTmdbRating: Math.max(structured.minimumTmdbRating ?? 0, interpreted.minimumTmdbRating ?? 0) || undefined,
    excludedTmdbIds: unique([...structured.excludedTmdbIds, ...interpreted.excludedTmdbIds]),
    excludedTitles: unique([...structured.excludedTitles, ...interpreted.excludedTitles]),
  };
  if (result.minimumYear && result.maximumYear && result.minimumYear > result.maximumYear) throw new ServiceError('CONTRADICTORY_FILTERS', 'Year filters do not overlap', 400, false);
  if (result.minimumRuntimeMinutes && result.maximumRuntimeMinutes && result.minimumRuntimeMinutes > result.maximumRuntimeMinutes) throw new ServiceError('CONTRADICTORY_FILTERS', 'Runtime filters do not overlap', 400, false);
  const excluded = new Set(result.excludedGenres.map(normalize));
  if (result.includedGenres.some(genre => excluded.has(normalize(genre)))) throw new ServiceError('CONTRADICTORY_FILTERS', 'A genre cannot be both included and excluded', 400, false);
  return result;
}

export function buildKeywordExpressions(groupIds: number[][], max = 8): string[] {
  const cleaned = groupIds.map(ids => unique(ids).slice(0, 4)).filter(ids => ids.length);
  if (!cleaned.length) return [];
  let combinations: number[][] = [[]];
  for (const group of cleaned) combinations = combinations.flatMap(prefix => group.map(id => [...prefix, id])).slice(0, max);
  const expressions = combinations.map(combo => combo.join(','));
  expressions.push(cleaned.map(group => group.join('|')).join(','));
  return unique(expressions).slice(0, max);
}

export function cosineSimilarity(a: number[], b: number[]): number {
  if (a.length !== b.length || !a.length) return 0;
  let dot = 0, aa = 0, bb = 0;
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; aa += a[i] ** 2; bb += b[i] ** 2; }
  return aa && bb ? Math.max(0, Math.min(1, dot / Math.sqrt(aa * bb))) : 0;
}

export function passesHardFilters(candidate: Candidate, filters: RecommendationFilters): boolean {
  const year = candidate.releaseDate ? Number(candidate.releaseDate.slice(0, 4)) : undefined;
  if ((filters.minimumYear || filters.maximumYear) && !year) return false;
  if (filters.minimumYear && year! < filters.minimumYear) return false;
  if (filters.maximumYear && year! > filters.maximumYear) return false;
  if (filters.originalLanguage && candidate.originalLanguage !== filters.originalLanguage) return false;
  if (filters.originCountries.length && !filters.originCountries.some(country => candidate.originCountries.includes(country))) return false;
  if ((filters.minimumRuntimeMinutes || filters.maximumRuntimeMinutes) && candidate.runtimeMinutes === undefined && !candidate.hardFiltersVerified) return false;
  if (filters.minimumRuntimeMinutes && candidate.runtimeMinutes! < filters.minimumRuntimeMinutes) return false;
  if (filters.maximumRuntimeMinutes && candidate.runtimeMinutes! > filters.maximumRuntimeMinutes) return false;
  const genres = new Set(candidate.genres.map(normalize));
  if (filters.includedGenres.length && !filters.includedGenres.every(genre => genres.has(normalize(genre)))) return false;
  if (filters.excludedGenres.some(genre => genres.has(normalize(genre)))) return false;
  if (filters.minimumTmdbRating !== undefined && (candidate.tmdbRating === undefined || candidate.tmdbRating < filters.minimumTmdbRating)) return false;
  if (filters.excludedTmdbIds.includes(candidate.tmdbId)) return false;
  if (filters.excludedTitles.some(title => normalize(title) === normalize(candidate.title) || normalize(title) === normalize(candidate.originalTitle || ''))) return false;
  return true;
}

function evidence(candidate: Candidate, intent: InterpretedIntent) {
  const haystack = normalize([candidate.title, candidate.originalTitle, candidate.overview, ...candidate.genres, ...candidate.keywords.map(k => k.name)].filter(Boolean).join(' '));
  const groupMatches = intent.requiredConceptGroups.map(group => group.synonyms.some(synonym => haystack.includes(normalize(synonym))));
  const concept = groupMatches.length ? groupMatches.filter(Boolean).length / groupMatches.length : 1;
  const keyword = candidate.matchedKeywordIds.size ? Math.min(1, candidate.matchedKeywordIds.size / Math.max(1, intent.requiredConceptGroups.length)) : concept * 0.4;
  const genreHints = unique([...intent.genreHints, ...intent.hardFilters.includedGenres]);
  const genre = genreHints.length ? genreHints.filter(hint => candidate.genres.some(g => normalize(g) === normalize(hint))).length / genreHints.length : 1;
  const path = Math.min(1, candidate.retrievalSources.size / 3);
  const quality = Math.min(1, Math.log10((candidate.tmdbVoteCount ?? 0) + 1) / 4) * Math.min(1, (candidate.tmdbRating ?? 0) / 7.5);
  return { concept, keyword, genre, path, quality };
}

export function rankCandidates(candidates: Candidate[], intent: InterpretedIntent, filters: RecommendationFilters, similar: boolean, embeddingsAvailable: boolean): RecommendationResult[] {
  for (const candidate of candidates) {
    if (!passesHardFilters(candidate, filters)) { candidate.matchLevel = 'Reject'; continue; }
    const e = evidence(candidate, intent);
    const signals = similar
      ? { semantic: candidate.semanticScore ?? 0, direct: candidate.directRelationshipScore, overlap: candidate.anchorOverlapScore, concept: e.concept, path: e.path, quality: e.quality }
      : { semantic: candidate.semanticScore ?? 0, concept: e.concept, keyword: e.keyword, genre: e.genre, path: e.path, quality: e.quality };
    const weights = similar
      ? { semantic: .30, direct: .28, overlap: .20, concept: .08, path: .11, quality: .03 }
      : { semantic: .42, concept: .23, keyword: .15, genre: .08, path: .09, quality: .03 };
    if (!embeddingsAvailable) delete (weights as Partial<typeof weights>).semantic;
    const denominator = Object.values(weights).reduce((sum, weight) => sum + weight, 0);
    candidate.finalScore = Object.entries(weights).reduce((sum, [name, weight]) => sum + (signals[name as keyof typeof signals] ?? 0) * weight, 0) / denominator;
    const requiredEvidence = intent.requiredConceptGroups.length ? e.concept : Math.max(e.genre, e.path);
    const reject = candidate.finalScore < .24 || (requiredEvidence === 0 && candidate.directRelationshipScore === 0);
    const level: MatchLevel = reject ? 'Reject' : candidate.finalScore >= .78 ? 'Exceptional' : candidate.finalScore >= .62 ? 'Strong' : candidate.finalScore >= .44 ? 'Relevant' : 'Broader but still relevant';
    candidate.matchLevel = level;
    candidate.matchReasons = [
      candidate.directRelationshipScore ? 'Recommended or marked similar by TMDB' : '',
      e.concept >= .67 ? 'Strong concept match' : e.concept > 0 ? 'Partial concept match' : '',
      e.keyword > .4 ? 'TMDB keyword evidence' : '',
      e.genre > .4 ? 'Genre match' : '',
    ].filter(Boolean);
  }
  return candidates.filter(c => c.matchLevel !== 'Reject').sort((a, b) => (b.finalScore! - a.finalScore!) || a.key.localeCompare(b.key)).map(candidate => ({
    tmdbId: candidate.tmdbId, mediaType: candidate.mediaType, title: candidate.title, originalTitle: candidate.originalTitle,
    overview: candidate.overview, posterPath: candidate.posterPath, backdropPath: candidate.backdropPath, releaseDate: candidate.releaseDate,
    genres: candidate.genres, runtimeMinutes: candidate.runtimeMinutes, originalLanguage: candidate.originalLanguage,
    originCountries: candidate.originCountries, tmdbRating: candidate.tmdbRating, tmdbVoteCount: candidate.tmdbVoteCount,
    matchLevel: candidate.matchLevel as Exclude<MatchLevel, 'Reject'>, finalScore: Number(candidate.finalScore!.toFixed(6)),
    matchReasons: candidate.matchReasons, retrievalSources: [...candidate.retrievalSources].sort(),
  }));
}
