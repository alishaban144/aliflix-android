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
import { ZodError } from 'zod';

const GROQ_API_URL = 'https://api.groq.com/openai/v1/chat/completions';
export const GROQ_MODEL = 'qwen/qwen3.8-27b';
export const GROQ_TIMEOUT_MS = 24_000;
export const GROQ_MAX_OUTPUT_TOKENS = 3_072;
export const GROQ_VERIFICATION_MAX_OUTPUT_TOKENS = 1_536;

const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], excludedTmdbIds: [], excludedTitles: [],
};

interface GroqChatCompletionResponse {
  choices?: Array<{
    finish_reason?: string;
    message?: { content?: unknown; refusal?: string | null };
  }>;
}

function strictJsonSchema(schema: unknown): unknown {
  if (Array.isArray(schema)) return schema.map(strictJsonSchema);
  if (!schema || typeof schema !== 'object') return schema;
  const source = schema as Record<string, unknown>;
  const converted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(source)) {
    if (key === 'nullable') continue;
    converted[key] = key === 'type' && typeof value === 'string'
      ? value.toLowerCase()
      : strictJsonSchema(value);
  }
  if (source.nullable === true && typeof converted.type === 'string') {
    converted.type = [converted.type, 'null'];
  }
  if (converted.type === 'object' && converted.properties && typeof converted.properties === 'object') {
    converted.additionalProperties = false;
    converted.required = Object.keys(converted.properties as Record<string, unknown>);
  }
  return converted;
}

function cleanJsonText(raw: string): string {
  let cleaned = raw.trim();
  if (cleaned.startsWith('```json')) cleaned = cleaned.slice(7);
  else if (cleaned.startsWith('```')) cleaned = cleaned.slice(3);
  if (cleaned.endsWith('```')) cleaned = cleaned.slice(0, -3);
  return cleaned.trim();
}

function parseGroqOutput<T>(operation: string, parse: () => T): T {
  try {
    return parse();
  } catch (error) {
    console.warn(JSON.stringify({
      event: 'groq_schema_validation_failed',
      operation,
      error: error instanceof ZodError ? error.issues.slice(0, 4) : String(error),
    }));
    throw new ServiceError('GROQ_UNAVAILABLE', 'Groq returned invalid structured output.', 502, true);
  }
}

function boundAssessmentReasons(data: unknown): unknown {
  if (!data || typeof data !== 'object') return data;
  const source = data as { assessments?: unknown };
  if (!Array.isArray(source.assessments)) return data;
  return {
    ...source,
    assessments: source.assessments.map(assessment => {
      if (!assessment || typeof assessment !== 'object') return assessment;
      const item = assessment as Record<string, unknown>;
      if (typeof item.reason !== 'string' || item.reason.length <= 180) return item;
      const bounded = item.reason.slice(0, 180);
      const lastSpace = bounded.lastIndexOf(' ');
      return { ...item, reason: (lastSpace >= 120 ? bounded.slice(0, lastSpace) : bounded).trim() };
    }),
  };
}

async function groqStructuredContent<T>(
  env: RecommendationEnv,
  systemInstruction: string,
  input: unknown,
  schema: unknown,
  schemaName: string,
  operation: string,
  maxOutputTokens = GROQ_MAX_OUTPUT_TOKENS,
  reasoningEffort: 'none' | 'low' = 'low',
  temperature = 0.6,
): Promise<T> {
  if (!env.GROQ_API_KEY) {
    throw new ServiceError(
      'GROQ_NOT_CONFIGURED',
      'Groq is not configured on the recommendation service yet.',
      503,
      false,
    );
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), GROQ_TIMEOUT_MS);
  const startedAt = Date.now();
  try {
    const response = await fetch(GROQ_API_URL, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${env.GROQ_API_KEY}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: GROQ_MODEL,
        // Groq recommends placing Qwen reasoning instructions in the user
        // message and hiding reasoning when structured output is requested.
        messages: [{
          role: 'user',
          content: `${systemInstruction}\n\nInput JSON:\n${JSON.stringify(input)}`,
        }],
        response_format: {
          type: 'json_schema',
          json_schema: {
            name: schemaName,
            strict: true,
            schema: strictJsonSchema(schema),
          },
        },
        reasoning_effort: reasoningEffort,
        reasoning_format: 'hidden',
        temperature,
        max_completion_tokens: maxOutputTokens,
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      const retryable = response.status === 408 || response.status === 429 || response.status >= 500;
      const providerError = await response.json().catch(() => undefined) as {
        error?: { code?: string; type?: string; message?: string };
      } | undefined;
      console.warn(JSON.stringify({
        event: 'groq_request_failed',
        operation,
        status: response.status,
        providerCode: providerError?.error?.code,
        providerType: providerError?.error?.type,
        providerMessage: providerError?.error?.message?.slice(0, 300),
        retryable,
        elapsedMs: Date.now() - startedAt,
      }));
      throw new ServiceError(
        'GROQ_UNAVAILABLE',
        retryable ? 'Groq is temporarily unavailable. Please try again.' : 'Groq rejected the recommendation request.',
        retryable ? 503 : 502,
        retryable,
      );
    }

    const data = await response.json() as GroqChatCompletionResponse;
    const choice = data.choices?.[0];
    const text = choice?.message?.content;
    if (choice?.message?.refusal) {
      throw new ServiceError('GROQ_UNAVAILABLE', 'Groq could not complete this recommendation request.', 502, false);
    }
    if (typeof text !== 'string' || !text.trim()) {
      throw new ServiceError(
        'GROQ_UNAVAILABLE',
        `Groq returned no structured output (${choice?.finish_reason || 'unknown'})`,
        502,
        true,
      );
    }
    try {
      return JSON.parse(cleanJsonText(text)) as T;
    } catch (error) {
      console.warn(JSON.stringify({
        event: 'groq_json_parse_failed',
        operation,
        finishReason: choice?.finish_reason,
        error: error instanceof Error ? error.message : String(error),
      }));
      throw new ServiceError('GROQ_UNAVAILABLE', 'Groq returned malformed structured output.', 502, true);
    }
  } catch (error) {
    if (error instanceof ServiceError) throw error;
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ServiceError('GROQ_UNAVAILABLE', `Groq ${operation} timed out`, 504, true);
    }
    console.warn(JSON.stringify({
      event: 'groq_network_failed',
      operation,
      error: error instanceof Error ? error.message.slice(0, 300) : String(error),
    }));
    throw new ServiceError('GROQ_UNAVAILABLE', 'Groq request failed.', 503, true);
  } finally {
    clearTimeout(timeout);
  }
}

