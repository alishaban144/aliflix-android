import { INTERPRET_V3_PROMPT } from './prompts';
import { GeminiIntentJsonSchema, GeminiIntentResponseSchema } from './schemas';
import { InterpretedIntent, MediaType, RecommendationEnv, ServiceError } from './types';

const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models';
const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [],
};

async function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export function fallbackIntentFromQuery(query: string): InterpretedIntent {
  const words = query
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .split(/\s+/)
    .filter(w => w.length >= 3 && !['the', 'and', 'with', 'for', 'about', 'like', 'show', 'movie', 'series'].includes(w));
  return {
    hardFilters: { ...EMPTY_FILTERS },
    requiredConceptGroups: words.length ? [{ label: 'query_terms', synonyms: words.slice(0, 5), weight: 1 }] : [],
    softConcepts: [],
    excludedConcepts: [],
    excludedKeywords: [],
    crewNames: [],
    castNames: [],
    studioNames: [],
    certifications: [],
    genreHints: [],
    toneAndMood: [],
    broadSearchPhrases: [query.slice(0, 60).trim()].filter(Boolean),
  };
}

async function geminiFetch(env: RecommendationEnv, model: string, method: string, body: unknown, timeoutMs: number): Promise<any> {
  if (!env.GEMINI_API_KEY) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini is not configured', 503, true);

  const maxRetries = 2;
  let lastError: Error | undefined;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    if (attempt > 0) {
      await sleep(300 * Math.pow(2, attempt - 1) + Math.random() * 100);
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${API_BASE}/${encodeURIComponent(model)}:${method}?key=${encodeURIComponent(env.GEMINI_API_KEY)}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
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
      return await response.json();
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
    };
  }

  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.7-flash';

  try {
    const data = await geminiFetch(env, model, 'generateContent', {
      systemInstruction: { parts: [{ text: INTERPRET_V3_PROMPT }] },
      contents: [{ role: 'user', parts: [{ text: JSON.stringify({ query, authoritativeMediaType: mediaType }) }] }],
      generationConfig: { responseMimeType: 'application/json', responseJsonSchema: GeminiIntentJsonSchema },
    }, 15_000);

    const text = data?.candidates?.[0]?.content?.parts?.find((part: any) => typeof part.text === 'string')?.text;
    if (text) {
      return GeminiIntentResponseSchema.parse(JSON.parse(text));
    }
  } catch (err) {
    console.warn(`[Gemini] Model ${model} interpretation error:`, err instanceof Error ? err.message : err);
  }

  // Gracefully fallback to keyword extraction so the user search never fails with 503 or timeout
  console.warn(`[Gemini] Applying robust keyword fallback for query: "${query}"`);
  return fallbackIntentFromQuery(query);
}

async function embedBatch(env: RecommendationEnv, texts: string[], taskType: 'RETRIEVAL_QUERY' | 'RETRIEVAL_DOCUMENT'): Promise<number[][]> {
  const model = env.GEMINI_EMBEDDING_MODEL || 'gemini-embedding-2';
  const data = await geminiFetch(env, model, 'batchEmbedContents', {
    requests: texts.map(text => ({
      model: `models/${model}`, taskType, outputDimensionality: 768,
      content: { parts: [{ text }] },
    })),
  }, 10_000);
  const embeddings = data?.embeddings;
  if (!Array.isArray(embeddings) || embeddings.length !== texts.length) {
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned an incomplete embedding batch', 502, true);
  }
  return embeddings.map((entry: any) => {
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
): Promise<{ queryVector: number[]; candidateVectors: number[][] }> {
  const [queryVectors, candidateVectors] = await Promise.all([
    embedBatch(env, [query], 'RETRIEVAL_QUERY'),
    embedBatch(env, candidateTexts, 'RETRIEVAL_DOCUMENT'),
  ]);
  const queryVector = queryVectors[0];
  if (!queryVector) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini query embedding was empty', 502, true);
  return { queryVector, candidateVectors };
}
