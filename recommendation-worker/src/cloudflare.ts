import {
  EDITORIAL_RECOMMENDATIONS_PROMPT,
  INTERPRET_V3_PROMPT,
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

export const CLOUDFLARE_MODEL = '@cf/openai/gpt-oss-120b';
export const CLOUDFLARE_GENERATION_MAX_TOKENS = 3_072;
export const CLOUDFLARE_EXPANSION_MAX_TOKENS = 4_096;
export const CLOUDFLARE_VERIFICATION_MAX_TOKENS = 2_048;
const CLOUDFLARE_INTENT_MAX_TOKENS = 1_536;

const EMPTY_FILTERS = {
  originCountries: [], includedGenres: [], excludedGenres: [], productionCompanyIds: [], excludedTmdbIds: [], excludedTitles: [],
};

function standardJsonSchema(schema: unknown): unknown {
  if (Array.isArray(schema)) return schema.map(standardJsonSchema);
  if (!schema || typeof schema !== 'object') return schema;
  const source = schema as Record<string, unknown>;
  const converted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(source)) {
    if (key === 'nullable') continue;
    converted[key] = key === 'type' && typeof value === 'string'
      ? value.toLowerCase()
      : standardJsonSchema(value);
  }
  if (source.nullable === true && typeof converted.type === 'string') {
    converted.type = [converted.type, 'null'];
  }
  if (converted.type === 'object' && converted.properties && typeof converted.properties === 'object') {
    converted.additionalProperties = false;
  }
  return converted;
}

function recommendationSchema(targetCount: number): unknown {
  const schema = standardJsonSchema(GeminiDescribeJsonSchema) as {
    properties?: { recommendations?: Record<string, unknown> };
  };
  const list = schema.properties?.recommendations;
  if (list) {
    list.minItems = Math.min(targetCount, targetCount >= 20 ? 20 : 1);
    list.maxItems = targetCount;
  }
  return schema;
}

function assessmentSchema(candidateCount: number): unknown {
  const schema = standardJsonSchema(GeminiPremiseAssessmentJsonSchema) as {
    properties?: { assessments?: Record<string, unknown> };
  };
  const list = schema.properties?.assessments;
  if (list) {
    list.minItems = candidateCount;
    list.maxItems = candidateCount;
  }
  return schema;
}

function cleanJsonText(raw: string): string {
  let cleaned = raw.trim();
  if (cleaned.startsWith('```json')) cleaned = cleaned.slice(7);
  else if (cleaned.startsWith('```')) cleaned = cleaned.slice(3);
  if (cleaned.endsWith('```')) cleaned = cleaned.slice(0, -3);
  return cleaned.trim();
}

function parseStructuredOutput<T>(operation: string, output: unknown): T {
  const envelope = output as {
    response?: unknown;
    result?: unknown;
    output_text?: unknown;
    choices?: Array<{ message?: { content?: unknown } }>;
  } | undefined;
  const payload = envelope?.response
    ?? envelope?.result
    ?? envelope?.output_text
    ?? envelope?.choices?.[0]?.message?.content
    ?? output;

  if (typeof payload === 'string' && payload.trim()) {
    try {
      return JSON.parse(cleanJsonText(payload)) as T;
    } catch (error) {
      console.warn(JSON.stringify({
        event: 'cloudflare_ai_json_parse_failed',
        provider: 'cloudflare',
        model: CLOUDFLARE_MODEL,
        operation,
        outputKeys: output && typeof output === 'object' ? Object.keys(output as Record<string, unknown>).slice(0, 12) : [],
        error: error instanceof Error ? error.message : String(error),
      }));
      throw new ServiceError('CLOUDFLARE_AI_UNAVAILABLE', 'Cloudflare AI returned malformed structured output.', 502, true);
    }
  }
  if (payload && typeof payload === 'object') return payload as T;

  console.warn(JSON.stringify({
    event: 'cloudflare_ai_empty_output',
    provider: 'cloudflare',
    model: CLOUDFLARE_MODEL,
    operation,
    outputKeys: output && typeof output === 'object' ? Object.keys(output as Record<string, unknown>).slice(0, 12) : [],
  }));
  throw new ServiceError('CLOUDFLARE_AI_UNAVAILABLE', 'Cloudflare AI returned no structured output.', 502, true);
}

