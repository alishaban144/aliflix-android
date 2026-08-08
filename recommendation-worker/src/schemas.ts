import { z } from 'zod';

export const InterpretationRequestSchema = z.object({
  requestId: z.string(),
  query: z.string(),
  mediaType: z.enum(['movie', 'tv']),
    deterministicConstraints: z.object({
    genres: z.array(z.string()).default([]),
    moods: z.array(z.string()).default([]),
    themes: z.array(z.string()).default([]),
    yearRule: z.string().nullable().default(null),
    runtimeRule: z.string().nullable().default(null),
    minimumImdb: z.number().nullable().default(null),
    language: z.string().nullable().default(null),
    status: z.string().nullable().default(null),
    exclusions: z.array(z.string()).default([]),
    similarityTitle: z.string().nullable().default(null),
  }),
});

export const RequiredConceptGroupSchema = z.object({
  id: z.string(),
  label: z.string(),
  description: z.string(),
  alternatives: z.array(z.string()),
  specificSubtypes: z.array(z.string()),
  centralityRequired: z.boolean(),
});

export const InterpretationResponseSchema = z.object({
  requestId: z.string(),
  normalizedRequest: z.string(),
  summary: z.string(),
  requiredConceptGroups: z.array(RequiredConceptGroupSchema),
  optionalConcepts: z.array(z.string()),
  excludedConcepts: z.array(z.string()),
  genreHypotheses: z.array(z.string()),
  keywordSearchPhrases: z.array(z.string()),
  broadSearchPhrases: z.array(z.string()),
  hardConstraints: z.object({
    mediaType: z.enum(['movie', 'tv']),
    includedGenres: z.array(z.string()),
    excludedGenres: z.array(z.string()),
    minimumYear: z.number().nullable(),
    maximumYear: z.number().nullable(),
    maximumRuntimeMinutes: z.number().nullable(),
    minimumRating: z.number().nullable(),
    originalLanguage: z.string().nullable(),
  }),
  anchorTitle: z.string().nullable(),
  anchorModifiers: z.array(z.string()),
  contradictions: z.array(z.string()),
});

export const ExpansionRequestSchema = z.object({
  requestId: z.string(),
  originalQuery: z.string(),
  interpretation: InterpretationResponseSchema,
  coveredConcepts: z.array(z.string()),
  underrepresentedConcepts: z.array(z.string()),
  successfulSearchPhrases: z.array(z.string()),
  failedSearchPhrases: z.array(z.string()),
});

export const ExpansionResponseSchema = z.object({
  requestId: z.string(),
  additionalKeywordPhrases: z.array(z.string()),
  additionalPairwiseSearches: z.array(
    z.object({
      leftConcept: z.string(),
      rightConcept: z.string(),
    })
  ),
  additionalBroadPhrases: z.array(z.string()),
});

export const OmdbMetadataRequestSchema = z.object({
  imdbId: z.string().regex(/^tt\d{7,10}$/i).optional(),
  title: z.string().min(1).max(200).optional(),
  year: z.number().int().min(1880).max(2100).optional(),
  mediaType: z.enum(['movie', 'series']),
}).refine(data => !!(data.imdbId || data.title), {
  message: "Either imdbId or title must be provided"
});

export const OmdbBatchCandidateSchema = z.object({
  candidateId: z.string(),
  imdbId: z.string().regex(/^tt\d{7,10}$/i).optional(),
  title: z.string().min(1).max(200).optional(),
  year: z.number().int().min(1880).max(2100).optional(),
  mediaType: z.enum(['movie', 'series']),
}).refine(data => !!(data.imdbId || data.title), {
  message: "Either imdbId or title must be provided"
});

export const OmdbBatchRequestSchema = z.object({
  titles: z.array(OmdbBatchCandidateSchema).max(25),
});

export const VerificationCandidateSchema = z.object({
  candidateId: z.string(),
  tmdbId: z.number(),
  mediaType: z.string(),
  title: z.string(),
  originalTitle: z.string(),
  overview: z.string(),
  genres: z.array(z.string()),
  keywords: z.array(z.string()),
  releaseYear: z.number().nullable(),
  directorOrCreators: z.array(z.string()),
  principalCast: z.array(z.string()),
  omdbVerified: z.boolean().optional().default(false),
  omdbGenres: z.array(z.string()).optional().default([]),
  fullPlot: z.string().nullable().optional(),
  runtimeMinutes: z.number().nullable().optional(),
  imdbRating: z.number().nullable().optional(),
  imdbVotes: z.number().nullable().optional(),
  rottenTomatoesRating: z.number().nullable().optional(),
  metascore: z.number().nullable().optional(),
  director: z.string().nullable().optional(),
  actors: z.array(z.string()).optional().default([]),
});

