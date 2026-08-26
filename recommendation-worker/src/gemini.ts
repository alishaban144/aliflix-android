import {
  DESCRIBE_RECOMMENDATIONS_COMPACT_PROMPT,
  INTERPRET_V3_PROMPT,
  SIMILAR_RECOMMENDATIONS_COMPACT_PROMPT,
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
export const GEMINI_GENERATION_TIMEOUT_MS = 30_000;
export const GEMINI_DESCRIBE_TIMEOUT_MS = 24_000;
export const GEMINI_VERIFICATION_TIMEOUT_MS = 20_000;
export const GEMINI_STRUCTURED_MAX_ATTEMPTS = 1;
export const GEMINI_37_RECOMMENDATION_LIMIT = 8;
export const GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS = 3_072;
const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [],
};

const FALLBACK_STOP_WORDS = new Set([
  'about', 'after', 'also', 'an', 'and', 'are', 'be', 'become', 'becomes', 'becoming', 'before', 'but', 'can',
  'could', 'find', 'finding', 'finds', 'for', 'from', 'get', 'gets', 'give', 'has', 'have', 'in', 'into', 'is',
  'just', 'less', 'like', 'made', 'me', 'more', 'movie', 'movies', 'need',
  'of', 'or', 'please', 'really', 'recommend', 'recommendation', 'series', 'show', 'shows', 'something',
  'story', 'stories', 'surprise', 'that', 'the', 'their', 'them', 'these', 'this', 'to', 'very', 'want',
  'where', 'which', 'with', 'would',
]);

const FALLBACK_PHRASES = [
  'artificial intelligence', 'coming of age', 'dark comedy', 'enemies to lovers', 'found footage',
  'haunted house', 'mind bending', 'plot twist', 'serial killer', 'small town', 'time loop', 'time travel',
  'true crime', 'unreliable narrator', 'cold case', 'psychological thriller', 'supernatural powers',
];

const FALLBACK_GENRE_CLUES: Array<[RegExp, string]> = [
  [/\b(?:funny|comedy|comic)\b/u, 'Comedy'],
  [/\b(?:detective|murder|crime|criminal|heist|serial killer)\b/u, 'Crime'],
  [/\b(?:mystery|whodunit|cold case)\b/u, 'Mystery'],
  [/\b(?:scary|horror|haunted|slasher)\b/u, 'Horror'],
  [/\b(?:romance|romantic|love)\b/u, 'Romance'],
  [/\b(?:science fiction|sci fi|space|cyberpunk|time travel|time loop|alien|aliens|extraterrestrial)\b/u, 'Science Fiction'],
  [/\b(?:fantasy|magic|magical)\b/u, 'Fantasy'],
  [/\b(?:war|wartime)\b/u, 'War'],
  [/\b(?:western|cowboy)\b/u, 'Western'],
  [/\b(?:documentary|true crime)\b/u, 'Documentary'],
  [/\b(?:animation|animated|anime)\b/u, 'Animation'],
  [/\b(?:thriller|suspense|tense)\b/u, 'Thriller'],
];

interface GeminiGenerateContentResponse {
  candidates?: Array<{
    finishReason?: string;
    content?: { parts?: Array<{ text?: unknown; thought?: boolean }> };
  }>;
}

interface GeminiEmbeddingResponse {
  embeddings?: Array<{ values?: unknown }>;
}

type GeminiThinkingLevel = 'low' | 'medium' | 'high';

function isGemini37Flash(model: string): boolean {
  return model === 'gemini-3.7-flash';
}

function responseJsonSchema(schema: unknown): unknown {
  if (Array.isArray(schema)) return schema.map(responseJsonSchema);
  if (!schema || typeof schema !== 'object') return schema;
  const source = schema as Record<string, unknown>;
  const converted = Object.fromEntries(
    Object.entries(source)
      .filter(([key]) => key !== 'nullable')
      .map(([key, value]) => [
        key,
        key === 'type' && typeof value === 'string'
          ? value.toLowerCase()
          : responseJsonSchema(value),
      ]),
  );
  if (source.nullable === true && typeof converted.type === 'string') {
    converted.type = [converted.type, 'null'];
  }
  return converted;
}