function parseSchema<T>(operation: string, parse: () => T): T {
  try {
    return parse();
  } catch (error) {
    console.warn(JSON.stringify({
      event: 'cloudflare_ai_schema_validation_failed',
      provider: 'cloudflare',
      model: CLOUDFLARE_MODEL,
      operation,
      error: error instanceof ZodError ? error.issues.slice(0, 4) : String(error),
    }));
    throw new ServiceError('CLOUDFLARE_AI_UNAVAILABLE', 'Cloudflare AI returned invalid structured output.', 502, true);
  }
}

async function cloudflareStructuredContent<T>(
  env: RecommendationEnv,
  systemInstruction: string,
  input: unknown,
  schema: unknown,
  operation: string,
  maxTokens: number,
  temperature: number,
  reasoningEffort: 'low' | 'medium' = 'low',
): Promise<T> {
  if (!env.AI?.run) {
    throw new ServiceError(
      'CLOUDFLARE_AI_NOT_CONFIGURED',
      'Cloudflare Workers AI is not configured on the recommendation service.',
      503,
      false,
    );
  }
  const startedAt = Date.now();
  try {
    const output = await env.AI.run(CLOUDFLARE_MODEL, {
      messages: [
        { role: 'system', content: systemInstruction },
        { role: 'user', content: JSON.stringify(input) },
      ],
      response_format: {
        type: 'json_schema',
        json_schema: {
          name: `aliflix_${operation.replace(/[^a-z0-9]+/gi, '_').toLowerCase()}`,
          strict: true,
          schema: standardJsonSchema(schema) as Record<string, unknown>,
        },
      },
      reasoning_effort: reasoningEffort,
      max_tokens: maxTokens,
      temperature,
      top_p: 0.9,
      seed: 314159,
    });
    console.log(JSON.stringify({
      event: 'cloudflare_ai_request_completed',
      provider: 'cloudflare',
      model: CLOUDFLARE_MODEL,
      operation,
      reasoningEffort,
      maxTokens,
      elapsedMs: Date.now() - startedAt,
    }));
    return parseStructuredOutput<T>(operation, output);
  } catch (error) {
    if (error instanceof ServiceError) throw error;
    const message = error instanceof Error ? error.message : String(error);
    const retryable = /rate|limit|capacity|busy|tempor|timeout|overload|unavailable|internal/i.test(message);
    console.warn(JSON.stringify({
      event: 'cloudflare_ai_request_failed',
      provider: 'cloudflare',
      model: CLOUDFLARE_MODEL,
      operation,
      retryable,
      elapsedMs: Date.now() - startedAt,
      error: message.slice(0, 300),
    }));
    throw new ServiceError(
      'CLOUDFLARE_AI_UNAVAILABLE',
      retryable
        ? 'Cloudflare AI is temporarily unavailable. Please try again.'
        : 'Cloudflare AI could not complete this recommendation request.',
      retryable ? 503 : 502,
      retryable,
    );
  }
}

