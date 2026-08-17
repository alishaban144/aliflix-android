import { INTERPRET_V3_PROMPT } from './prompts';
import { GeminiIntentJsonSchema, GeminiIntentResponseSchema } from './schemas';
import { InterpretedIntent, MediaType, RecommendationEnv, ServiceError } from './types';

const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models';
const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [],
};

async function geminiFetch(env: RecommendationEnv, model: string, method: string, body: unknown, timeoutMs: number): Promise<any> {
  if (!env.GEMINI_API_KEY) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini is not configured', 503, true);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${API_BASE}/${encodeURIComponent(model)}:${method}?key=${encodeURIComponent(env.GEMINI_API_KEY)}`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal: controller.signal,
    });
    if (!response.ok) {
      const retryable = response.status === 429 || response.status >= 500;
      throw new ServiceError('GEMINI_UNAVAILABLE', `Gemini request failed (${response.status})`, retryable ? 503 : 502, retryable);
    }
    return await response.json();
  } catch (error) {
    if (error instanceof ServiceError) throw error;
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request timed out', 504, true);
    }
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini request failed', 503, true);
  } finally {
    clearTimeout(timeout);
  }
}

export async function interpretQuery(env: RecommendationEnv, query: string, mediaType: MediaType): Promise<InterpretedIntent> {
  if (!query.trim()) return {
    hardFilters: { ...EMPTY_FILTERS }, requiredConceptGroups: [], softConcepts: [], excludedConcepts: [],
    genreHints: [], toneAndMood: [], broadSearchPhrases: [],
  };
  const model = env.GEMINI_GENERATION_MODEL || 'gemini-3.6-flash';
  const data = await geminiFetch(env, model, 'generateContent', {
    systemInstruction: { parts: [{ text: INTERPRET_V3_PROMPT }] },
    contents: [{ role: 'user', parts: [{ text: JSON.stringify({ query, authoritativeMediaType: mediaType }) }] }],
    generationConfig: { responseMimeType: 'application/json', responseJsonSchema: GeminiIntentJsonSchema },
  }, 12_000);
  const text = data?.candidates?.[0]?.content?.parts?.find((part: any) => typeof part.text === 'string')?.text;
  if (!text) throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned no interpretation', 502, true);
  try {
    return GeminiIntentResponseSchema.parse(JSON.parse(text));
  } catch {
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned an invalid interpretation', 502, true);
  }
}

async function embedBatch(env: RecommendationEnv, texts: string[], taskType: 'RETRIEVAL_QUERY' | 'RETRIEVAL_DOCUMENT'): Promise<number[][]> {
  const model = env.GEMINI_EMBEDDING_MODEL || 'gemini-embedding-2';
  const data = await geminiFetch(env, model, 'batchEmbedContents', {
    requests: texts.map(text => ({
      model: `models/${model}`, taskType, outputDimensionality: 768,
      content: { parts: [{ text }] },
    })),
  }, 15_000);
  if (!Array.isArray(data?.embeddings) || data.embeddings.length !== texts.length) {
    throw new ServiceError('GEMINI_UNAVAILABLE', 'Gemini returned invalid embeddings', 502, true);
  }
  return data.embeddings.map((entry: any) => entry.values as number[]);
}

export async function embedForSearch(env: RecommendationEnv, query: string, documents: string[]): Promise<{ query: number[]; documents: number[][] }> {
  const documentBatches: string[][] = [];
  for (let index = 0; index < documents.length; index += 50) {
    documentBatches.push(documents.slice(index, index + 50).map(text => `search document: ${text}`));
  }
  const embedDocuments = async (): Promise<number[][]> => {
    const vectors: number[][] = [];
    for (let index = 0; index < documentBatches.length; index += 3) {
      const group = await Promise.all(
        documentBatches.slice(index, index + 3)
          .map(batch => embedBatch(env, batch, 'RETRIEVAL_DOCUMENT')),
      );
      group.forEach(batchVectors => vectors.push(...batchVectors));
    }
    return vectors;
  };
  const [[queryVector], vectors] = await Promise.all([
    embedBatch(env, [`search query: ${query}`], 'RETRIEVAL_QUERY'),
    embedDocuments(),
  ]);
  return { query: queryVector, documents: vectors };
}