export const VerificationRequestSchema = z.object({
  requestId: z.string(),
  originalQuery: z.string(),
  mediaType: z.string(),
  requiredConceptGroups: z.array(RequiredConceptGroupSchema),
  excludedConcepts: z.array(z.string()),
  hardConstraints: z.any(),
  candidates: z.array(VerificationCandidateSchema).max(25),
});

export const VerifiedConceptEvidenceSchema = z.object({
  groupId: z.string(),
  status: z.enum(['SATISFIED', 'NOT_SATISFIED', 'INSUFFICIENT']),
  evidence: z.string(),
});

export const VerificationResultSchema = z.object({
  candidateId: z.string(),
  decision: z.enum(['DEFINITE_MATCH', 'PROBABLE_MATCH', 'INSUFFICIENT_EVIDENCE', 'REJECT']),
  confidence: z.number(),
  centralityScore: z.number(),
  requiredGroupAssessments: z.array(VerifiedConceptEvidenceSchema),
  matchedConcepts: z.array(z.string()),
  evidenceSummary: z.string(),
  rejectionReason: z.string().nullable(),
});

export const VerificationResponseSchema = z.object({
  requestId: z.string(),
  results: z.array(VerificationResultSchema),
});

// Gemini JSON Schemas

export const GeminiInterpretationSchema = {
  type: "OBJECT",
  properties: {
    requestId: { type: "STRING" },
    normalizedRequest: { type: "STRING" },
    summary: { type: "STRING" },
    requiredConceptGroups: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          id: { type: "STRING" },
          label: { type: "STRING" },
          description: { type: "STRING" },
          alternatives: { type: "ARRAY", items: { type: "STRING" } },
          specificSubtypes: { type: "ARRAY", items: { type: "STRING" } },
          centralityRequired: { type: "BOOLEAN" }
        },
        required: ["id", "label", "description", "alternatives", "specificSubtypes", "centralityRequired"]
      }
    },
    optionalConcepts: { type: "ARRAY", items: { type: "STRING" } },
    excludedConcepts: { type: "ARRAY", items: { type: "STRING" } },
    genreHypotheses: { type: "ARRAY", items: { type: "STRING" } },
    keywordSearchPhrases: { type: "ARRAY", items: { type: "STRING" } },
    broadSearchPhrases: { type: "ARRAY", items: { type: "STRING" } },
    hardConstraints: {
      type: "OBJECT",
      properties: {
        mediaType: { type: "STRING" },
        includedGenres: { type: "ARRAY", items: { type: "STRING" } },
        excludedGenres: { type: "ARRAY", items: { type: "STRING" } },
        minimumYear: { type: "INTEGER", nullable: true },
        maximumYear: { type: "INTEGER", nullable: true },
        maximumRuntimeMinutes: { type: "INTEGER", nullable: true },
        minimumRating: { type: "NUMBER", nullable: true },
        originalLanguage: { type: "STRING", nullable: true }
      },
      required: ["mediaType", "includedGenres", "excludedGenres", "minimumYear", "maximumYear", "maximumRuntimeMinutes", "minimumRating", "originalLanguage"]
    },
    anchorTitle: { type: "STRING", nullable: true },
    anchorModifiers: { type: "ARRAY", items: { type: "STRING" } },
    contradictions: { type: "ARRAY", items: { type: "STRING" } }
  },
  required: ["requestId", "normalizedRequest", "summary", "requiredConceptGroups", "optionalConcepts", "excludedConcepts", "genreHypotheses", "keywordSearchPhrases", "broadSearchPhrases", "hardConstraints", "anchorTitle", "anchorModifiers", "contradictions"]
};

export const GeminiExpansionSchema = {
  type: "OBJECT",
  properties: {
    requestId: { type: "STRING" },
    additionalKeywordPhrases: { type: "ARRAY", items: { type: "STRING" } },
    additionalPairwiseSearches: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          leftConcept: { type: "STRING" },
          rightConcept: { type: "STRING" }
        },
        required: ["leftConcept", "rightConcept"]
      }
    },
    additionalBroadPhrases: { type: "ARRAY", items: { type: "STRING" } }
  },
  required: ["requestId", "additionalKeywordPhrases", "additionalPairwiseSearches", "additionalBroadPhrases"]
};