function uniqueRecommendations(items: DescribeRecommendation[], excludedTitles: string[]): DescribeRecommendation[] {
  const excluded = new Set(excludedTitles.map(title => title.trim().toLocaleLowerCase()));
  const seen = new Set<string>();
  return items.filter(item => {
    const titleKey = item.title.trim().toLocaleLowerCase();
    const key = `${titleKey}:${item.releaseYear}`;
    if (excluded.has(titleKey) || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export async function interpretQueryWithCloudflare(
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
  const data = await cloudflareStructuredContent<unknown>(
    env,
    INTERPRET_V3_PROMPT,
    { query, authoritativeMediaType: mediaType },
    GeminiIntentJsonSchema,
    'query interpretation',
    CLOUDFLARE_INTENT_MAX_TOKENS,
    0.2,
  );
  return parseSchema('query interpretation', () => GeminiIntentResponseSchema.parse(data));
}

export async function recommendDescribeTitlesWithCloudflare(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
  targetCount = 24,
): Promise<DescribeRecommendation[]> {
  const boundedTarget = Math.min(24, Math.max(1, targetCount));
  const data = await cloudflareStructuredContent<unknown>(
    env,
    EDITORIAL_RECOMMENDATIONS_PROMPT,
    {
      query,
      currentDate: new Date().toISOString().slice(0, 10),
      authoritativeMediaType: mediaType,
      explicitFilters,
      targetCount: boundedTarget,
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    recommendationSchema(boundedTarget),
    'Describe candidate generation',
    excludedTitles.length > 0 ? CLOUDFLARE_EXPANSION_MAX_TOKENS : CLOUDFLARE_GENERATION_MAX_TOKENS,
    0.25,
    'low',
  );
  const parsed = parseSchema(
    'Describe candidate generation',
    () => GeminiDescribeResponseSchema.parse(data),
  );
  return uniqueRecommendations(parsed.recommendations, excludedTitles);
}

export async function recommendSimilarTitlesWithCloudflare(
  env: RecommendationEnv,
  anchors: SimilarAnchorDocument[],
  mediaType: MediaType,
  refinement: string,
  explicitFilters: unknown,
  excludedTitles: string[] = [],
  targetCount = 24,
): Promise<DescribeRecommendation[]> {
  const boundedTarget = Math.min(24, Math.max(1, targetCount));
  const data = await cloudflareStructuredContent<unknown>(
    env,
    EDITORIAL_RECOMMENDATIONS_PROMPT,
    {
      anchors,
      currentDate: new Date().toISOString().slice(0, 10),
      authoritativeMediaType: mediaType,
      refinement,
      explicitFilters,
      targetCount: boundedTarget,
      expansionPass: excludedTitles.length > 0,
      excludedTitles,
    },
    recommendationSchema(boundedTarget),
    'Similar candidate generation',
    excludedTitles.length > 0 ? CLOUDFLARE_EXPANSION_MAX_TOKENS : CLOUDFLARE_GENERATION_MAX_TOKENS,
    0.25,
    'low',
  );
  const parsed = parseSchema(
    'Similar candidate generation',
    () => GeminiDescribeResponseSchema.parse(data),
  );
  return uniqueRecommendations(parsed.recommendations, excludedTitles);
}

export async function assessPremiseCandidatesWithCloudflare(
  env: RecommendationEnv,
  query: string,
  mediaType: MediaType,
  requiredConceptGroups: InterpretedIntent['requiredConceptGroups'],
  candidates: PremiseCandidateDocument[],
): Promise<PremiseAssessment[]> {
  if (!candidates.length) return [];
  const data = await cloudflareStructuredContent<unknown>(
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
    assessmentSchema(candidates.length),
    'premise verification',
    CLOUDFLARE_VERIFICATION_MAX_TOKENS,
    0,
  );
  const parsed = parseSchema(
    'premise verification',
    () => GeminiPremiseAssessmentResponseSchema.parse(data),
  );
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parsed.assessments.filter(assessment => validIndexes.has(assessment.index));
}

export async function assessSimilarCandidatesWithCloudflare(
  env: RecommendationEnv,
  anchors: SimilarAnchorDocument[],
  refinement: string,
  mediaType: MediaType,
  candidates: PremiseCandidateDocument[],
): Promise<PremiseAssessment[]> {
  if (!candidates.length) return [];
  const data = await cloudflareStructuredContent<unknown>(
    env,
    VERIFY_SIMILARITY_PROMPT,
    { anchors, refinement, authoritativeMediaType: mediaType, candidates },
    assessmentSchema(candidates.length),
    'similarity verification',
    CLOUDFLARE_VERIFICATION_MAX_TOKENS,
    0,
  );
  const parsed = parseSchema(
    'similarity verification',
    () => GeminiPremiseAssessmentResponseSchema.parse(data),
  );
  const validIndexes = new Set(candidates.map(candidate => candidate.index));
  return parsed.assessments.filter(assessment => validIndexes.has(assessment.index));
}
