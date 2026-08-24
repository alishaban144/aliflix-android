import { INTERPRET_V3_PROMPT, VERIFY_PREMISE_PROMPT } from './prompts';
import {
  GeminiIntentJsonSchema,
  GeminiIntentResponseSchema,
  GeminiPremiseAssessmentJsonSchema,
  GeminiPremiseAssessmentResponseSchema,
} from './schemas';
import {
  InterpretedIntent,
  MediaType,
  PremiseAssessment,
  PremiseCandidateDocument,
  RecommendationEnv,
  ServiceError,
} from './types';

const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models';
const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [],
};

const FALLBACK_STOP_WORDS = new Set([
  'about', 'after', 'also', 'and', 'before', 'but', 'can', 'could', 'find', 'for', 'from', 'give', 'has',
  'have', 'into', 'just', 'less', 'like', 'made', 'more', 'movie', 'movies', 'need', 'please', 'really',
  'recommend', 'recommendation', 'series', 'show', 'shows', 'something', 'story', 'stories', 'surprise',
  'that', 'the', 'their', 'them', 'these', 'they', 'this', 'very', 'want', 'where', 'which', 'who', 'with', 'would',
]);

const FALLBACK_PHRASES = [
  'alien abduction', 'natural disasters', 'natural disaster', 'mysterious place', 'cannot escape', 'young people',
  'artificial intelligence', 'coming of age', 'dark comedy', 'enemies to lovers', 'found footage',
  'haunted house', 'mind bending', 'plot twist', 'serial killer', 'small town', 'time loop', 'time travel',
  'true crime', 'unreliable narrator', 'cold case', 'psychological thriller', 'supernatural powers',
];

const GENRE_CLUES: Array<[RegExp, string]> = [
  [/\b(?:funny|comedy|comic)\b/u, 'Comedy'],
  [/\b(?:detective|murder|crime|criminal|heist|serial killer)\b/u, 'Crime'],
  [/\b(?:mystery|whodunit|cold case)\b/u, 'Mystery'],
  [/\b(?:scary|horror|haunted|slasher)\b/u, 'Horror'],
  [/\b(?:romance|romantic|love)\b/u, 'Romance'],
  [/\b(?:science fiction|sci fi|space|cyberpunk|time travel|time loop)\b/u, 'Science Fiction'],
  [/\b(?:fantasy|magic|magical)\b/u, 'Fantasy'],
  [/\b(?:war|wartime)\b/u, 'War'],
  [/\b(?:western|cowboy)\b/u, 'Western'],
  [/\b(?:documentary|true crime)\b/u, 'Documentary'],
  [/\b(?:animation|animated|anime)\b/u, 'Animation'],
  [/\b(?:thriller|suspense|tense)\b/u, 'Thriller'],
];

interface GeminiGenerationResponse {
  candidates?: Array<{ content?: { parts?: Array<{ text?: unknown }> } }>;
}

interface GeminiEmbeddingResponse {
  embeddings?: Array<{ values?: unknown }>;
}

async function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

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

  const genreHints = GENRE_CLUES
    .filter(([pattern]) => pattern.test(normalizedQuery))
    .map(([, genre]) => genre)
    .filter((genre, index, values) => values.indexOf(genre) === index);
  return {
    hardFilters: { ...EMPTY_FILTERS },
    requiredConceptGroups: concepts.map(concept => ({
      label: concept,
      synonyms: [concept, concept.includes(' ') ? concept.replace(/\s+/g, '-') : concept].filter((value, index, values) => values.indexOf(value) === index),
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
    seedTitles: [],
  };
}

async function geminiFetch<T>(env: RecommendationEnv, model: string, method: string, body: unknown, timeoutMs: number): Promise<T> {
  if (!env.GEMINI_API_KEY) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini is not configured', 503, true);

  const maxRetries = 1;
  let lastError: Error | undefined;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    if (attempt > 0) {
      await sleep(300 * Math.pow(2, attempt - 1) + Math.random() * 100);
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${API_BASE}/${encodeURIComponent(model)}:${method}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const retryable = response.status === 429 || response.status >= 500;
        if (retryable && attempt < maxRetries) {
          lastError = new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, 503, true);
          continue;
        }
        throw new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, retryable ? 503 : 502, retryable);
      }
      return await response.json() as T;
    } catch (error) {
      if (error instanceof ServiceError && !error.retryable) throw error;
      if (error instanceof DOMException && error.name === 'AbortError') {
        lastError = new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request timed out', 504, true);
      } else if (error instanceof Error) {
        lastError = error;
      } else {
        lastError = new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request failed', 503, true);
      }
      if (attempt >= maxRetries) {
        if (lastError instanceof ServiceError) throw lastError;
        throw new ServiceError('GEMINI_UNAVAILABLE', lastError.message || 'Gemini request failed', 503, true);
      }
    } finally {
      clearTimeout(timeout);
    }
  }

  throw lastError || new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request failed', 503, true);
}

