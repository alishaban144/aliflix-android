import { Env, callGemini, callGeminiEmbeddingsBatch } from './gemini';
import { V3RecommendationRequestSchema, GeminiIntentSchema, GeminiExpansionSchema } from './schemas';
import { INTERPRET_V3_PROMPT, EXPAND_V3_PROMPT } from './prompts';
import * as tmdb from './tmdb';

function cosineSimilarity(a: number[], b: number[]): number {
  let dotProduct = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < a.length; i++) {
    dotProduct += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  if (normA === 0 || normB === 0) return 0;
  return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
}

function processHardFilters(cand: any, filters: any, mediaType: string) {
  if (cand.media_type && cand.media_type !== mediaType) return false;
  
  const year = cand.release_date ? parseInt(cand.release_date.split('-')[0]) : 
               cand.first_air_date ? parseInt(cand.first_air_date.split('-')[0]) : null;
               
  if (filters.minimumYear && year && year < filters.minimumYear) return false;
  if (filters.maximumYear && year && year > filters.maximumYear) return false;
  
  if (filters.originalLanguage && cand.original_language !== filters.originalLanguage) return false;
  if (filters.originCountry && cand.origin_country && !cand.origin_country.includes(filters.originCountry)) return false;
  
  return true;
}

export async function processRecommendation(env: Env, requestBody: any) {
  const req = V3RecommendationRequestSchema.parse(requestBody);
  const mediaType = req.mediaType || 'movie';

  // 1. Interpret Intent
  const intent = await callGemini(env, INTERPRET_V3_PROMPT, { query: req.query, mediaType }, GeminiIntentSchema);

  // Diagnostics
  const diagnostics: any = {
    tmdbApiCalls: 0,
    tmdbSearchCalls: 0,
    tmdbKeywordCalls: 0,
    tmdbDiscoverCalls: 0,
    tmdbRecommendationCalls: 0,
    tmdbSimilarCalls: 0,
    tmdbDetailCalls: 0,
    expansionRounds: 0,
    resolvedKeywordIds: []
  };

  const wrapTmdb = async <T>(apiCall: Promise<T>, counterKey: string) => {
    diagnostics.tmdbApiCalls++;
    diagnostics[counterKey]++;
    return await apiCall;
  };

  let rawCandidates = new Map<number, any>();

  // Helper to add candidates
  const addCandidates = (results: any[], source: string, baseScore: number) => {
    for (const res of results) {
      if (!res.id) continue;
      if (!processHardFilters(res, intent.hardFilters, intent.mediaType)) continue;

      if (!rawCandidates.has(res.id)) {
        rawCandidates.set(res.id, {
          ...res,
          retrievalSources: new Set([source]),
          preliminaryScore: baseScore,
          popularityScore: res.popularity || 0,
          voteAverage: res.vote_average || 0
        });
      } else {
        const existing = rawCandidates.get(res.id);
        existing.retrievalSources.add(source);
        existing.preliminaryScore += (baseScore * 0.5); // Boost score if found in multiple paths
      }
    }
  };

  if (intent.intentType === 'similar_to' && intent.anchorTitle) {
    // SIMILAR TO PIPELINE
    const searchRes = await wrapTmdb(mediaType === 'movie' ? tmdb.searchMovie(env, intent.anchorTitle) : tmdb.searchTv(env, intent.anchorTitle), 'tmdbSearchCalls');
    if (searchRes.results && searchRes.results.length > 0) {
      const anchorId = searchRes.results[0].id;
      
      const [recs, similar, details] = await Promise.all([
        wrapTmdb(tmdb.getRecommendations(env, mediaType, anchorId), 'tmdbRecommendationCalls'),
        wrapTmdb(tmdb.getSimilar(env, mediaType, anchorId), 'tmdbSimilarCalls'),
        wrapTmdb(tmdb.getDetails(env, mediaType, anchorId), 'tmdbDetailCalls')
      ]);

      if (recs.results) addCandidates(recs.results, 'tmdb_recommendations', 10);
      if (similar.results) addCandidates(similar.results, 'tmdb_similar', 8);

      // We can also fetch keywords of the anchor and do a discover
      const keywordsRes = await wrapTmdb(tmdb.getKeywords(env, mediaType, anchorId), 'tmdbKeywordCalls');
      const keywordIds = keywordsRes.keywords?.map((k: any) => k.id) || keywordsRes.results?.map((k: any) => k.id) || [];
      
      if (keywordIds.length > 0) {
        const discRes = await wrapTmdb(mediaType === 'movie' ? 
          tmdb.discoverMovie(env, { with_keywords: keywordIds.slice(0, 3).join('|') }) : 
          tmdb.discoverTv(env, { with_keywords: keywordIds.slice(0, 3).join('|') }), 'tmdbDiscoverCalls');
        if (discRes.results) addCandidates(discRes.results, 'anchor_keywords', 6);
      }
      
      // Exclude anchor itself
      rawCandidates.delete(anchorId);
    }
  } else {
    // NORMAL DISCOVERY PIPELINE
    
    // Resolve keywords
    let keywordIds: number[] = [];
    const searchPhrases = [...(intent.broadSearchPhrases || []), ...(intent.requiredConceptGroups?.flatMap((g: any) => g.synonyms) || [])];
    
    // Bounded keyword searches
    for (const phrase of searchPhrases.slice(0, 5)) {
      const kwRes = await wrapTmdb(tmdb.searchKeyword(env, phrase), 'tmdbKeywordCalls');
      if (kwRes.results && kwRes.results.length > 0) {
        keywordIds.push(kwRes.results[0].id);
        diagnostics.resolvedKeywordIds.push(kwRes.results[0].id);
      }
    }

    // Pass A: STRICT CONCEPT COMBINATIONS (AND)
    if (keywordIds.length > 1) {
      const andKws = keywordIds.slice(0, 3).join(',');
      const discRes = await wrapTmdb(mediaType === 'movie' ? tmdb.discoverMovie(env, { with_keywords: andKws }) : tmdb.discoverTv(env, { with_keywords: andKws }), 'tmdbDiscoverCalls');
      if (discRes.results) addCandidates(discRes.results, 'keyword_intersection', 10);
    }

    // Pass B & C: INDIVIDUAL OR COMBINED (OR)
    if (keywordIds.length > 0) {
      const orKws = keywordIds.join('|');
      const discRes = await wrapTmdb(mediaType === 'movie' ? tmdb.discoverMovie(env, { with_keywords: orKws }) : tmdb.discoverTv(env, { with_keywords: orKws }), 'tmdbDiscoverCalls');
      if (discRes.results) addCandidates(discRes.results, 'keyword_union', 6);
    }

    // Pass D: STRUCTURED DISCOVER (just hard filters)
    if (Object.keys(intent.hardFilters).length > 0) {
      const params: any = {};
      if (intent.hardFilters.originalLanguage) params.with_original_language = intent.hardFilters.originalLanguage;
      if (intent.hardFilters.minimumYear) {
         if (mediaType === 'movie') params['primary_release_date.gte'] = `${intent.hardFilters.minimumYear}-01-01`;
         else params['first_air_date.gte'] = `${intent.hardFilters.minimumYear}-01-01`;
      }
      const discRes = await wrapTmdb(mediaType === 'movie' ? tmdb.discoverMovie(env, params) : tmdb.discoverTv(env, params), 'tmdbDiscoverCalls');
      if (discRes.results) addCandidates(discRes.results, 'hard_filters_only', 4);
    }

    // Pass E: GEMINI SEARCH EXPANSION if candidates < 30
    if (rawCandidates.size < 30) {
      diagnostics.expansionRounds++;
      const expansion = await callGemini(env, EXPAND_V3_PROMPT, { originalQuery: req.query, triedPhrases: searchPhrases }, GeminiExpansionSchema);
      
      let expKeywordIds: number[] = [];
      for (const phrase of (expansion.newSearchPhrases || []).slice(0, 3)) {
        const kwRes = await wrapTmdb(tmdb.searchKeyword(env, phrase), 'tmdbKeywordCalls');
        if (kwRes.results && kwRes.results.length > 0) expKeywordIds.push(kwRes.results[0].id);
      }
      
      if (expKeywordIds.length > 0) {
        const orKws = expKeywordIds.join('|');
        const discRes = await wrapTmdb(mediaType === 'movie' ? tmdb.discoverMovie(env, { with_keywords: orKws }) : tmdb.discoverTv(env, { with_keywords: orKws }), 'tmdbDiscoverCalls');
        if (discRes.results) addCandidates(discRes.results, 'expansion_union', 5);
      }
    }
  }

  diagnostics.rawCandidateCount = rawCandidates.size;
  diagnostics.deduplicatedCandidateCount = rawCandidates.size;

  // PRELIMINARY RANKING (cheap)
  let candidateList = Array.from(rawCandidates.values());
  candidateList.sort((a, b) => b.preliminaryScore - a.preliminaryScore);
  
  // Take top 80 for enrichment
  candidateList = candidateList.slice(0, 80);
  diagnostics.preliminaryPoolCount = candidateList.length;

  // TMDB ENRICHMENT (Concurrency bounded)
  const enrichedCandidates: any[] = [];
  const chunkSize = 5;
  for (let i = 0; i < candidateList.length; i += chunkSize) {
    const chunk = candidateList.slice(i, i + chunkSize);
    const enrichedChunk = await Promise.all(chunk.map(async cand => {
      try {
        const [details, keywordsRes] = await Promise.all([
          wrapTmdb(tmdb.getDetails(env, mediaType, cand.id), 'tmdbDetailCalls'),
          wrapTmdb(tmdb.getKeywords(env, mediaType, cand.id), 'tmdbKeywordCalls')
        ]);
        
        cand.details = details;
        cand.keywords = keywordsRes.keywords || keywordsRes.results || [];
        
        // Build semantic document
        const genresStr = details.genres?.map((g: any) => g.name).join(', ') || '';
        const kwsStr = cand.keywords.map((k: any) => k.name).join(', ');
        cand.semanticDocument = `Title: ${details.title || details.name}\nOverview: ${details.overview}\nGenres: ${genresStr}\nKeywords: ${kwsStr}\nTagline: ${details.tagline || ''}\nOriginal language: ${details.original_language}`;
        
        return cand;
      } catch (e) {
        // Fallback to basic if details fail
        cand.semanticDocument = `Title: ${cand.title || cand.name}\nOverview: ${cand.overview}`;
        return cand;
      }
    }));
    enrichedCandidates.push(...enrichedChunk);
  }

  diagnostics.enrichedCandidateCount = enrichedCandidates.length;

  // GEMINI EMBEDDINGS
  let embeddingSuccess = false;
  try {
    const queryDoc = `Query: ${req.query}\nIntent: ${intent.intentType}\nConcepts: ${JSON.stringify(intent.requiredConceptGroups)}`;
    const textsToEmbed = [queryDoc, ...enrichedCandidates.map(c => c.semanticDocument)];
    
    // Batch embeddings
    const embeddings = await callGeminiEmbeddingsBatch(env, textsToEmbed);
    const queryEmbedding = embeddings[0];
    
    for (let i = 0; i < enrichedCandidates.length; i++) {
      enrichedCandidates[i].semanticScore = cosineSimilarity(queryEmbedding, embeddings[i + 1]);
    }
    embeddingSuccess = true;
    diagnostics.semanticCandidateCount = enrichedCandidates.length;
  } catch (e) {
    // Embedding failed, fallback to deterministic
    console.warn("Embeddings failed, using fallback ranking");
    for (let i = 0; i < enrichedCandidates.length; i++) {
      enrichedCandidates[i].semanticScore = 0.5; // Neutral
    }
  }

  // FINAL RANKING
  for (const cand of enrichedCandidates) {
    // Calculate concept coverage
    let conceptCoverage = 0;
    const allKeywords = cand.keywords?.map((k: any) => k.name.toLowerCase()) || [];
    if (intent.requiredConceptGroups && intent.requiredConceptGroups.length > 0) {
      let matchedGroups = 0;
      for (const group of intent.requiredConceptGroups) {
        if (group.synonyms.some((syn: string) => allKeywords.some((k: string) => k.includes(syn.toLowerCase()) || (cand.details?.overview || '').toLowerCase().includes(syn.toLowerCase())))) {
          matchedGroups++;
        }
      }
      conceptCoverage = matchedGroups / intent.requiredConceptGroups.length;
    } else {
      conceptCoverage = 0.5;
    }

    let rankingScore = 0;
    if (intent.intentType === 'similar_to') {
      const isDirect = cand.retrievalSources.has('tmdb_recommendations') || cand.retrievalSources.has('tmdb_similar');
      rankingScore = (isDirect ? 0.30 : 0.0) +
                     (cand.semanticScore * 0.30) +
                     (conceptCoverage * 0.20) +
                     ((cand.preliminaryScore / 20) * 0.15) +
                     (Math.min(cand.popularityScore / 100, 1) * 0.05);
    } else {
      rankingScore = (cand.semanticScore * 0.35) +
                     (conceptCoverage * 0.30) +
                     ((cand.preliminaryScore / 20) * 0.20) +
                     (Math.min(cand.popularityScore / 100, 1) * 0.15);
    }
    cand.finalScore = rankingScore;
    cand.matchTier = rankingScore > 0.7 ? 'A' : rankingScore > 0.5 ? 'B' : rankingScore > 0.3 ? 'C' : 'D';
  }

  enrichedCandidates.sort((a, b) => b.finalScore - a.finalScore);
  
  // Format Results
  const results = enrichedCandidates.map(cand => ({
    tmdbId: cand.id,
    mediaType: mediaType,
    title: cand.title || cand.name,
    originalTitle: cand.original_title || cand.original_name,
    overview: cand.details?.overview || cand.overview,
    posterPath: cand.poster_path,
    backdropPath: cand.backdrop_path,
    releaseDate: cand.release_date || cand.first_air_date,
    genres: cand.details?.genres?.map((g: any) => g.name) || [],
    voteAverage: cand.vote_average,
    voteCount: cand.vote_count,
    matchTier: cand.matchTier,
    finalScore: cand.finalScore,
    retrievalSources: Array.from(cand.retrievalSources)
  }));
  
  diagnostics.acceptedCandidateCount = results.length;
  diagnostics.returnedCount = Math.min(results.length, req.pageSize);
  
  // No homeCatalogueCandidates, localFallbackCandidates, omdbCandidates, scrapedTmdbCandidates
  diagnostics.homeCatalogueCandidates = 0;
  diagnostics.localFallbackCandidates = 0;
  diagnostics.omdbCandidates = 0;
  diagnostics.scrapedTmdbCandidates = 0;

  return {
    requestId: req.requestId,
    interpretation: intent,
    results: results.slice(0, req.pageSize), // Pagination logic: just return first page for simplicity, or handle cursor properly in a real DB setup
    nextCursor: req.pageSize < results.length ? "cursor_next_page" : null,
    hasMore: req.pageSize < results.length,
    diagnostics
  };
}