export const GeminiVerificationSchema = {
  type: "OBJECT",
  properties: {
    requestId: { type: "STRING" },
    results: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          candidateId: { type: "STRING" },
          decision: { type: "STRING" },
          confidence: { type: "NUMBER" },
          centralityScore: { type: "NUMBER" },
          requiredGroupAssessments: {
            type: "ARRAY",
            items: {
              type: "OBJECT",
              properties: {
                groupId: { type: "STRING" },
                status: { type: "STRING" },
                evidence: { type: "STRING" }
              },
              required: ["groupId", "status", "evidence"]
            }
          },
          matchedConcepts: { type: "ARRAY", items: { type: "STRING" } },
          evidenceSummary: { type: "STRING" },
          rejectionReason: { type: "STRING", nullable: true }
        },
        required: ["candidateId", "decision", "confidence", "centralityScore", "requiredGroupAssessments", "matchedConcepts", "evidenceSummary", "rejectionReason"]
      }
    }
  },
  required: ["requestId", "results"]
};

// V2 Schemas for Rebuilt Recommendation Engine

export const InterpretV2RequestSchema = z.object({
  mediaType: z.enum(['movie', 'tv']),
  request: z.string().min(1).max(1000),
});

export const GeminiInterpretV2Schema = {
  type: "OBJECT",
  properties: {
    mediaType: { type: "STRING" },
    includedGenres: { type: "ARRAY", items: { type: "STRING" } },
    excludedGenres: { type: "ARRAY", items: { type: "STRING" } },
    minimumYear: { type: "INTEGER", nullable: true },
    maximumYear: { type: "INTEGER", nullable: true },
    minimumRuntimeMinutes: { type: "INTEGER", nullable: true },
    maximumRuntimeMinutes: { type: "INTEGER", nullable: true },
    minimumImdbRating: { type: "NUMBER", nullable: true },
    minimumImdbVotes: { type: "INTEGER", nullable: true },
    minimumRottenTomatoesRating: { type: "INTEGER", nullable: true },
    minimumMetascore: { type: "INTEGER", nullable: true },
    contentRatings: { type: "ARRAY", items: { type: "STRING" } },
    languages: { type: "ARRAY", items: { type: "STRING" } },
    countries: { type: "ARRAY", items: { type: "STRING" } },
    actors: { type: "ARRAY", items: { type: "STRING" } },
    directors: { type: "ARRAY", items: { type: "STRING" } },
    writers: { type: "ARRAY", items: { type: "STRING" } },
    minimumSeasons: { type: "INTEGER", nullable: true },
    maximumSeasons: { type: "INTEGER", nullable: true },
    plotRequirements: { type: "ARRAY", items: { type: "STRING" } },
    discoveryConcepts: { type: "ARRAY", items: { type: "STRING" } }
  },
  required: [
    "mediaType", "includedGenres", "excludedGenres", "minimumYear", "maximumYear",
    "minimumRuntimeMinutes", "maximumRuntimeMinutes", "minimumImdbRating", "minimumImdbVotes",
    "minimumRottenTomatoesRating", "minimumMetascore", "contentRatings", "languages",
    "countries", "actors", "directors", "writers", "minimumSeasons", "maximumSeasons",
    "plotRequirements", "discoveryConcepts"
  ]
};

export const VerifyPlotsCandidateSchema = z.object({
  candidateId: z.string(),
  title: z.string(),
  genres: z.array(z.string()).default([]),
  plot: z.string().nullable().optional(),
});

export const VerifyPlotsRequestSchema = z.object({
  plotRequirements: z.array(z.string()),
  candidates: z.array(VerifyPlotsCandidateSchema).max(25),
});

export const GeminiVerifyPlotsSchema = {
  type: "OBJECT",
  properties: {
    results: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          candidateId: { type: "STRING" },
          decision: { type: "STRING" },
          confidence: { type: "NUMBER" },
          evidence: { type: "STRING" }
        },
        required: ["candidateId", "decision", "confidence", "evidence"]
      }
    }
  },
  required: ["results"]
};

export const ProfileAnchorRequestSchema = z.object({
  mediaType: z.string(),
  title: z.string(),
  genres: z.array(z.string()).default([]),
  plot: z.string().nullable().optional(),
  director: z.string().nullable().optional(),
  writers: z.array(z.string()).default([]),
  actors: z.array(z.string()).default([]),
});

export const GeminiProfileAnchorSchema = {
  type: "OBJECT",
  properties: {
    coreThemes: { type: "ARRAY", items: { type: "STRING" } },
    keyGenres: { type: "ARRAY", items: { type: "STRING" } },
    keyCreators: { type: "ARRAY", items: { type: "STRING" } },
    discoveryConcepts: { type: "ARRAY", items: { type: "STRING" } },
    summary: { type: "STRING" }
  },
  required: ["coreThemes", "keyGenres", "keyCreators", "discoveryConcepts", "summary"]
};

