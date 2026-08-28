import {
  assessPremiseCandidates,
  assessSimilarCandidates,
  fallbackIntentFromQuery,
  interpretQuery as interpretQueryWithGemini,
  recommendDescribeTitles as recommendDescribeTitlesWithGemini,
  recommendSimilarTitles as recommendSimilarTitlesWithGemini,
} from './gemini';
import {
  assessPremiseCandidatesWithGroq,
  assessSimilarCandidatesWithGroq,
  interpretQueryWithGroq,
  recommendDescribeTitlesWithGroq,
  recommendSimilarTitlesWithGroq,
} from './groq';
import {
  GeminiAiModel,
  GroqAiModel,
  RecommendationAiModel,
  RecommendationEnv,
  ServiceError,
} from './types';

export { embedForSearch } from './gemini';
export { fallbackIntentFromQuery };

export const DEFAULT_AI_MODEL: RecommendationAiModel = 'gemini-3.5-flash';

export function selectedAiModel(env: RecommendationEnv): RecommendationAiModel {
  return env.AI_GENERATION_MODEL || env.GEMINI_GENERATION_MODEL || DEFAULT_AI_MODEL;
}

export function isGroqAiModel(model: RecommendationAiModel): model is GroqAiModel {
  return model === 'groq-qwen-3.8-27b';
}

export function aiProviderName(model: RecommendationAiModel): 'gemini' | 'groq' {
  return isGroqAiModel(model) ? 'groq' : 'gemini';
}

export function isRetryableAiProviderError(error: unknown): error is ServiceError {
  return error instanceof ServiceError
    && error.retryable
    && (error.code === 'GEMINI_UNAVAILABLE' || error.code === 'GROQ_UNAVAILABLE');
}

function geminiEnv(env: RecommendationEnv): RecommendationEnv {
  return { ...env, GEMINI_GENERATION_MODEL: selectedAiModel(env) as GeminiAiModel };
}

export async function interpretQuery(...args: Parameters<typeof interpretQueryWithGemini>) {
  return isGroqAiModel(selectedAiModel(args[0]))
    ? interpretQueryWithGroq(...args)
    : interpretQueryWithGemini(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function recommendDescribeTitles(...args: Parameters<typeof recommendDescribeTitlesWithGemini>) {
  return isGroqAiModel(selectedAiModel(args[0]))
    ? recommendDescribeTitlesWithGroq(...args)
    : recommendDescribeTitlesWithGemini(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function recommendSimilarTitles(...args: Parameters<typeof recommendSimilarTitlesWithGemini>) {
  return isGroqAiModel(selectedAiModel(args[0]))
    ? recommendSimilarTitlesWithGroq(...args)
    : recommendSimilarTitlesWithGemini(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function assessRecommendationPremise(...args: Parameters<typeof assessPremiseCandidates>) {
  return isGroqAiModel(selectedAiModel(args[0]))
    ? assessPremiseCandidatesWithGroq(...args)
    : assessPremiseCandidates(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}

export async function assessRecommendationSimilarity(...args: Parameters<typeof assessSimilarCandidates>) {
  return isGroqAiModel(selectedAiModel(args[0]))
    ? assessSimilarCandidatesWithGroq(...args)
    : assessSimilarCandidates(...([geminiEnv(args[0]), ...args.slice(1)] as typeof args));
}
