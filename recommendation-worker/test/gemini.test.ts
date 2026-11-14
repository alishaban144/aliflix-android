import { afterEach, describe, expect, it, vi } from 'vitest';
import { embedForSearch, recommendDescribeTitles, recommendSimilarTitles } from '../src/gemini';
import { hydrogenRecommendationPicks } from '../src/fastRecommendations';
import { EDITORIAL_RECOMMENDATIONS_PROMPT } from '../src/prompts';

describe('Gemini editorial contract', () => {
  afterEach(() => vi.unstubAllGlobals());
  it.each(['describe', 'similar'])('uses only 4 Hydrogen and requests twenty rated titles for %s', async mode => {
    const recommendations = Array.from({length: 20}, (_, i) => ({title: `Title ${i}`, releaseYear: 2025, rating: 10 - i / 10}));
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({
      candidates: [{content: {parts: [{text: JSON.stringify({recommendations})}]}}]}));
    vi.stubGlobal('fetch', fetchMock);
    const env = {GEMINI_API_KEY: 'fixture'} as any;
    const result = mode === 'describe' ? await recommendDescribeTitles(env, 'Movies about aliens and AI. Recent', 'movie', {})
      : await recommendSimilarTitles(env, [], 'movie', '', {});
    expect(result).toHaveLength(20); expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('/gemini-4-hydrogen:generateContent');
    const body = JSON.parse(String(init?.body));
    expect(body.systemInstruction.parts[0].text).toBe(EDITORIAL_RECOMMENDATIONS_PROMPT);
    expect(JSON.parse(body.contents[0].parts[0].text).targetCount).toBe(20);
    expect(body.generationConfig.responseJsonSchema.properties.recommendations.maxItems).toBe(20);
    expect(body.generationConfig.maxOutputTokens).toBe(8192);
  });
  it('does not silently retry with an older model after a quota failure', async () => {
    const fetchMock = vi.fn(async () => Response.json({error: {message: 'Quota'}}, {status: 429}));
    vi.stubGlobal('fetch', fetchMock);
    await expect(recommendDescribeTitles({GEMINI_API_KEY: 'fixture'} as any, 'aliens', 'movie', {})).rejects.toMatchObject({code: 'GEMINI_UNAVAILABLE'});
    expect(fetchMock).toHaveBeenCalledOnce();
  });
  it.each([
    ['malformed JSON', '{'],
    ['invalid schema', '{"recommendations":[{"title":"Missing year and rating"}]}'],
  ])('ignores an early 200 with %s and uses the validated recommendation', async (_failure, invalid) => {
    const primary = 'primary-' + crypto.randomUUID();
    const secondary = 'secondary-' + crypto.randomUUID();
    let markRejected!: () => void;
    const rejected = new Promise<void>(resolve => {markRejected = resolve;});
    const warn = vi.spyOn(console, 'warn').mockImplementation(message => {
      if (String(message).includes('gemini_json_parse_failed') || String(message).includes('gemini_describe_schema_failed')) {
        markRejected();
      }
    });
    const recommendations = [{title: 'Arrival', releaseYear: 2016, rating: 9}];
    const envelope = (text: string) => Response.json({candidates: [{content: {parts: [{text}]}}]});
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (new Headers(init?.headers).get('x-goog-api-key') === primary) return envelope(invalid);
      await rejected;
      return envelope(JSON.stringify({recommendations}));
    });
    vi.stubGlobal('fetch', fetchMock);
    try {
      const result = await recommendDescribeTitles({
        GEMINI_API_KEY: primary, GEMINI_API_KEY_SECONDARY: secondary,
      } as any, 'Films about first contact', 'movie', {}, [], 1);
      expect(result).toMatchObject(recommendations);
      expect(fetchMock).toHaveBeenCalledTimes(2);
      const body = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
      expect(body.generationConfig.responseJsonSchema.properties.recommendations.maxItems).toBe(1);
    } finally {
      warn.mockRestore();
    }
  });
});

it('uses the new key pool for bounded Hydrogen curation and ignores thought parts', async () => {
  const a = 'fixture-a-' + crypto.randomUUID();
  const b = 'fixture-b-' + crypto.randomUUID();
  const keys: string[] = [];
  let started!: () => void;
  const both = new Promise<void>(resolve => {started = resolve;});
  const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    expect(String(input)).toContain('/gemini-4-hydrogen:generateContent');
    const key = new Headers(init?.headers).get('x-goog-api-key')!;
    keys.push(key);
    if (keys.length === 2) started();
    await both;
    const request = JSON.parse(String(init?.body));
    expect(request.generationConfig.thinkingConfig.thinkingLevel).toBe('low');
    expect(request.generationConfig.responseJsonSchema.properties.candidates.maxItems).toBe(5);
    const pick = {title: 'The Assistant', year: 2019, why: 'A junior employee navigating workplace pressure'};
    return Response.json({candidates: [{content: {parts: [
      {thought: true, text: 'internal'}, {text: JSON.stringify({candidates: [pick]})},
    ]}}]});
  });
  try {
    const picks = await hydrogenRecommendationPicks({
      GEMINI_API_KEY: 'fixture-legacy', GEMINI_API_KEY_HYDROGEN_A: a, GEMINI_API_KEY_HYDROGEN_B: b,
    } as any, {count: 1}, AbortSignal.timeout(1500));
    expect(picks).toMatchObject({model:'gemini-4-hydrogen',picks:[{title: 'The Assistant', year: 2019}]});
    expect(['hydrogen-a','hydrogen-b']).toContain(picks.keySlot);
    expect(new Set(keys)).toEqual(new Set([a, b]));
  } finally {
    fetcher.mockRestore();
  }
});

describe('Gemini Embedding 2 retrieval', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('uses the current model, independent batches and the model-2 prefix contract', async () => {
    const fetchMock=vi.fn(async (_url:string,init?:RequestInit)=>{
      const request=JSON.parse(String(init?.body));
      return Response.json({embeddings:request.requests.map((_:unknown,index:number)=>({values:Array.from({length:768},(_,i)=>i===index?1:0)}))});
    });
    vi.stubGlobal('fetch',fetchMock);
    const result=await embedForSearch({GEMINI_API_KEY:'fixture'} as any,'Why did she fall?', ['Someone loosened the railing.', 'The train departed.']);
    expect(result.queryVector).toHaveLength(768);
    expect(result.candidateVectors).toHaveLength(2);
    const bodies=fetchMock.mock.calls.map(([url,init])=>{expect(url).toContain('/gemini-embedding-2:batchEmbedContents');return JSON.parse(String(init?.body));});
    expect(bodies[0].requests[0].content.parts[0].text).toBe('task: search result | query: Why did she fall?');
    expect(bodies[1].requests).toHaveLength(2);
    expect(bodies[1].requests[0].content.parts[0].text).toBe('title: none | text: Someone loosened the railing.');
    for(const body of bodies)for(const request of body.requests){
      expect(request).not.toHaveProperty('taskType');
      expect(request.embedContentConfig.outputDimensionality).toBe(768);
    }
    expect(result.candidateVectors[0]).not.toEqual(result.candidateVectors[1]);
  });
  it('rejects non-finite or wrong-dimensional vectors instead of corrupting relevance scores', async () => {
    vi.stubGlobal('fetch',vi.fn(async()=>Response.json({embeddings:[{values:[1,2,3]}]})));
    await expect(embedForSearch({GEMINI_API_KEY:'fixture'} as any,'question',[])).rejects.toMatchObject({code:'GEMINI_UNAVAILABLE'});
  });
});
