import { Candidate, InterpretedIntent, MatchLevel, RecommendationFilters, RecommendationResult, ServiceError } from './types';

export const normalize = (value: string): string => value.trim().toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const unique = <T>(values: T[]): T[] => [...new Set(values)];

export function mergeFilters(structured: RecommendationFilters, interpreted: RecommendationFilters): RecommendationFilters {
  const structuredYear = structured.minimumYear !== undefined || structured.maximumYear !== undefined;
  const structuredRuntime = structured.minimumRuntimeMinutes !== undefined || structured.maximumRuntimeMinutes !== undefined;
  const structuredIncluded = new Set(structured.includedGenres.map(normalize));
  const structuredExcluded = new Set(structured.excludedGenres.map(normalize));
  const result: RecommendationFilters = {
    minimumYear: structuredYear ? structured.minimumYear : interpreted.minimumYear,
    maximumYear: structuredYear ? structured.maximumYear : interpreted.maximumYear,
    originalLanguage: structured.originalLanguage ?? interpreted.originalLanguage,
    originCountries: unique((structured.originCountries.length ? structured.originCountries : interpreted.originCountries).map(v => v.toUpperCase())),
    minimumRuntimeMinutes: structuredRuntime ? structured.minimumRuntimeMinutes : interpreted.minimumRuntimeMinutes,
    maximumRuntimeMinutes: structuredRuntime ? structured.maximumRuntimeMinutes : interpreted.maximumRuntimeMinutes,
    includedGenres: unique(structured.includedGenres.length
      ? structured.includedGenres
      : interpreted.includedGenres.filter(genre => !structuredExcluded.has(normalize(genre)))),
    excludedGenres: unique(structured.excludedGenres.length
      ? structured.excludedGenres
      : interpreted.excludedGenres.filter(genre => !structuredIncluded.has(normalize(genre)))),
    minimumTmdbRating: structured.minimumTmdbRating ?? interpreted.minimumTmdbRating,
    excludedTmdbIds: unique(structured.excludedTmdbIds.length ? structured.excludedTmdbIds : interpreted.excludedTmdbIds),
    excludedTitles: unique(structured.excludedTitles.length ? structured.excludedTitles : interpreted.excludedTitles),
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
  const parsedYear = candidate.releaseDate ? Number(candidate.releaseDate.slice(0, 4)) : undefined;
  const year = Number.isInteger(parsedYear) ? parsedYear : undefined;
  if ((filters.minimumYear || filters.maximumYear) && !year) return false;
  if (filters.minimumYear && year! < filters.minimumYear) return false;
  if (filters.maximumYear && year! > filters.maximumYear) return false;
  if (filters.originalLanguage && candidate.originalLanguage !== filters.originalLanguage) return false;
  if (filters.originCountries.length && !filters.originCountries.some(country => candidate.originCountries.includes(country))) return false;
  if ((filters.minimumRuntimeMinutes !== undefined || filters.maximumRuntimeMinutes !== undefined) && candidate.runtimeMinutes === undefined) return false;
  if (filters.minimumRuntimeMinutes && candidate.runtimeMinutes! < filters.minimumRuntimeMinutes) return false;
  if (filters.maximumRuntimeMinutes && candidate.runtimeMinutes! > filters.maximumRuntimeMinutes) return false;
  const genres = new Set(candidate.genres.map(normalize));
  if ((filters.includedGenres.length || filters.excludedGenres.length) && !genres.size) return false;
  if (filters.includedGenres.length && !filters.includedGenres.every(genre => genres.has(normalize(genre)))) return false;
  if (filters.excludedGenres.some(genre => genres.has(normalize(genre)))) return false;
  if (filters.minimumTmdbRating !== undefined && (candidate.tmdbRating === undefined || candidate.tmdbRating < filters.minimumTmdbRating)) return false;
  if (filters.excludedTmdbIds.includes(candidate.tmdbId)) return false;
  if (filters.excludedTitles.some(title => normalize(title) === normalize(candidate.title) || normalize(title) === normalize(candidate.originalTitle || ''))) return false;
  return true;
}

function evidence(candidate: Candidate, intent: InterpretedIntent) {
  const haystack = normalize([candidate.title, candidate.originalTitle, candidate.overview, ...candidate.genres, ...candidate.keywords.map(k => k.name)].filter(Boolean).join(' '));
  const containsConcept = (value: string): boolean => {
    const normalizedValue = normalize(value);
    return normalizedValue.length > 1 && ` ${haystack} `.includes(` ${normalizedValue} `);
  };
  const groupMatches = intent.requiredConceptGroups.map((group, index) =>
    group.synonyms.some(containsConcept) || candidate.matchedConceptGroupIndexes.has(index),
  );
  const matchingGroupCount = groupMatches.filter(Boolean).length;
  const concept = groupMatches.length ? matchingGroupCount / groupMatches.length : 0;
  const groundedGroupCoverage = groupMatches.length ? matchingGroupCount / groupMatches.length : 0;
  const keyword = groupMatches.length
    ? (concept >= 0.5 ? concept : concept * 0.5)
    : candidate.matchedKeywordIds.size ? Math.min(1, candidate.matchedKeywordIds.size / 3) : 0;
  const genreHints = unique([...intent.genreHints, ...intent.hardFilters.includedGenres]);
  const genre = genreHints.length ? genreHints.filter(hint => candidate.genres.some(g => normalize(g) === normalize(hint))).length / genreHints.length : 0;
  const path = Math.min(1, candidate.retrievalSources.size / 3);
  const quality = Math.min(1, Math.log10((candidate.tmdbVoteCount ?? 0) + 1) / 4) * Math.min(1, (candidate.tmdbRating ?? 0) / 7.5);
  const excluded = intent.excludedConcepts.some(containsConcept);
  return {
    concept,
    keyword,
    genre,
    path,
    quality,
    excluded,
    conceptRequested: groupMatches.length > 0,
    genreRequested: genreHints.length > 0,
    keywordGrounded: groundedGroupCoverage > 0,
  };
}

export function rankCandidates(candidates: Candidate[], intent: InterpretedIntent, filters: RecommendationFilters, similar: boolean, embeddingsAvailable: boolean): RecommendationResult[] {
  for (const candidate of candidates) {
    if (!passesHardFilters(candidate, filters)) { candidate.matchLevel = 'Reject'; continue; }
    const e = evidence(candidate, intent);
    const signals: Record<string, number> = similar
      ? { semantic: candidate.semanticScore ?? 0, direct: candidate.directRelationshipScore, overlap: candidate.anchorOverlapScore, concept: e.concept, path: e.path, quality: e.quality }
      : { semantic: candidate.semanticScore ?? 0, concept: e.concept, keyword: e.keyword, genre: e.genre, path: e.path, quality: e.quality };
    const weights: Record<string, number> = similar
      ? { semantic: .30, direct: .28, overlap: .20, concept: .08, path: .11, quality: .03 }
      : { semantic: .42, concept: .23, keyword: .15, genre: .08, path: .09, quality: .03 };
    if (!embeddingsAvailable || candidate.semanticScore === undefined) delete weights.semantic;
    if (!e.conceptRequested) {
      delete weights.concept;
      delete weights.keyword;
    }
    if (!similar && !e.genreRequested) delete weights.genre;
    const denominator = Object.values(weights).reduce((sum, weight) => sum + weight, 0);
    let rawScore = Object.entries(weights).reduce((sum, [name, weight]) => sum + (signals[name] ?? 0) * weight, 0) / denominator;
    if (!similar && e.genreRequested && e.genre === 0) {
      rawScore *= 0.65;
    }
    candidate.finalScore = rawScore;
    const requiredEvidence = intent.requiredConceptGroups.length
      ? Math.max(e.concept, e.keyword, (candidate.semanticScore ?? 0) * .85)
      : Math.max(e.genre, e.path, (candidate.semanticScore ?? 0) * .85);
    const reject = e.excluded || candidate.finalScore < .24 ||
      (requiredEvidence < .18 && candidate.directRelationshipScore === 0);
    const level: MatchLevel = reject ? 'Reject' : candidate.finalScore >= .78 ? 'Exceptional' : candidate.finalScore >= .62 ? 'Strong' : candidate.finalScore >= .44 ? 'Relevant' : 'Broader but still relevant';
    candidate.matchLevel = level;
    candidate.matchReasons = [
      candidate.directRelationshipScore ? 'Recommended or marked similar by TMDB' : '',
      e.conceptRequested && e.concept >= .99 ? 'Matches every requested concept' : e.conceptRequested && e.concept >= .5 ? 'Matches most requested concepts' : e.conceptRequested && e.concept > 0 ? 'Matches part of your request' : '',
      (candidate.semanticScore ?? 0) >= .72 ? 'Strong meaning and story match' : (candidate.semanticScore ?? 0) >= .56 ? 'Good story and theme match' : '',
      e.keywordGrounded ? 'Grounded in TMDB keyword data' : '',
      e.genreRequested && e.genre > .4 ? 'Strong genre fit' : '',
      e.path >= .67 ? 'Confirmed by multiple TMDB discovery paths' : '',
    ].filter(Boolean);
    if (!candidate.matchReasons.length) {
      const hasHardFilters = filters.minimumYear !== undefined || filters.maximumYear !== undefined ||
        filters.originalLanguage !== undefined || filters.originCountries.length > 0 ||
        filters.minimumRuntimeMinutes !== undefined || filters.maximumRuntimeMinutes !== undefined ||
        filters.includedGenres.length > 0 || filters.excludedGenres.length > 0 ||
        filters.minimumTmdbRating !== undefined;
      candidate.matchReasons.push(
        similar ? 'Related through TMDB similarity data'
          : hasHardFilters ? 'Matches your selected filters'
            : 'Relevant based on TMDB metadata',
      );
    }
  }
  const sorted = candidates
    .filter(candidate => candidate.matchLevel !== 'Reject')
    .sort((a, b) => (b.finalScore! - a.finalScore!) || a.key.localeCompare(b.key));

  const seenCollections = new Set<number>();
  const franchiseDeduplicated: Candidate[] = [];
  for (const candidate of sorted) {
    if (candidate.collectionId) {
      if (seenCollections.has(candidate.collectionId)) continue;
      seenCollections.add(candidate.collectionId);
    }
    franchiseDeduplicated.push(candidate);
  }

  return diversify(franchiseDeduplicated).map(candidate => ({
    tmdbId: candidate.tmdbId, mediaType: candidate.mediaType, title: candidate.title, originalTitle: candidate.originalTitle,
    overview: candidate.overview, posterPath: candidate.posterPath, backdropPath: candidate.backdropPath, releaseDate: candidate.releaseDate,
    genres: candidate.genres, runtimeMinutes: candidate.runtimeMinutes, originalLanguage: candidate.originalLanguage,
    originCountries: candidate.originCountries, tmdbRating: candidate.tmdbRating, tmdbVoteCount: candidate.tmdbVoteCount,
    matchLevel: candidate.matchLevel as Exclude<MatchLevel, 'Reject'>, finalScore: Number(candidate.finalScore!.toFixed(6)),
    matchReasons: candidate.matchReasons, retrievalSources: [...candidate.retrievalSources].sort(),
  }));
}

function diversify(sorted: Candidate[]): Candidate[] {
  if (sorted.length < 3) return sorted;
  const scoreFloor = Math.max(.24, (sorted[0].finalScore ?? 0) - .16);
  const firstBelowFloor = sorted.findIndex(candidate => (candidate.finalScore ?? 0) < scoreFloor);
  const poolSize = Math.min(
    80,
    firstBelowFloor === -1 ? sorted.length : firstBelowFloor,
  );
  if (poolSize < 3) return sorted;

  const relevant = sorted.slice(0, poolSize);
  const selected = [relevant[0]];
  const selectedKeys = new Set([relevant[0].key]);
  while (selected.length < relevant.length) {
    let best: Candidate | undefined;
    let bestMmr = Number.NEGATIVE_INFINITY;
    for (const candidate of relevant) {
      if (selectedKeys.has(candidate.key)) continue;
      const maximumSimilarity = Math.max(...selected.map(chosen => candidateSimilarity(candidate, chosen)));
      const mmr = .88 * (candidate.finalScore ?? 0) - .12 * maximumSimilarity;
      if (mmr > bestMmr || (mmr === bestMmr && candidate.key.localeCompare(best?.key ?? '') < 0)) {
        best = candidate;
        bestMmr = mmr;
      }
    }
    if (!best) break;
    selected.push(best);
    selectedKeys.add(best.key);
  }
  return [...selected, ...sorted.filter(candidate => !selectedKeys.has(candidate.key))];
}

function candidateSimilarity(left: Candidate, right: Candidate): number {
  const leftGenres = new Set(left.genres.map(normalize));
  const rightGenres = new Set(right.genres.map(normalize));
  const genreUnion = new Set([...leftGenres, ...rightGenres]);
  const genreOverlap = genreUnion.size
    ? [...leftGenres].filter(genre => rightGenres.has(genre)).length / genreUnion.size
    : 0;
  const leftYear = Number(left.releaseDate?.slice(0, 4));
  const rightYear = Number(right.releaseDate?.slice(0, 4));
  const sameEra = Number.isFinite(leftYear) && Number.isFinite(rightYear)
    ? Math.max(0, 1 - Math.abs(leftYear - rightYear) / 24)
    : 0;
  const sameLanguage = left.originalLanguage && right.originalLanguage &&
    left.originalLanguage === right.originalLanguage ? 1 : 0;
  return genreOverlap * .68 + sameEra * .20 + sameLanguage * .12;
}
