import {
  assessPremiseCandidates,
  assessSimilarCandidates,
  fallbackIntentFromQuery,
  interpretQuery as interpretQueryWithGemini,
  recommendDescribeTitles as recommendDescribeTitlesWithGemini,
  recommendSimilarTitles as recommendSimilarTitlesWithGemini,
} from './gemini';
import {
  assessPremiseCandidatesWithCloudflare,
  assessSimilarCandidatesWithCloudflare,
  interpretQueryWithCloudflare,
  recommendDescribeTitlesWithCloudflare,
  recommendSimilarTitlesWithCloudflare,
} from './cloudflare';
import {
  assessPremiseCandidatesWithGroq,
  assessSimilarCandidatesWithGroq,
  interpretQueryWithGroq,
  recommendDescribeTitlesWithGroq,
  recommendSimilarTitlesWithGroq,
} from './groq';
import {
  CloudflareAiModel,
  GeminiAiModel,
  GroqAiModel,
  RecommendationAiModel,
  RecommendationEnv,
  ServiceError,
} from './types';

export { embedForSearch } from './gemini';
export { fallbackIntentFromQuery };

export const DEFAULT_AI_MODEL: RecommendationAiModel = 'cloudflare-gpt-oss-120b';

export function selectedAiModel(env: RecommendationEnv): RecommendationAiModel {
  return env.AI_GENERATION_MODEL || env.GEMINI_GENERATION_MODEL || DEFAULT_AI_MODEL;
}

export function isCloudflareAiModel(model: RecommendationAiModel): model is CloudflareAiModel {
  return model === 'cloudflare-gpt-oss-120b';
}

export function isGroqAiModel(model: RecommendationAiModel): model is GroqAiModel {
  // Any groq-* id (including values persisted by older clients) routes to Groq,
  // which exclusively serves GPT-OSS 120B. Never silently fall back to Gemini.
  return model.startsWith('groq-');
}

export function aiProviderName(model: RecommendationAiModel): 'cloudflare' | 'gemini' | 'groq' {
  if (isCloudflareAiModel(model)) return 'cloudflare';
  return isGroqAiModel(model) ? 'groq' : 'gemini';
}

export function isRetryableAiProviderError(error: unknown): error is ServiceError {
  return error instanceof ServiceError
    && error.retryable
    && (error.code === 'CLOUDFLARE_AI_UNAVAILABLE' || error.code === 'GEMINI_UNAVAILABLE' || error.code === 'GROQ_UNAVAILABLE');
}

function geminiEnv(env: RecommendationEnv): RecommendationEnv {
  return { ...env, GEMINI_GENERATION_MODEL: selectedAiModel(env) as GeminiAiModel };
}

export async function interpretQuery(...args: Parameters<typeof interpretQueryWithGemini>) {
  const model = selectedAiModel(args[0]);
  if (isCloudflareAiModel(model)) return interpretQueryWithCloudflare(...args);
  return isGroqAiModel(model)
    ? interpretQueryWithGroq(...args)
    : interpretQueryWithGemini(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function recommendDescribeTitles(...args: Parameters<typeof recommendDescribeTitlesWithGemini>) {
  const model = selectedAiModel(args[0]);
  if (isCloudflareAiModel(model)) return recommendDescribeTitlesWithCloudflare(...args);
  return isGroqAiModel(model)
    ? recommendDescribeTitlesWithGroq(...args)
    : recommendDescribeTitlesWithGemini(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function recommendSimilarTitles(...args: Parameters<typeof recommendSimilarTitlesWithGemini>) {
  const model = selectedAiModel(args[0]);
  if (isCloudflareAiModel(model)) return recommendSimilarTitlesWithCloudflare(...args);
  return isGroqAiModel(model)
    ? recommendSimilarTitlesWithGroq(...args)
    : recommendSimilarTitlesWithGemini(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function assessRecommendationPremise(...args: Parameters<typeof assessPremiseCandidates>) {
  const model = selectedAiModel(args[0]);
  if (isCloudflareAiModel(model)) return assessPremiseCandidatesWithCloudflare(...args);
  return isGroqAiModel(model)
    ? assessPremiseCandidatesWithGroq(...args)
    : assessPremiseCandidates(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function assessRecommendationSimilarity(...args: Parameters<typeof assessSimilarCandidates>) {
  const model = selectedAiModel(args[0]);
  if (isCloudflareAiModel(model)) return assessSimilarCandidatesWithCloudflare(...args);
  return isGroqAiModel(model)
    ? assessSimilarCandidatesWithGroq(...args)
    : assessSimilarCandidates(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}