export async function interpretQueryWithGroq(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
): Promise<InterpretedIntent> {
  if (!query.trim()) {
    return {
      hardFilters: { ...EMPTY_FILTERS }, requiredConceptGroups: [], softConcepts: [], excludedConcepts: [],
      excludedKeywords: [], crewNames: [], castNames: [], studioNames: [], certifications: [],
      genreHints: [], toneAndMood: [], broadSearchPhrases: [],
    };
  }
  const data = await groqStructuredContent<unknown>(
    env,
    INTERPRET_V3_PROMPT,
    { query, authoritativeMediaType: mediaType },
    GeminiIntentJsonSchema,
    'recommendation_intent',
    'query interpretation',
    4_096,
  );
  return parseGroqOutput('query interpretation', () => GeminiIntentResponseSchema.parse(data));
}

export async function recommendDescribeTitlesWithGroq(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
  targetCount = 12,
): Promise<DescribeRecommendation[]> {
  const data = await groqStructuredContent<unknown>(
    env,
    DESCRIBE_RECOMMENDATIONS_PROMPT,
    {
      query,
      authoritativeMediaType: mediaType,
      explicitFilters,
      targetCount: Math.min(16, Math.max(1, targetCount)),
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    GeminiDescribeJsonSchema,
    'describe_recommendations',
    'Describe candidate generation',
  );
  return uniqueRecommendations(parseGroqOutput(
    'Describe candidate generation',
    () => GeminiDescribeResponseSchema.parse(data),
  ).recommendations);
}

export async function recommendSimilarTitlesWithGroq(
  env: RecommendationEnv,
  anchors: SimilarAnchorDocument[],
  mediaType: MediaType,
  refinement: string,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
  targetCount = 12,
): Promise<DescribeRecommendation[]> {
  const data = await groqStructuredContent<unknown>(
    env,
    SIMILAR_RECOMMENDATIONS_PROMPT,
    {
      anchors,
      authoritativeMediaType: mediaType,
      refinement,
      explicitFilters,
      targetCount: Math.min(16, Math.max(1, targetCount)),
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    GeminiDescribeJsonSchema,
    'similar_recommendations',
    'Similar candidate generation',
  );
  return uniqueRecommendations(parseGroqOutput(
    'Similar candidate generation',
    () => GeminiDescribeResponseSchema.parse(data),
  ).recommendations);
}

function uniqueRecommendations(items: DescribeRecommendation[]): DescribeRecommendation[] {
  const seen = new Set<string>();
  return items.filter(item => {
    const key = `${item.title.toLocaleLowerCase()}:${item.releaseYear}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export async function assessPremiseCandidatesWithGroq(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  requiredConceptGroups: InterpretedIntent['requiredConceptGroups'],
  candidates: PremiseCandidateDocument[],
): Promise<PremiseAssessment[]> {
  if (!candidates.length) return [];
  const data = await groqStructuredContent<unknown>(
    env,
    VERIFY_PREMISE_PROMPT,
    {
      query,
      authoritativeMediaType: mediaType,
      requiredConceptGroups: requiredConceptGroups.map((group, index) => ({
        index, label: group.label, synonyms: group.synonyms,
      })),
      candidates,
    },
    GeminiPremiseAssessmentJsonSchema,
    'premise_assessments',
    'premise verification',
    GROQ_VERIFICATION_MAX_OUTPUT_TOKENS,
    'none',
    0.2,
  );
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parseGroqOutput(
    'premise verification',
    () => GeminiPremiseAssessmentResponseSchema.parse(boundAssessmentReasons(data)),
  ).assessments
    .filter(assessment => validIndexes.has(assessment.index));
}

export async function assessSimilarCandidatesWithGroq(
  env: RecommendationEnv,
  anchors: SimilarAnchorDocument[],
  refinement: string,
  mediaType: MediaType,
  candidates: PremiseCandidateDocument[],
): Promise<PremiseAssessment[]> {
  if (!candidates.length) return [];
  const data = await groqStructuredContent<unknown>(
    env,
    VERIFY_SIMILARITY_PROMPT,
    { anchors, refinement, authoritativeMediaType: mediaType, candidates },
    GeminiPremiseAssessmentJsonSchema,
    'similarity_assessments',
    'similarity verification',
    GROQ_VERIFICATION_MAX_OUTPUT_TOKENS,
    'none',
    0.2,
  );
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parseGroqOutput(
    'similarity verification',
    () => GeminiPremiseAssessmentResponseSchema.parse(boundAssessmentReasons(data)),
  ).assessments
    .filter(assessment => validIndexes.has(assessment.index));
}