async function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function geminiFetch<T>(
  env: RecommendationEnv,
  url: string,
  body: unknown,
  timeoutMs: number,
  operation = 'request',
  maxAttempts = 2,
): Promise<T> {
  if (!env.GEMINI_API_KEY) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini is not configured', 503, true);

  // The deadline covers the entire provider operation. The former per-attempt
  // timeout could turn one 30-second request into five attempts plus backoff,
  // which made an interactive request hang for almost three minutes.
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let lastError: Error | undefined;
  let providerRetryAfterMs: number | undefined;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (attempt > 0) {
      const remainingBeforeDelay = deadline - Date.now();
      const exponentialDelay = Math.min(8_000, 1_000 * Math.pow(2, attempt - 1));
      const delay = Math.min(30_000, providerRetryAfterMs ?? exponentialDelay);
      if (remainingBeforeDelay <= delay + 250) break;
      await sleep(delay + (providerRetryAfterMs === undefined ? Math.random() * 250 : 0));
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
        const providerError = await response.json().catch(() => undefined) as {
          error?: {
            code?: number | string;
            status?: string;
            message?: string;
            details?: Array<{ '@type'?: string; retryDelay?: string }>;
          };
        } | undefined;
        const retryInfo = providerError?.error?.details?.find(detail =>
          detail['@type'] === 'type.googleapis.com/google.rpc.RetryInfo');
        const retryInfoMatch = /^(\d+(?:\.\d+)?)s$/.exec(retryInfo?.retryDelay || '');
        const messageRetryMatch = /retry in\s+(\d+(?:\.\d+)?)s/i.exec(providerError?.error?.message || '');
        const bodyRetrySeconds = Number(retryInfoMatch?.[1] || messageRetryMatch?.[1]);
        providerRetryAfterMs = Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
          ? retryAfterSeconds * 1_000
          : Number.isFinite(bodyRetrySeconds) && bodyRetrySeconds > 0
            ? bodyRetrySeconds * 1_000
            : undefined;
        console.warn(JSON.stringify({
          event: 'gemini_request_failed',
          status: response.status,
          providerCode: providerError?.error?.code,
          providerStatus: providerError?.error?.status,
          providerMessage: providerError?.error?.message?.slice(0, 300),
          providerRetryAfterMs,
          retryable,
          attempt: attempt + 1,
          elapsedMs: Date.now() - startedAt,
          operation,
        }));
        if (retryable && attempt + 1 < maxAttempts) {
          lastError = new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, 503, true);
          continue;
        }
        const providerMessage = providerError?.error?.message?.trim().slice(0, 300);
        const isGemini37CapacityFailure = response.status === 503
          && isGemini37Flash(decodeURIComponent(url).split('/').at(-1)?.split(':')[0] || '')
          && /high demand/i.test(providerMessage || '');
        if (isGemini37CapacityFailure) {
          throw new ServiceError(
            'GEMINI_UNAVAILABLE',
            'Gemini 3.7 Flash is temporarily at capacity. Switch to Gemini 3.5 Flash in Settings and try again.',
            503,
            true,
          );
        }
        const diagnostic = providerMessage ? `: ${providerMessage}` : '';
        throw new ServiceError(
          'GEMINI_UNAVAILABLE',
          `Gemini request failed (${response.status})${diagnostic}`,
          retryable ? 503 : 502,
          retryable,
        );
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

function cleanJsonText(raw: string): string {
  let cleaned = raw.trim();
  if (cleaned.startsWith('```json')) {
    cleaned = cleaned.slice(7);
  } else if (cleaned.startsWith('```')) {
    cleaned = cleaned.slice(3);
  }
  if (cleaned.endsWith('```')) {
    cleaned = cleaned.slice(0, -3);
  }
  return cleaned.trim();
}

/**
 * Builds a conservative TMDB-search intent when Gemini generation is
 * temporarily unavailable. It deliberately preserves only explicit concepts;
 * the engine still requires exact TMDB keyword identities and hydrated details.
 */
export function fallbackIntentFromQuery(query: string): InterpretedIntent {
  const normalizedQuery = query
    .toLocaleLowerCase()
    .replace(/[^\p{L}\p{N}\s-]+/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const excludedConcepts: string[] = [];
  const negativePattern = /\b(?:avoid|exclude|excluding|no|without)\s+([\p{L}\p{N}-]+(?:\s+[\p{L}\p{N}-]+)?)/gu;
  for (const match of normalizedQuery.matchAll(negativePattern)) {
    const value = match[1]?.trim();
    if (value && !excludedConcepts.includes(value)) excludedConcepts.push(value);
  }

  let positiveText = normalizedQuery.replace(negativePattern, ' ');
  const concepts: string[] = [];
  for (const phrase of FALLBACK_PHRASES) {
    if (` ${positiveText} `.includes(` ${phrase} `)) {
      concepts.push(phrase);
      positiveText = ` ${positiveText} `.replace(` ${phrase} `, ' ').trim();
    }
  }
  for (const word of positiveText.split(/\s+/)) {
    if (concepts.length >= 8) break;
    if (word.length < 3 || FALLBACK_STOP_WORDS.has(word) || /^(?:19|20)\d{2}$/.test(word)) continue;
    if (!concepts.includes(word)) concepts.push(word);
  }

  const genreHints = FALLBACK_GENRE_CLUES
    .filter(([pattern]) => pattern.test(normalizedQuery))
    .map(([, genre]) => genre)
    .filter((genre, index, values) => values.indexOf(genre) === index);
  return {
    hardFilters: { ...EMPTY_FILTERS },
    requiredConceptGroups: concepts.map(concept => ({
      label: concept,
      synonyms: [concept, concept.includes(' ') ? concept.replace(/\s+/g, '-') : concept]
        .filter((value, index, values) => values.indexOf(value) === index),
      weight: 1,
    })),
    softConcepts: [],
    excludedConcepts,
    excludedKeywords: excludedConcepts,
    crewNames: [],
    castNames: [],
    studioNames: [],
    certifications: [],
    genreHints,
    toneAndMood: [],
    broadSearchPhrases: concepts.slice(0, 4),
  };
}

async function geminiStructuredContent<T>(
  env: RecommendationEnv,
  model: string,
  systemInstruction: string,
  input: unknown,
  schema: unknown,
  timeoutMs: number,
  thinkingLevel: GeminiThinkingLevel,
  operation: string,
  maxOutputTokens = 8_192,
): Promise<T> {
  const convertedSchema = responseJsonSchema(schema);
  // Keep the already-proven 3.5 request shape unchanged. Gemini 3.7 uses the
  // mature generateContent structured-output fields from Google's REST API;
  // avoiding the newer nested responseFormat path prevents extra server-side
  // response negotiation on its latency-sensitive recommendation requests.
  const structuredOutputConfig = isGemini37Flash(model)
    ? {
        responseMimeType: 'application/json',
        responseJsonSchema: convertedSchema,
      }
    : {
        responseFormat: { text: { mimeType: 'APPLICATION_JSON', schema: convertedSchema } },
      };
  const data = await geminiFetch<GeminiGenerateContentResponse>(
    env,
    `${API_BASE}/${encodeURIComponent(model)}:generateContent`,
    {
      systemInstruction: { parts: [{ text: systemInstruction }] },
      contents: [{ role: 'user', parts: [{ text: JSON.stringify(input) }] }],
      generationConfig: {
        maxOutputTokens,
        thinkingConfig: { thinkingLevel },
        ...structuredOutputConfig,
      },
    },
    timeoutMs,
    operation,
    GEMINI_STRUCTURED_MAX_ATTEMPTS,
  );
  const text = data.candidates
    ?.flatMap(candidate => candidate.content?.parts || [])
    .find(part => !part.thought && typeof part.text === 'string')
    ?.text;
  if (typeof text !== 'string' || !text) {
    const finishReason = data.candidates?.[0]?.finishReason || 'unknown';
    throw new ServiceError('GEMINI_UNAVAILABLE', `Gemini returned no structured output (${finishReason})`, 502, true);
  }
  const cleanedText = cleanJsonText(text);
  try {
    return JSON.parse(cleanedText) as T;
  } catch (error) {
    const finishReason = data.candidates?.[0]?.finishReason || 'unknown';
    console.warn(JSON.stringify({
      event: 'gemini_json_parse_failed',
      operation,
      finishReason,
      textPreview: cleanedText.slice(0, 300),
      error: error instanceof Error ? error.message : String(error),
    }));
    throw new ServiceError('GEMINI_UNAVAILABLE', `Gemini returned malformed structured output (${finishReason})`, 502, true);
  }
}

export async function interpretQuery(env: RecommendationEnv, query: string, mediaType: MediaType): Promise<InterpretedIntent> {
  if (!query.trim()) {
    return {
      hardFilters: { ...EMPTY_FILTERS }, requiredConceptGroups: [], softConcepts: [], excludedConcepts: [],
      excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
      genreHints: [], toneAndMood: [], broadSearchPhrases: [],
    };
  }

  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.5-flash';

  const data = await geminiStructuredContent<unknown>(
    env,
    model,
    INTERPRET_V3_PROMPT,
    { query, authoritativeMediaType: mediaType },
    GeminiIntentJsonSchema,
    30_000,
    'medium',
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
  targetCount = 12,
): Promise<DescribeRecommendation[]> {
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.5-flash';
  const optimized37 = isGemini37Flash(model);
  const recommendationLimit = optimized37 ? GEMINI_37_RECOMMENDATION_LIMIT : 12;
  const data = await geminiStructuredContent<unknown>(
    env,
    model,
    DESCRIBE_RECOMMENDATIONS_COMPACT_PROMPT,
    {
      query,
      authoritativeMediaType: mediaType,
      explicitFilters,
      targetCount: Math.min(recommendationLimit, Math.max(1, targetCount)),
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    GeminiDescribeJsonSchema,
    GEMINI_DESCRIBE_TIMEOUT_MS,
    'low',
    'Describe candidate generation',
    GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS,
  );
  let parsed: ReturnType<typeof GeminiDescribeResponseSchema.parse>;
  try {
    parsed = GeminiDescribeResponseSchema.parse(data);
  } catch (error) {
    console.warn(JSON.stringify({
      event: 'gemini_describe_schema_failed',
      model,
      error: error instanceof Error ? error.message.slice(0, 300) : String(error),
    }));
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned invalid Describe recommendations', 502, true);
  }
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
  targetCount = 12,
): Promise<DescribeRecommendation[]> {
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.5-flash';
  const optimized37 = isGemini37Flash(model);
  const recommendationLimit = optimized37 ? GEMINI_37_RECOMMENDATION_LIMIT : 12;
  const data = await geminiStructuredContent<unknown>(
    env,
    model,
    optimized37 ? SIMILAR_RECOMMENDATIONS_COMPACT_PROMPT : SIMILAR_RECOMMENDATIONS_PROMPT,
    {
      anchors,
      authoritativeMediaType: mediaType,
      refinement,
      explicitFilters,
      targetCount: Math.min(recommendationLimit, Math.max(1, targetCount)),
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    GeminiDescribeJsonSchema,
    GEMINI_GENERATION_TIMEOUT_MS,
    'medium',
    'Similar candidate generation',
    optimized37 ? GEMINI_37_RECOMMENDATION_MAX_OUTPUT_TOKENS : 8_192,
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
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.5-flash';
  const data = await geminiStructuredContent<unknown>(
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
    GEMINI_VERIFICATION_TIMEOUT_MS,
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
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.5-flash';
  const data = await geminiStructuredContent<unknown>(
    env,
    model,
    VERIFY_SIMILARITY_PROMPT,
    { anchors, refinement, authoritativeMediaType: mediaType, candidates },
    GeminiPremiseAssessmentJsonSchema,
    GEMINI_VERIFICATION_TIMEOUT_MS,
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
