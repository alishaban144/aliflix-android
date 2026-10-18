import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,describe,expect,it,vi} from 'vitest';
import {AskResearch,AskRequestSchema,askRoute,clefProbability,safeResearchUrl,planFilters} from '../src/askResearch';
import {liveSetup,liveInstructions} from '../src/talkProviders';
import {TalkContextSchema} from '../src/talkEvidence';
afterEach(()=>vi.restoreAllMocks());
const request=(extra:Record<string,unknown>={})=>({requestId:crypto.randomUUID(),query:'Memory and identity science fiction',mediaType:'movie',pageSize:24,...extra});
describe('unified authenticated research',()=>{
 it('offers research only to mobile clients that implement the dispatcher, preserving voice',()=>{
  const old=liveSetup(TalkContextSchema.parse({companionVersion:2,recentProgress:[]}));
  expect(old.tools[0].functionDeclarations.some(t=>t.name==='web_research')).toBe(false);
  const current=liveSetup(TalkContextSchema.parse({companionVersion:3,recentProgress:[]}));
  expect(current.tools[0].functionDeclarations.filter(t=>['web_research','recommendations'].includes(t.name)).map(t=>t.behavior)).toEqual(['NON_BLOCKING','NON_BLOCKING']);
  expect(current.model).toBe('models/gemini-3.8-live');expect(current.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName).toBe('Aoede');
  expect(liveInstructions(TalkContextSchema.parse({companionVersion:3,recentProgress:[]}))).toContain('real authenticated Tavily');
 });
 it('normalizes omitted optional model constraints without removing real constraints',()=>{expect(planFilters({originalLanguage:'',minimumYear:0,maximumRuntimeMinutes:null}).originalLanguage).toBeUndefined();expect(planFilters({minimumYear:2023,originalLanguage:'de'})).toMatchObject({minimumYear:2023,originalLanguage:'de'});expect(()=>planFilters({minimumYear:2500})).toThrow();});
 it('requires a signed identity before daily or research access',async()=>{
  const r=await askRoute(new Request('https://example.com/v4/ask/daily?type=movie'),{...env,CURSOR_SIGNING_SECRET:'test'});expect(r?.status).toBe(401);
 });
 it.each(['http://example.com','https://user:password@example.com','https://127.0.0.1/x','https://themoviedb.org/x','https://imdb.com/title/tt1','file:///etc/passwd'])('rejects unsafe/unauthorized evidence %s',u=>expect(safeResearchUrl(u)).toBeUndefined());
 it('validates bounded multilingual queries, anchors and explicit feedback',()=>{
  expect(AskRequestSchema.parse(request({query:'أفلام عن الذاكرة والهوية',library:[{id:1,type:'movie',kind:'dislike'}]})).library[0].kind).toBe('dislike');
  expect(AskRequestSchema.safeParse(request({anchors:Array(5).fill({tmdbId:1,title:'A',mediaType:'movie'})})).success).toBe(false);
  expect(()=>clefProbability('90%')).toThrow();expect(clefProbability(.8)).toBe(.8);expect(clefProbability({type:'noul',noul:.9301})).toBe(.9301);
 });
 it('researches once, returns real citations and cached findings without further credits',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({results:[{title:'Official release',url:'https://www.sony.com/film',content:'A film opens in December 2026.'}],usage:{credits:1}}));
  await runInDurableObject(env.ASK_RESEARCH.getByName('test-web'),async(instance)=>{
   (instance as any).env.TAVILY_API_KEY='test-only';const service=instance as unknown as AskResearch;
   const a=await service.web({query:'new German releases',recent:true}),b=await service.web({query:'new German releases',recent:true});
   expect(a.evidence[0].url).toBe('https://www.sony.com/film');expect(a.credits).toBe(1);expect(b.credits).toBe(0);expect(b.cached).toBe(true);expect(fetch).toHaveBeenCalledTimes(1);
   const options=fetch.mock.calls[0][1];expect(JSON.parse(String(options?.body))).toMatchObject({search_depth:'basic',auto_parameters:false,include_raw_content:false,include_usage:true});
  });
 });
 it('fails honestly on search outage and quota exhaustion without replacing models',async()=>{
  vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response('',{status:429}));
  await runInDurableObject(env.ASK_RESEARCH.getByName('test-quota'),async(instance,state)=>{
   (instance as any).env.TAVILY_API_KEY='test-only';const service=instance as unknown as AskResearch;
   await expect(service.web({query:'current films',recent:true})).rejects.toMatchObject({code:'RESEARCH_QUOTA'});
   const day=new Date().toISOString().slice(0,10);state.storage.sql.exec('INSERT OR REPLACE INTO ask_cache VALUES(?,?,?)','quota:search:'+day,'24',Date.now()+86400000);
   await expect(service.web({query:'other current films',recent:true})).rejects.toMatchObject({code:'RESEARCH_QUOTA'});
  });
 });
 it('cancellation aborts work and does not populate its cache',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async(_url,options)=>{await new Promise((_,reject)=>{options?.signal?.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));});return Response.json({});});
  await runInDurableObject(env.ASK_RESEARCH.getByName('test-cancel'),async(instance,state)=>{
   (instance as any).env.TAVILY_API_KEY='test-only';const service=instance as unknown as AskResearch;
   const work=service.research('owner','job',{query:'new films',recent:true});await new Promise(r=>setTimeout(r,25));await service.cancel('owner','job');await expect(work).rejects.toThrow();
   expect(state.storage.sql.exec("SELECT key FROM ask_cache WHERE key LIKE 'web:%'").toArray()).toHaveLength(0);
  });
 });
 it('retains both last-good editorial collections on refresh failure',async()=>{
  await runInDurableObject(env.ASK_RESEARCH.getByName('test-last-good'),async(instance,state)=>{
   const service=instance as unknown as AskResearch;const original={results:[{tmdbId:7}],publishedAt:123};
   for(const type of ['movie','tv'])state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','daily:'+type,JSON.stringify(original),Date.now()+86400000);
   await expect(service.refreshDaily()).rejects.toThrow();expect(await service.daily('movie')).toEqual(original);expect(await service.daily('tv')).toEqual(original);
  });
 });
 it('one cancelled listener does not abort another viewer sharing the same research',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{await new Promise(r=>setTimeout(r,80));return Response.json({results:[{title:'Official films',url:'https://www.bfi.org.uk/new',content:'New independent films announced in 2026.'}]});});
  await runInDurableObject(env.ASK_RESEARCH.getByName('shared-cancellation'),async(instance)=>{
   (instance as any).env.TAVILY_API_KEY='test-only';const service=instance as unknown as AskResearch;
   const a=service.research('a','1',{query:'new independent films',recent:true}),b=service.research('b','2',{query:'new independent films',recent:true});
   const failure=expect(a).rejects.toThrow();await new Promise(r=>setTimeout(r,25));await service.cancel('a','1');await failure;expect((await b).evidence).toHaveLength(1);expect(fetch).toHaveBeenCalledTimes(1);
  });
 });
 it('returns 24 distinct genuine identities only after evidence, hard constraints and independent Clef acceptance',async()=>{
  const titles=Array.from({length:30},(_,i)=>({title:'Film '+i,year:2025,evidence:[0],reason:'Memory and identity are central to its narrative.'}));
  vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({results:[{title:'Research collection',url:'https://www.bfi.org.uk/lists/memory',content:titles.map(t=>t.title+': memory and identity story.').join(' ')}]}));
  await runInDurableObject(env.ASK_RESEARCH.getByName('pipeline-complete'),async(instance)=>{
   const e=(instance as any).env;e.TAVILY_API_KEY='test-only';
   const ai=vi.fn(async(model:string,input:any)=>model.includes('clef')?{answers:Object.fromEntries(Object.keys(input.questions).map((key,i)=>[key,{type:'noul',noul:i===0?.1:.9}]))}:{response:JSON.stringify(input.messages[0].content.startsWith('Interpret')?{intent:'Memory and identity',search:'memory identity film list',hardFilters:{}}:{candidates:titles})});e.AI={run:ai};
   const identity=async(_type:string,title:string)=>({tmdbId:Number(title.split(' ')[1])+1,mediaType:'movie',title,releaseDate:'2025-01-01',genres:['Science Fiction'],originCountries:['US'],originalLanguage:'en',runtimeMinutes:110,matchLevel:'Relevant',finalScore:0,matchReasons:[],retrievalSources:[]});
   e.ASK_RESEARCH={getByName:()=>({identity,resolveBatch:async(type:string,items:Array<{title:string}>)=>Promise.all(items.map(p=>identity(type,p.title))),evidenceBatch:async()=>[]})};
   const result=await(instance as unknown as AskResearch).recommend('user',request({filters:{minimumYear:2024,originalLanguage:'en'}}));
   expect(result.results).toHaveLength(24);expect(new Set(result.results.map(v=>v.tmdbId)).size).toBe(24);expect(result.results.some(v=>v.tmdbId===1)).toBe(false);expect(result.results.every(v=>v.retrievalSources[0].startsWith('https://www.bfi.org.uk'))).toBe(true);
   expect(ai.mock.calls.map(c=>c[0])).toEqual(['@cf/openai/gpt-oss-120b','@cf/openai/gpt-oss-120b','@cf/cloudflare/clef-flash']);
   expect(result.metrics).toMatchObject({gptCalls:2,clefCalls:1,verified:30,tavilyCredits:1,neurons:null});
  });
 });
});
