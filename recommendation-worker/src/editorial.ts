import { recommendDescribeTitles, recommendSimilarTitles } from './ai';
import type { EngineDependencies } from './engine';
import type { ParsedRecommendationRequest } from './schemas';
import { RecommendationEnv, RecommendationResult, ServiceError, SimilarAnchorDocument } from './types';

// TMDB resolves identity and artwork only. No relevance gates or catalogue reranking.
export async function editorialRecommendations(env: RecommendationEnv, request: ParsedRecommendationRequest,
  tmdb: NonNullable<EngineDependencies['tmdb']>, dependencies: EngineDependencies): Promise<RecommendationResult[]> {
  if (!tmdb.searchTitle) throw new ServiceError('TMDB_UNAVAILABLE', 'Title lookup is unavailable', 503, true);
  const anchors: SimilarAnchorDocument[] = [];
  for (const anchor of request.anchors || (request.anchor ? [request.anchor] : [])) {
    const d = await tmdb.details(anchor.mediaType, anchor.tmdbId);
    anchors.push({ tmdbId: anchor.tmdbId, mediaType: anchor.mediaType,
      title: d.title || d.name || anchor.title, originalTitle: d.original_title || d.original_name,
      releaseYear: Number((d.release_date || d.first_air_date || '').slice(0, 4)) || undefined,
      overview: d.overview || '', genres: (d.genres || []).map(g => g.name), keywords: [] });
  }
  const query = [request.previousQuery, request.query, request.refinementQuery].filter(Boolean).join('\n');
  const excluded = new Set(request.filters.excludedTmdbIds);
  anchors.forEach(a => { if (a.mediaType === request.mediaType) excluded.add(a.tmdbId); });
  const excludedTitles = [...request.filters.excludedTitles, ...anchors.map(a => a.title)];
  const results: RecommendationResult[] = [];
  // A second editorial batch replaces unresolved identities, never verifies or rejects taste.
  for (let pass = 0; pass < 2 && results.length < 20 && tmdb.callsRemaining > 0; pass++) {
    const generated = request.mode === 'similar'
      ? await (dependencies.recommendSimilar || recommendSimilarTitles)(env, anchors, request.mediaType, query, request.filters, excludedTitles, 20)
      : await (dependencies.recommendDescribe || recommendDescribeTitles)(env, query, request.mediaType, request.filters, excludedTitles, 20);
    excludedTitles.push(...generated.map(g => g.title));
    for (let i = 0; i < generated.length && results.length < 20 && tmdb.callsRemaining > 0; i += 4) {
      const batch = generated.slice(i, i + Math.min(4, tmdb.callsRemaining));
      const resolved = await Promise.all(batch.map(async pick => {
        const page = await tmdb.searchTitle!(request.mediaType, pick.title);
        const identity = (value: string) => value.normalize('NFKC').toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
        const matches = page.results.filter(item => [item.title, item.name, item.original_title, item.original_name]
          .some(title => title && identity(title) === identity(pick.title)));
        const item = matches.find(item => (item.release_date || item.first_air_date || '').startsWith(String(pick.releaseYear)))
          || (matches.length === 1 ? matches[0] : undefined);
        if (!item) return undefined;
        return { tmdbId: item.id, mediaType: request.mediaType, title: item.title || item.name || pick.title,
          originalTitle: item.original_title || item.original_name, posterPath: item.poster_path || undefined,
          backdropPath: item.backdrop_path || undefined, releaseDate: item.release_date || item.first_air_date,
          genres: [], originalLanguage: item.original_language, originCountries: item.origin_country || [],
          tmdbRating: item.vote_average, tmdbVoteCount: item.vote_count, matchLevel: 'Strong' as const,
          finalScore: pick.rating ?? 0, matchReasons: [], retrievalSources: [] };
      }));
      for (const item of resolved) if (item && !excluded.has(item.tmdbId) && results.length < 20) {
        excluded.add(item.tmdbId); results.push(item);
      }
    }
  }
  return results.sort((a, b) => b.finalScore - a.finalScore);
}
