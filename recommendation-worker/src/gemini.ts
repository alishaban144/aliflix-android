import {
  DESCRIBE_RECOMMENDATIONS_PROMPT,
  INTERPRET_V3_PROMPT,
  SIMILAR_RECOMMENDATIONS_PROMPT,
  VERIFY_PREMISE_PROMPT,
  VERIFY_SIMILARITY_PROMPT,
} from './prompts';
import {
  GeminiDescribeJsonSchema,
  GeminiDescribeResponseSchema,
  GeminiIntentJsonSchema,
  GeminiIntentResponseSchema,
  GeminiPremiseAssessmentJsonSchema,
  GeminiPremiseAssessmentResponseSchema,
} from './schemas';
import {
  DescribeRecommendation,
  InterpretedIntent,
  MediaType,
  PremiseAssessment,
  PremiseCandidateDocument,
  RecommendationEnv,
  ServiceError,
  SimilarAnchorDocument,
} from './types';

const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models';
const INTERACTIONS_URL = 'https://generativelanguage.googleapis.com/v1/interactions';
const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [],
};

interface GeminiInteractionResponse {
  status?: string;
  steps?: Array<{ type?: string; content?: Array<{ type?: string; text?: unknown }> }>;
}

interface GeminiEmbeddingResponse {
  embeddings?: Array<{ values?: unknown }>;
}

type GeminiThinkingLevel = 'low' | 'medium' | 'high';

