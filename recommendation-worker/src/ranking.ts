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
  // Titles are identifiers, not topical evidence. A title such as "The Case" must
  // never satisfy a request for a cold case unless its metadata supports that idea.
  const contentHaystack = normalize([candidate.overview, ...candidate.genres, ...candidate.keywords.map(k => k.name)].filter(Boolean).join(' '));
  const keywordHaystack = normalize(candidate.keywords.map(keyword => keyword.name).join(' '));
  const containsConcept = (haystack: string, value: string): boolean => {
    const normalizedValue = normalize(value);
    return normalizedValue.length > 1 && ` ${haystack} `.includes(` ${normalizedValue} `);
  };
  const groupMatches = intent.requiredConceptGroups.map((group, index) => ({
    matched: group.synonyms.some(value => containsConcept(contentHaystack, value)) || candidate.matchedConceptGroupIndexes.has(index),
    grounded: group.synonyms.some(value => containsConcept(keywordHaystack, value)) || candidate.matchedConceptGroupIndexes.has(index),
    weight: Math.max(.1, group.weight),
  }));
  const matchedGroupCount = groupMatches.filter(group => group.matched).length;
  const groundedGroupCount = groupMatches.filter(group => group.grounded).length;
  const totalConceptWeight = groupMatches.reduce((sum, group) => sum + group.weight, 0);
  const concept = totalConceptWeight
    ? groupMatches.reduce((sum, group) => sum + (group.matched ? group.weight : 0), 0) / totalConceptWeight
    : 0;
  const groundedGroupCoverage = totalConceptWeight
    ? groupMatches.reduce((sum, group) => sum + (group.grounded ? group.weight : 0), 0) / totalConceptWeight
    : 0;
  const keyword = groupMatches.length
    ? groundedGroupCoverage
    : candidate.matchedKeywordIds.size ? Math.min(1, candidate.matchedKeywordIds.size / 3) : 0;
  const genreHints = unique([...intent.genreHints, ...intent.hardFilters.includedGenres]);
  const genre = genreHints.length ? genreHints.filter(hint => candidate.genres.some(g => normalize(g) === normalize(hint))).length / genreHints.length : 0;
  const retrievalFamilies = new Set([...candidate.retrievalSources].map(source => source.replace(/:page-\d+$/, '')));
  const path = Math.min(1, retrievalFamilies.size / 3);
  const quality = Math.min(1, Math.log10((candidate.tmdbVoteCount ?? 0) + 1) / 4) * Math.min(1, (candidate.tmdbRating ?? 0) / 7.5);
  const excluded = intent.excludedConcepts.some(value => containsConcept(contentHaystack, value));
  const requestedCertifications = new Set(intent.certifications.map(normalize));
  const certification = requestedCertifications.size
    ? candidate.certifications.some(value => requestedCertifications.has(normalize(value))) ? 1 : 0
    : 0;
  return {
    concept,
    keyword,
    genre,
    path,
    quality,
    excluded,
    conceptRequested: groupMatches.length > 0,
    totalGroupCount: groupMatches.length,
    matchedGroupCount,
    groundedGroupCount,
    genreRequested: genreHints.length > 0,
    keywordGrounded: groundedGroupCount > 0,
    certification,
    certificationRequested: requestedCertifications.size > 0,
  };
}

function hasRequiredConceptCoverage(total: number, matched: number, weightedCoverage: number): boolean {
  if (!total) return true;
  const minimumMatches = total <= 2 ? total : Math.ceil(total * .66);
  return matched >= minimumMatches && weightedCoverage >= .64;
}