export async function interpretQuery(env: RecommendationEnv, query: string, mediaType: MediaType): Promise<InterpretedIntent> {
  if (!query.trim()) {
    return {
      hardFilters: { ...EMPTY_FILTERS }, requiredConceptGroups: [], softConcepts: [], excludedConcepts: [],
      excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
      genreHints: [], toneAndMood: [], broadSearchPhrases: [],
      seedTitles: [],
    };
  }

  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';

  try {
    const data = await geminiFetch<GeminiGenerationResponse>(env, model, 'generateContent', {
      systemInstruction: { parts: [{ text: INTERPRET_V3_PROMPT }] },
      contents: [{ role: 'user', parts: [{ text: JSON.stringify({ query, authoritativeMediaType: mediaType }) }] }],
      generationConfig: { temperature: .2, responseMimeType: 'application/json', responseJsonSchema: GeminiIntentJsonSchema },
    }, 15_000);

    const text = data.candidates?.[0]?.content?.parts?.find(part => typeof part.text === 'string')?.text;
    if (typeof text === 'string' && text) {
      return GeminiIntentResponseSchema.parse(JSON.parse(text));
    }
  } catch (err) {
    console.warn(`[Gemini] Model ${model} interpretation error:`, err instanceof Error ? err.message : err);
  }

  // Gracefully fallback to keyword extraction so the user search never fails with 503 or timeout
  console.warn(`[Gemini] Applying robust keyword fallback for query: "${query}"`);
  return fallbackIntentFromQuery(query);
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
  const data = await geminiFetch<GeminiGenerationResponse>(env, model, 'generateContent', {
    systemInstruction: { parts: [{ text: VERIFY_PREMISE_PROMPT }] },
    contents: [{ role: 'user', parts: [{ text: JSON.stringify({
      query,
      authoritativeMediaType: mediaType,
      requiredConceptGroups: requiredConceptGroups.map((group, index) => ({
        index,
        label: group.label,
        synonyms: group.synonyms,
      })),
      candidates,
    }) }] }],
    generationConfig: {
      temperature: .1,
      responseMimeType: 'application/json',
      responseJsonSchema: GeminiPremiseAssessmentJsonSchema,
    },
  }, 20_000);
  const text = data.candidates?.[0]?.content?.parts?.find(part => typeof part.text === 'string')?.text;
  if (typeof text !== 'string' || !text) {
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned no premise assessments', 502, true);
  }
  const parsed = GeminiPremiseAssessmentResponseSchema.parse(JSON.parse(text));
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parsed.assessments.filter(assessment => validIndexes.has(assessment.index));
}

async function embedBatch(env: RecommendationEnv, texts: string[], taskType: 'RETRIEVAL_QUERY' | 'RETRIEVAL_DOCUMENT'): Promise<number[][]> {
  const model = env.GEMINI_EMBEDDING_MODEL || 'gemini-embedding-001';
  const data = await geminiFetch<GeminiEmbeddingResponse>(env, model, 'batchEmbedContents', {
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
