import { z } from 'zod';

export const V3RecommendationRequestSchema = z.object({
  requestId: z.string(),
  query: z.string(),
  mediaType: z.enum(['movie', 'tv']).optional().default('movie'),
  filters: z.record(z.any()).optional().default({}),
  pageSize: z.number().optional().default(20),
  cursor: z.any().optional(),
});

// Gemini Schema for Intent Parsing
export const GeminiIntentSchema = {
  type: "OBJECT",
  properties: {
    intentType: { type: "STRING", description: "'discovery' or 'similar_to'" },
    mediaType: { type: "STRING", description: "'movie' or 'tv'" },
    anchorTitle: { type: "STRING", nullable: true },
    hardFilters: {
      type: "OBJECT",
      properties: {
        minimumYear: { type: "INTEGER", nullable: true },
        maximumYear: { type: "INTEGER", nullable: true },
        originalLanguage: { type: "STRING", nullable: true },
        originCountry: { type: "STRING", nullable: true },
        minimumRuntimeMinutes: { type: "INTEGER", nullable: true },
        maximumRuntimeMinutes: { type: "INTEGER", nullable: true },
        includedGenreNames: { type: "ARRAY", items: { type: "STRING" } },
        excludedGenreNames: { type: "ARRAY", items: { type: "STRING" } }
      },
      required: [
        "minimumYear", "maximumYear", "originalLanguage", "originCountry",
        "minimumRuntimeMinutes", "maximumRuntimeMinutes",
        "includedGenreNames", "excludedGenreNames"
      ]
    },
    requiredConceptGroups: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          label: { type: "STRING" },
          synonyms: { type: "ARRAY", items: { type: "STRING" } },
          weight: { type: "NUMBER" }
        },
        required: ["label", "synonyms", "weight"]
      }
    },
    softConcepts: { type: "ARRAY", items: { type: "STRING" } },
    excludedConcepts: { type: "ARRAY", items: { type: "STRING" } },
    genreHints: { type: "ARRAY", items: { type: "STRING" } },
    toneAndMood: { type: "ARRAY", items: { type: "STRING" } },
    broadSearchPhrases: { type: "ARRAY", items: { type: "STRING" } }
  },
  required: [
    "intentType", "mediaType", "anchorTitle", "hardFilters",
    "requiredConceptGroups", "softConcepts", "excludedConcepts",
    "genreHints", "toneAndMood", "broadSearchPhrases"
  ]
};

export const GeminiExpansionSchema = {
  type: "OBJECT",
  properties: {
    newSearchPhrases: { type: "ARRAY", items: { type: "STRING" } },
    newConceptGroups: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          label: { type: "STRING" },
          synonyms: { type: "ARRAY", items: { type: "STRING" } },
          weight: { type: "NUMBER" }
        },
        required: ["label", "synonyms", "weight"]
      }
    }
  },
  required: ["newSearchPhrases", "newConceptGroups"]
};
