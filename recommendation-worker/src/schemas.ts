import { z } from 'zod';

const mediaType = z.enum(['movie', 'tv']);
const trimmed = z.string().trim();

const RecommendationFiltersObjectSchema = z.object({
  minimumYear: z.number().int().min(1870).max(2200).nullish().transform(value => value ?? undefined),
  maximumYear: z.number().int().min(1870).max(2200).nullish().transform(value => value ?? undefined),
  originalLanguage: trimmed.min(2).max(12).nullish().transform(value => value ?? undefined),
  originCountries: z.array(trimmed.min(2).max(3)).max(12).default([]),
  minimumRuntimeMinutes: z.number().int().min(1).max(1000).nullish().transform(value => value ?? undefined),
  maximumRuntimeMinutes: z.number().int().min(1).max(1000).nullish().transform(value => value ?? undefined),
  includedGenres: z.array(trimmed.min(1).max(80)).max(20).default([]),
  excludedGenres: z.array(trimmed.min(1).max(80)).max(20).default([]),
  minimumTmdbRating: z.number().min(0).max(10).nullish().transform(value => value ?? undefined),
  excludedTmdbIds: z.array(z.number().int().positive()).max(100).default([]),
  excludedTitles: z.array(trimmed.min(1).max(200)).max(100).default([]),
});

export const RecommendationFiltersSchema = RecommendationFiltersObjectSchema.superRefine((value, ctx) => {
  if (value.minimumYear && value.maximumYear && value.minimumYear > value.maximumYear) {
    ctx.addIssue({ code: 'custom', message: 'minimumYear must not exceed maximumYear' });
  }
  if (value.minimumRuntimeMinutes && value.maximumRuntimeMinutes && value.minimumRuntimeMinutes > value.maximumRuntimeMinutes) {
    ctx.addIssue({ code: 'custom', message: 'minimumRuntimeMinutes must not exceed maximumRuntimeMinutes' });
  }
});

export const RecommendationRequestSchema = z.object({
  requestId: z.string().uuid(),
  mode: z.enum(['describe', 'similar', 'filters']).default('describe'),
  query: z.string().trim().max(2000).default(''),
  mediaType,
  anchor: z.object({
    tmdbId: z.number().int().positive(),
    title: trimmed.min(1).max(300),
    mediaType,
  }).optional(),
  filters: RecommendationFiltersObjectSchema.default({
    minimumYear: undefined, maximumYear: undefined, originalLanguage: undefined, originCountries: [],
    minimumRuntimeMinutes: undefined, maximumRuntimeMinutes: undefined, includedGenres: [], excludedGenres: [],
    minimumTmdbRating: undefined, excludedTmdbIds: [], excludedTitles: [],
  }),
  pageSize: z.number().int().min(1).max(40).default(20),
  cursor: z.string().min(1).max(2048).optional(),
}).superRefine((value, ctx) => {
  if (value.mode === 'similar' && !value.anchor) {
    ctx.addIssue({ code: 'custom', path: ['anchor'], message: 'Similar requests require an anchor' });
  }
  if (value.mode === 'describe' && !value.query) {
    ctx.addIssue({ code: 'custom', path: ['query'], message: 'Describe requests require a query' });
  }
});

export const ConceptGroupSchema = z.object({
  label: trimmed.min(1).max(120),
  synonyms: z.array(trimmed.min(1).max(120)).min(1).max(12),
  weight: z.number().min(0).max(10),
});

export const GeminiIntentResponseSchema = z.object({
  hardFilters: RecommendationFiltersObjectSchema.default({
    minimumYear: undefined, maximumYear: undefined, originalLanguage: undefined, originCountries: [],
    minimumRuntimeMinutes: undefined, maximumRuntimeMinutes: undefined, includedGenres: [], excludedGenres: [],
    minimumTmdbRating: undefined, excludedTmdbIds: [], excludedTitles: [],
  }),
  requiredConceptGroups: z.array(ConceptGroupSchema).max(8).default([]),
  softConcepts: z.array(trimmed.min(1).max(120)).max(20).default([]),
  excludedConcepts: z.array(trimmed.min(1).max(120)).max(20).default([]),
  genreHints: z.array(trimmed.min(1).max(80)).max(12).default([]),
  toneAndMood: z.array(trimmed.min(1).max(80)).max(12).default([]),
  broadSearchPhrases: z.array(trimmed.min(1).max(120)).max(20).default([]),
});

export const GeminiIntentJsonSchema = {
  type: 'OBJECT',
  properties: {
    hardFilters: {
      type: 'OBJECT',
      properties: {
        minimumYear: { type: 'INTEGER', nullable: true },
        maximumYear: { type: 'INTEGER', nullable: true },
        originalLanguage: { type: 'STRING', nullable: true },
        originCountries: { type: 'ARRAY', items: { type: 'STRING' } },
        minimumRuntimeMinutes: { type: 'INTEGER', nullable: true },
        maximumRuntimeMinutes: { type: 'INTEGER', nullable: true },
        includedGenres: { type: 'ARRAY', items: { type: 'STRING' } },
        excludedGenres: { type: 'ARRAY', items: { type: 'STRING' } },
        minimumTmdbRating: { type: 'NUMBER', nullable: true },
        excludedTmdbIds: { type: 'ARRAY', items: { type: 'INTEGER' } },
        excludedTitles: { type: 'ARRAY', items: { type: 'STRING' } },
      },
      required: ['originCountries', 'includedGenres', 'excludedGenres', 'excludedTmdbIds', 'excludedTitles'],
    },
    requiredConceptGroups: {
      type: 'ARRAY', items: { type: 'OBJECT', properties: {
        label: { type: 'STRING' }, synonyms: { type: 'ARRAY', items: { type: 'STRING' } }, weight: { type: 'NUMBER' },
      }, required: ['label', 'synonyms', 'weight'] },
    },
    softConcepts: { type: 'ARRAY', items: { type: 'STRING' } },
    excludedConcepts: { type: 'ARRAY', items: { type: 'STRING' } },
    genreHints: { type: 'ARRAY', items: { type: 'STRING' } },
    toneAndMood: { type: 'ARRAY', items: { type: 'STRING' } },
    broadSearchPhrases: { type: 'ARRAY', items: { type: 'STRING' } },
  },
  required: ['hardFilters', 'requiredConceptGroups', 'softConcepts', 'excludedConcepts', 'genreHints', 'toneAndMood', 'broadSearchPhrases'],
} as const;

export type ParsedRecommendationRequest = z.infer<typeof RecommendationRequestSchema>;
