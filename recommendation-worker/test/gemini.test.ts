import { afterEach, describe, expect, it, vi } from 'vitest';
import { embedForSearch, recommendDescribeTitles, recommendSimilarTitles } from '../src/gemini';
import { EDITORIAL_RECOMMENDATIONS_PROMPT } from '../src/prompts';

describe('Gemini editorial contract', () => {
  afterEach(() => vi.unstubAllGlobals());
  it.each(['describe', 'similar'])('uses only 3.8 Flash and requests twenty rated titles for %s', async mode => {
    const recommendations = Array.from({length: 20}, (_, i) => ({title: `Title ${i}`, releaseYear: 2025, rating: 10 - i / 10}));
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({
      candidates: [{content: {parts: [{text: JSON.stringify({recommendations})}]}}]}));
    vi.stubGlobal('fetch', fetchMock);
    const env = {GEMINI_API_KEY: 'fixture'} as any;
    const result = mode === 'describe' ? await recommendDescribeTitles(env, 'Movies about aliens and AI. Recent', 'movie', {})
      : await recommendSimilarTitles(env, [], 'movie', '', {});
    expect(result).toHaveLength(20); expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('/gemini-3.8-flash:generateContent');
    const body = JSON.parse(String(init?.body));
    expect(body.systemInstruction.parts[0].text).toBe(EDITORIAL_RECOMMENDATIONS_PROMPT);
    expect(JSON.parse(body.contents[0].parts[0].text).targetCount).toBe(20);
    expect(body.generationConfig.maxOutputTokens).toBe(8192);
  });
  it('does not silently retry with an older model after a quota failure', async () => {
    const fetchMock = vi.fn(async () => Response.json({error: {message: 'Quota'}}, {status: 429}));
    vi.stubGlobal('fetch', fetchMock);
    await expect(recommendDescribeTitles({GEMINI_API_KEY: 'fixture'} as any, 'aliens', 'movie', {})).rejects.toMatchObject({code: 'GEMINI_UNAVAILABLE'});
    expect(fetchMock).toHaveBeenCalledOnce();
  });
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
