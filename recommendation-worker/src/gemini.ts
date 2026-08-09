import { INTERPRETATION_PROMPT, EXPANSION_PROMPT, VERIFICATION_PROMPT } from './prompts';

export interface Env {
  GEMINI_API_KEY: string;
  TMDB_API_KEY?: string;
  OMDB_API_KEY?: string;
  OMDB_CACHE?: KVNamespace;
}

export async function callGemini(
  env: Env,
  promptTemplate: string,
  requestJson: any,
  schema: any
): Promise<any> {
  const url = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=' + env.GEMINI_API_KEY;

  const payload = {
    contents: [
      {
        role: 'user',
        parts: [
          { text: JSON.stringify(requestJson) }
        ]
      }
    ],
    systemInstruction: {
      parts: [
        { text: promptTemplate }
      ]
    },
    generationConfig: {
      temperature: 0.1,
      responseMimeType: "application/json",
      // Gemini JSON schema support
      responseSchema: schema
    }
  };

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 12000); // 12s timeout

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'User-Agent': 'Aliflix-Recommendation-Worker/1.0'
      },
      body: JSON.stringify(payload),
      signal: controller.signal
    });

    clearTimeout(timeoutId);

    if (!response.ok) {
      const status = response.status;
      if (status === 429) {
        throw new Error('GEMINI_RATE_LIMIT');
      }
      if (status >= 500) {
        throw new Error('GEMINI_SERVER_ERROR');
      }
      throw new Error('GEMINI_ERROR');
    }

    const data: any = await response.json();
    const textOutput = data?.candidates?.[0]?.content?.parts?.[0]?.text;
    
    if (!textOutput) {
      throw new Error('GEMINI_INVALID_RESPONSE');
    }

    return JSON.parse(textOutput);
  } catch (error: any) {
    clearTimeout(timeoutId);
    if (error.name === 'AbortError') {
      throw new Error('GEMINI_TIMEOUT');
    }
    throw error;
  }
}

export async function callGeminiEmbeddingsBatch(
  env: Env,
  texts: string[]
): Promise<number[][]> {
  // Using text-embedding-004 as it is the current stable model
  const url = 'https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:batchEmbedContents?key=' + env.GEMINI_API_KEY;
  
  const payload = {
    requests: texts.map(t => ({
      model: 'models/text-embedding-004',
      content: {
        parts: [{ text: t }]
      }
    }))
  };

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    
    clearTimeout(timeoutId);
    if (!response.ok) {
      throw new Error(`GEMINI_EMBEDDING_ERROR: ${response.status}`);
    }
    
    const data: any = await response.json();
    return data.embeddings.map((e: any) => e.values);
  } catch (error: any) {
    clearTimeout(timeoutId);
    if (error.name === 'AbortError') throw new Error('GEMINI_EMBEDDING_TIMEOUT');
    throw error;
  }
}