export function rankCandidates(candidates: Candidate[], intent: InterpretedIntent, filters: RecommendationFilters, similar: boolean, embeddingsAvailable: boolean): RecommendationResult[] {
  for (const candidate of candidates) {
    if (!passesHardFilters(candidate, filters)) { candidate.matchLevel = 'Reject'; continue; }
    const e = evidence(candidate, intent);
    const semantic = candidate.semanticScore ?? 0;
    const conceptCoveragePasses = hasRequiredConceptCoverage(e.totalGroupCount, e.matchedGroupCount, e.concept);
    const anchorSupport = !candidate.anchorEvidenceAvailable || candidate.sharedAnchorKeywordCount > 0 ||
      candidate.anchorGenreScore >= .25 || semantic >= .50;
    const directSimilarity = candidate.directRelationshipScore >= .50 && anchorSupport;
    const groundedSimilarity = candidate.anchorOverlapScore >= .42 && (
      candidate.sharedAnchorKeywordCount >= 2 || semantic >= .50 ||
      (candidate.sharedAnchorKeywordCount >= 1 && candidate.anchorGenreScore >= .25 && candidate.anchorOverlapScore >= .72)
    );
    const hybridSimilarity = candidate.sharedAnchorKeywordCount >= 1 && candidate.anchorGenreScore >= .25 && semantic >= .52;
    const similarityEvidencePasses = directSimilarity || groundedSimilarity || hybridSimilarity;
    const signals: Record<string, number> = similar
      ? { semantic: candidate.semanticScore ?? 0, direct: candidate.directRelationshipScore, overlap: candidate.anchorOverlapScore, concept: e.concept, path: e.path, quality: e.quality }
      : { semantic: candidate.semanticScore ?? 0, concept: e.concept, keyword: e.keyword, genre: e.genre, path: e.path, quality: e.quality };
    const weights: Record<string, number> = similar
      ? { semantic: .22, direct: .38, overlap: .28, concept: .02, path: .05, quality: .05 }
      : { semantic: .25, concept: .38, keyword: .18, genre: .08, path: .06, quality: .05 };
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
    const noConceptEvidence = !e.conceptRequested && !e.genreRequested && e.path === 0 && semantic === 0;
    const reject = e.excluded || (e.certificationRequested && e.certification === 0) ||
      (similar ? !similarityEvidencePasses : !conceptCoveragePasses) ||
      (!similar && !e.conceptRequested && e.genreRequested && e.genre === 0) ||
      (!similar && noConceptEvidence) || candidate.finalScore < .28;
    const level: MatchLevel = reject ? 'Reject' : candidate.finalScore >= .78 ? 'Exceptional' : candidate.finalScore >= .62 ? 'Strong' : candidate.finalScore >= .44 ? 'Relevant' : 'Broader but still relevant';
    candidate.matchLevel = level;
    candidate.matchReasons = [
      candidate.directRelationshipScore ? 'Recommended or marked similar by TMDB' : '',
      e.conceptRequested && e.concept >= .99 ? 'Matches every requested concept' : e.conceptRequested && conceptCoveragePasses ? 'Matches most requested concepts' : '',
      (candidate.semanticScore ?? 0) >= .72 ? 'Strong meaning and story match' : (candidate.semanticScore ?? 0) >= .56 ? 'Good story and theme match' : '',
      e.groundedGroupCount >= 2 ? 'Grounded in multiple TMDB keyword concepts' : e.keywordGrounded ? 'Grounded in TMDB keyword data' : '',
      similar && candidate.sharedAnchorKeywordCount >= 2 ? 'Shares several specific themes with the selected title' : '',
      e.genreRequested && e.genre > .4 ? 'Strong genre fit' : '',
      e.path >= .67 ? 'Confirmed by multiple TMDB discovery paths' : '',
      e.certificationRequested && e.certification ? 'Matches the requested age rating' : '',
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

  const collectionCounts = new Map<number, number>();
  const franchiseDeduplicated: Candidate[] = [];
  for (const candidate of sorted) {
    if (candidate.collectionId) {
      const count = collectionCounts.get(candidate.collectionId) || 0;
      if (count >= 2) continue;
      collectionCounts.set(candidate.collectionId, count + 1);
    }
    franchiseDeduplicated.push(candidate);
  }

  return franchiseDeduplicated.map(candidate => ({
    tmdbId: candidate.tmdbId, mediaType: candidate.mediaType, title: candidate.title, originalTitle: candidate.originalTitle,
    overview: candidate.overview, posterPath: candidate.posterPath, backdropPath: candidate.backdropPath, releaseDate: candidate.releaseDate,
    genres: candidate.genres, runtimeMinutes: candidate.runtimeMinutes, originalLanguage: candidate.originalLanguage,
    originCountries: candidate.originCountries, tmdbRating: candidate.tmdbRating, tmdbVoteCount: candidate.tmdbVoteCount,
    matchLevel: candidate.matchLevel as Exclude<MatchLevel, 'Reject'>, finalScore: Number(candidate.finalScore!.toFixed(6)),
    matchReasons: candidate.matchReasons, retrievalSources: [...candidate.retrievalSources].sort(),
  }));
}
