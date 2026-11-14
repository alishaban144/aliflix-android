import { fetchGemini, hasGeminiCredentials, raceGeminiGeneration, redactGeminiKeys } from './geminiKeys';
import { readGeminiError } from './geminiQuota';
import { EDITORIAL_RECOMMENDATIONS_PROMPT } from './prompts';
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
export const GEMINI_GENERATION_MODEL_FALLBACK = 'gemini-4-hydrogen';
export const GEMINI_GENERATION_TIMEOUT_MS = 45_000;
export const GEMINI_DESCRIBE_TIMEOUT_MS = 45_000;
export const GEMINI_VERIFICATION_TIMEOUT_MS = 20_000;
export const GEMINI_STRUCTURED_MAX_ATTEMPTS = 1;
export const GEMINI_RECOMMENDATION_LIMIT = 20;
export const GEMINI_RECOMMENDATION_MAX_OUTPUT_TOKENS = 8_192;
const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], productionCompanyIds: [], excludedTmdbIds: [], excludedTitles: [],
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

function editorialOutput(targetCount: number) {
  const count = Math.min(GEMINI_RECOMMENDATION_LIMIT, Math.max(1, targetCount));
  return {
    count,
    schema: {
      ...GeminiDescribeJsonSchema,
      properties: {
        recommendations: {...GeminiDescribeJsonSchema.properties.recommendations, maxItems: count},
      },
    },
  };
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
  decodeGeneration?: (response: Response) => Promise<T>,
): Promise<T> {
  if (!hasGeminiCredentials(env)) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini is not configured', 503, true);

  // The deadline covers the entire provider operation. The former per-attempt
  // timeout could turn one 30-second request into five attempts plus backoff,
  // which made an interactive request hang for almost three minutes.
  const attempts=env.GEMINI_FREE_TIER_MODE==='true'?1:maxAttempts;
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let lastError: Error | undefined;
  let providerRetryAfterMs: number | undefined;

  for (let attempt = 0; attempt < attempts; attempt++) {
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
    let awaiting: 'headers' | 'body' | 'generation projects' = 'headers';
    try {
      const init = {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      };
      if (decodeGeneration) awaiting = 'generation projects';
      const result = decodeGeneration
        ? await raceGeminiGeneration(env, url, init, decodeGeneration)
        : {response: await fetchGemini(env, url, init)};
      if ('data' in result) {
        console.info(JSON.stringify({event:'gemini_generation_completed',operation,slot:result.keySlot,elapsedMs:Date.now()-startedAt}));
        return result.data;
      }
      const {response} = result;
      awaiting = 'body';

      if (!response.ok) {
        const retryable = response.status === 408 || response.status === 429 || response.status >= 500;
        const retryAfterSeconds = Number(response.headers.get('retry-after'));
        const providerError = await readGeminiError(response);
        controller.signal.throwIfAborted();
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
          providerMessage: redactGeminiKeys(env,providerError?.error?.message || '').slice(0, 300),
          providerRetryAfterMs,
          retryable,
          attempt: attempt + 1,
          elapsedMs: Date.now() - startedAt,
          operation,
        }));
        if (retryable && attempt + 1 < attempts) {
          lastError = new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, 503, true);
          continue;
        }
        const providerMessage = redactGeminiKeys(env,providerError?.error?.message || '').trim().slice(0, 300);
        const isGeminiCapacityFailure = response.status === 503
          && /high demand/i.test(providerMessage || '');
        if (isGeminiCapacityFailure) {
          throw new ServiceError(
            'GEMINI_UNAVAILABLE',
            'Gemini 4 Hydrogen is temporarily at capacity. Try again shortly.',
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
      if (controller.signal.aborted) {
        console.warn(JSON.stringify({event:'gemini_request_timeout',operation,awaiting,elapsedMs:Date.now()-startedAt}));
        lastError = new ServiceError('GEMINI_UNAVAILABLE', `Gemini ${operation} timed out`, 504, true);
      } else if (error instanceof Error) {
        lastError = error;
      } else {
        lastError = new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request failed', 503, true);
      }
      if (attempt + 1 >= attempts || Date.now() >= deadline) {
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
  validate: (value: unknown) => T,
  timeoutMs: number,
  thinkingLevel: GeminiThinkingLevel,
  operation: string,
  maxOutputTokens = 8_192,
): Promise<T> {
  const convertedSchema = responseJsonSchema(schema);
  const structuredOutputConfig = { responseMimeType: 'application/json', responseJsonSchema: convertedSchema };
  return geminiFetch(
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
    async response => validate(await readStructuredAnswer(response, operation)),
  );
}

async function readStructuredAnswer(response: Response, operation: string): Promise<unknown> {
  const data = await response.json<GeminiGenerateContentResponse>();
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
    return JSON.parse(cleanedText) as unknown;
  } catch {
    const finishReason = data.candidates?.[0]?.finishReason || 'unknown';
    console.warn(JSON.stringify({
      event: 'gemini_json_parse_failed',
      operation,
      finishReason,
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

  const model = env.GEMINI_GENERATION_MODEL || GEMINI_GENERATION_MODEL_FALLBACK;

  return geminiStructuredContent(
    env,
    model,
    INTERPRET_V3_PROMPT,
    { query, authoritativeMediaType: mediaType },
    GeminiIntentJsonSchema,
    value => GeminiIntentResponseSchema.parse(value),
    30_000,
    'medium',
    'query interpretation',
  );
}

export async function recommendDescribeTitles(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
  targetCount = 20,
): Promise<DescribeRecommendation[]> {
  const model = env.GEMINI_GENERATION_MODEL || GEMINI_GENERATION_MODEL_FALLBACK;
  const output = editorialOutput(targetCount);
  const parsed = await geminiStructuredContent(
    env,
    model,
    EDITORIAL_RECOMMENDATIONS_PROMPT,
    {
      query,
      currentDate: new Date().toISOString().slice(0, 10),
      authoritativeMediaType: mediaType,
      explicitFilters,
      targetCount: output.count,
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    output.schema,
    value => {
      const result = GeminiDescribeResponseSchema.safeParse(value);
      if (result.success) return result.data;
      console.warn(JSON.stringify({
        event: 'gemini_describe_schema_failed',
        model,
        error: result.error.message.slice(0, 300),
      }));
      throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned invalid Describe recommendations', 502, true);
    },
    GEMINI_DESCRIBE_TIMEOUT_MS,
    'low',
    'Describe candidate generation',
    8_192,
  );
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
  targetCount = 20,
): Promise<DescribeRecommendation[]> {
  const model = env.GEMINI_GENERATION_MODEL || GEMINI_GENERATION_MODEL_FALLBACK;
  const output = editorialOutput(targetCount);
  const parsed = await geminiStructuredContent(
    env,
    model,
    EDITORIAL_RECOMMENDATIONS_PROMPT,
    {
      anchors,
      currentDate: new Date().toISOString().slice(0, 10),
      authoritativeMediaType: mediaType,
      refinement,
      explicitFilters,
      targetCount: output.count,
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    output.schema,
    value => GeminiDescribeResponseSchema.parse(value),
    GEMINI_GENERATION_TIMEOUT_MS,
    'low',
    'Similar candidate generation',
    GEMINI_RECOMMENDATION_MAX_OUTPUT_TOKENS,
  );
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
  const model = env.GEMINI_GENERATION_MODEL || GEMINI_GENERATION_MODEL_FALLBACK;
  const parsed = await geminiStructuredContent(
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
    value => GeminiPremiseAssessmentResponseSchema.parse(value),
    GEMINI_VERIFICATION_TIMEOUT_MS,
    'medium',
    'premise verification',
  );
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
  const model = env.GEMINI_GENERATION_MODEL || GEMINI_GENERATION_MODEL_FALLBACK;
  const parsed = await geminiStructuredContent(
    env,
    model,
    VERIFY_SIMILARITY_PROMPT,
    { anchors, refinement, authoritativeMediaType: mediaType, candidates },
    GeminiPremiseAssessmentJsonSchema,
    value => GeminiPremiseAssessmentResponseSchema.parse(value),
    GEMINI_VERIFICATION_TIMEOUT_MS,
    'medium',
    'similarity verification',
  );
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parsed.assessments.filter(assessment => validIndexes.has(assessment.index));
}

async function embedBatch(env: RecommendationEnv, texts: string[], taskType: 'RETRIEVAL_QUERY' | 'RETRIEVAL_DOCUMENT'): Promise<number[][]> {
  const model = env.GEMINI_EMBEDDING_MODEL || 'gemini-embedding-2';
  const data = await geminiFetch<GeminiEmbeddingResponse>(env, `${API_BASE}/${encodeURIComponent(model)}:batchEmbedContents`, {
    requests: texts.map(text => ({
      model: `models/${model}`, embedContentConfig: { outputDimensionality: 768 },
      // Embedding 2 uses text task prefixes; taskType is not supported. One
      // batch request per document avoids aggregating independent scene vectors.
      content: { parts: [{ text: taskType === 'RETRIEVAL_QUERY'
        ? `task: search result | query: ${text}` : `title: none | text: ${text}` }] },
    })),
  }, 10_000);
  const embeddings = data?.embeddings;
  if (!Array.isArray(embeddings) || embeddings.length !== texts.length) {
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned an incomplete embedding batch', 502, true);
  }
  return embeddings.map(entry => {
    const values = entry?.values;
    if (!Array.isArray(values) || values.length !== 768 || values.some(value => typeof value !== 'number' || !Number.isFinite(value))) {
      throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned an invalid embedding vector', 502, true);
    }
    return values as number[];
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