async function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function geminiFetch<T>(
  env: RecommendationEnv,
  url: string,
  body: unknown,
  timeoutMs: number,
  operation = 'request',
): Promise<T> {
  if (!env.GEMINI_API_KEY) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini is not configured', 503, true);

  // The deadline covers the entire provider operation. The former per-attempt
  // timeout could turn one 30-second request into five attempts plus backoff,
  // which made an interactive request hang for almost three minutes.
  const maxAttempts = 2;
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let lastError: Error | undefined;
  let providerRetryAfterMs: number | undefined;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (attempt > 0) {
      const remainingBeforeDelay = deadline - Date.now();
      const delay = Math.min(1_500, Math.max(250, providerRetryAfterMs || 0));
      if (remainingBeforeDelay <= delay + 250) break;
      await sleep(delay);
    }
    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) break;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), remainingMs);
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const retryable = response.status === 408 || response.status === 429 || response.status >= 500;
        const retryAfterSeconds = Number(response.headers.get('retry-after'));
        providerRetryAfterMs = Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
          ? retryAfterSeconds * 1_000
          : undefined;
        const providerError = await response.json().catch(() => undefined) as {
          error?: { code?: number; status?: string; message?: string };
        } | undefined;
        console.warn(JSON.stringify({
          event: 'gemini_request_failed',
          status: response.status,
          providerStatus: providerError?.error?.status,
          retryable,
          attempt: attempt + 1,
          elapsedMs: Date.now() - startedAt,
          operation,
        }));
        if (retryable && attempt + 1 < maxAttempts) {
          lastError = new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, 503, true);
          continue;
        }
        throw new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, retryable ? 503 : 502, retryable);
      }
      return await response.json() as T;
    } catch (error) {
      if (error instanceof ServiceError && !error.retryable) throw error;
      if (error instanceof DOMException && error.name === 'AbortError') {
        lastError = new ServiceError('GEMINI_UNAVAILABLE', `Gemini ${operation} timed out`, 504, true);
      } else if (error instanceof Error) {
        lastError = error;
      } else {
        lastError = new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request failed', 503, true);
      }
      if (attempt + 1 >= maxAttempts || Date.now() >= deadline) {
        if (lastError instanceof ServiceError) throw lastError;
        throw new ServiceError('GEMINI_UNAVAILABLE', lastError.message || 'Gemini request failed', 503, true);
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  if (lastError instanceof ServiceError) throw lastError;
  throw new ServiceError(
    'GEMINI_UNAVAILABLE',
    lastError?.message || `Gemini request exceeded its ${timeoutMs}ms deadline`,
    504,
    true,
  );
}

async function geminiStructuredInteraction<T>(
  env: RecommendationEnv,
  model: string,
  systemInstruction: string,
  input: unknown,
  schema: unknown,
  timeoutMs: number,
  thinkingLevel: GeminiThinkingLevel,
  operation: string,
): Promise<T> {
  const data = await geminiFetch<GeminiInteractionResponse>(env, INTERACTIONS_URL, {
    model,
    input: JSON.stringify(input),
    system_instruction: systemInstruction,
    response_format: [{ type: 'text', mime_type: 'application/json', schema }],
    generation_config: { max_output_tokens: 4_096, thinking_level: thinkingLevel },
    store: false,
  }, timeoutMs, operation);
  const text = data.steps
    ?.filter(step => step.type === 'model_output')
    .flatMap(step => step.content || [])
    .find(content => content.type === 'text' && typeof content.text === 'string')
    ?.text;
  if (typeof text !== 'string' || !text) {
    throw new ServiceError('GEMINI_UNAVAILABLE', `Gemini interaction returned no structured output (${data.status || 'unknown'})`, 502, true);
  }
  return JSON.parse(text) as T;
}

export async function interpretQuery(env: RecommendationEnv, query: string, mediaType: MediaType): Promise<InterpretedIntent> {
  if (!query.trim()) {
    return {
      hardFilters: { ...EMPTY_FILTERS }, requiredConceptGroups: [], softConcepts: [], excludedConcepts: [],
      excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
      genreHints: [], toneAndMood: [], broadSearchPhrases: [],
    };
  }

  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';

  const data = await geminiStructuredInteraction<unknown>(
    env,
    model,
    INTERPRET_V3_PROMPT,
    { query, authoritativeMediaType: mediaType },
    GeminiIntentJsonSchema,
    20_000,
    'low',
    'query interpretation',
  );
  return GeminiIntentResponseSchema.parse(data);
}

export async function recommendDescribeTitles(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
): Promise<DescribeRecommendation[]> {
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';
  const data = await geminiStructuredInteraction<unknown>(
    env,
    model,
    DESCRIBE_RECOMMENDATIONS_PROMPT,
    {
      query,
      authoritativeMediaType: mediaType,
      explicitFilters,
      targetCount: 24,
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    GeminiDescribeJsonSchema,
    30_000,
    'low',
    'Describe candidate generation',
  );
  const parsed = GeminiDescribeResponseSchema.parse(data);
  const seen = new Set<string>();
  return parsed.recommendations.filter(item => {
    const key = `${item.title.toLocaleLowerCase()}:${item.releaseYear}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export async function recommendSimilarTitles(
  env: RecommendationEnv,
  anchors: SimilarAnchorDocument[],
  mediaType: MediaType,
  refinement: string,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
): Promise<DescribeRecommendation[]> {
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';
  const data = await geminiStructuredInteraction<unknown>(
    env,
    model,
    SIMILAR_RECOMMENDATIONS_PROMPT,
    {
      anchors,
      authoritativeMediaType: mediaType,
      refinement,
      explicitFilters,
      targetCount: 24,
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    GeminiDescribeJsonSchema,
    30_000,
    'low',
    'Similar candidate generation',
  );
  const parsed = GeminiDescribeResponseSchema.parse(data);
  const seen = new Set<string>();
  return parsed.recommendations.filter(item => {
    const key = `${item.title.toLocaleLowerCase()}:${item.releaseYear}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export async function assessPremiseCandidates(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  requiredConceptGroups: InterpretedIntent['requiredConceptGroups'],
  candidates: PremiseCandidateDocument[],
): Promise<PremiseAssessment[]> {
  if (!candidates.length) return [];
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';
  const data = await geminiStructuredInteraction<unknown>(
    env,
    model,
    VERIFY_PREMISE_PROMPT,
    {
      query,
      authoritativeMediaType: mediaType,
      requiredConceptGroups: requiredConceptGroups.map((group, index) => ({
        index,
        label: group.label,
        synonyms: group.synonyms,
      })),
      candidates,
    },
    GeminiPremiseAssessmentJsonSchema,
    30_000,
    'medium',
    'premise verification',
  );
  const parsed = GeminiPremiseAssessmentResponseSchema.parse(data);
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parsed.assessments.filter(assessment => validIndexes.has(assessment.index));
}

export async function assessSimilarCandidates(
  env: RecommendationEnv,
  anchors: SimilarAnchorDocument[],
  refinement: string,
  mediaType: MediaType,
  candidates: PremiseCandidateDocument[],
): Promise<PremiseAssessment[]> {
  if (!candidates.length) return [];
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';
  const data = await geminiStructuredInteraction<unknown>(
    env,
    model,
    VERIFY_SIMILARITY_PROMPT,
    { anchors, refinement, authoritativeMediaType: mediaType, candidates },
    GeminiPremiseAssessmentJsonSchema,
    30_000,
    'medium',
    'similarity verification',
  );
  const parsed = GeminiPremiseAssessmentResponseSchema.parse(data);
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parsed.assessments.filter(assessment => validIndexes.has(assessment.index));
}

async function embedBatch(env: RecommendationEnv, texts: string[], taskType: 'RETRIEVAL_QUERY' | 'RETRIEVAL_DOCUMENT'): Promise<number[][]> {
  const model = env.GEMINI_EMBEDDING_MODEL || 'gemini-embedding-001';
  const data = await geminiFetch<GeminiEmbeddingResponse>(env, `${API_BASE}/${encodeURIComponent(model)}:batchEmbedContents`, {
    requests: texts.map(text => ({
      model: `models/${model}`, taskType, outputDimensionality: 768,
      content: { parts: [{ text }] },
    })),
  }, 10_000);
  const embeddings = data?.embeddings;
  if (!Array.isArray(embeddings) || embeddings.length !== texts.length) {
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned an incomplete embedding batch', 502, true);
  }
  return embeddings.map(entry => {
    const values = entry?.values;
    if (!Array.isArray(values) || !values.length) {
      throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned an invalid embedding vector', 502, true);
    }
    return values.map(Number);
  });
}

export async function embedForSearch(
  env: RecommendationEnv,
  query: string,
  candidateTexts: string[],
): Promise<{ queryVector: number[]; candidateVectors: Array<number[] | undefined> }> {
  const queryVectors = await embedBatch(env, [query], 'RETRIEVAL_QUERY');
  const queryVector = queryVectors[0];
  if (!queryVector) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini query embedding was empty', 502, true);
  const chunkSize = 24;
  const chunks = Array.from({ length: Math.ceil(candidateTexts.length / chunkSize) }, (_, index) =>
    candidateTexts.slice(index * chunkSize, (index + 1) * chunkSize));
  const candidateVectors = (await Promise.all(chunks.map(async texts => {
    try {
      return await embedBatch(env, texts, 'RETRIEVAL_DOCUMENT');
    } catch (error) {
      console.warn('[Gemini] Candidate embedding chunk unavailable; retaining deterministic ranking', error instanceof Error ? error.message : error);
      return texts.map(() => undefined);
    }
  }))).flat();
  return { queryVector, candidateVectors };
}
