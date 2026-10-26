import { env, runInDurableObject } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AskResearch,
  PicksSchema,
  AskRequestSchema,
  askRoute,
  clefProbability,
  clefVerdict,
  safeResearchUrl,
  planFilters,
  researchFilters,
  researchTaste,
  researchQuotaRetryAt,
  researchBudgetState,
  researchCacheIdentity,
} from "../src/askResearch";
import { liveSetup, liveInstructions } from "../src/talkProviders";
import { TalkContextSchema } from "../src/talkEvidence";
afterEach(() => vi.restoreAllMocks());
const request = (extra: Record<string, unknown> = {}) => ({
  requestId: crypto.randomUUID(),
  query: "Memory and identity science fiction",
  mediaType: "movie",
  pageSize: 24,
  ...extra,
});
describe("unified authenticated research", () => {
  it("offers research only to mobile clients that implement the dispatcher, preserving voice", () => {
    const old = liveSetup(
      TalkContextSchema.parse({ companionVersion: 2, recentProgress: [] }),
    );
    expect(
      old.tools[0].functionDeclarations.some((t) => t.name === "web_research"),
    ).toBe(false);
    const current = liveSetup(
      TalkContextSchema.parse({ companionVersion: 3, recentProgress: [] }),
    );
    expect(
      current.tools[0].functionDeclarations
        .filter((t) => ["web_research", "recommendations"].includes(t.name))
        .map((t) => t.behavior),
    ).toEqual(["NON_BLOCKING", "NON_BLOCKING"]);
    expect(current.model).toBe("models/gemini-3.8-live");
    expect(
      current.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig
        .voiceName,
    ).toBe("Aoede");
    expect(
      liveInstructions(
        TalkContextSchema.parse({ companionVersion: 3, recentProgress: [] }),
      ),
    ).toContain("real authenticated Tavily");
  });
  it("normalizes omitted optional model constraints without removing real constraints", () => {
    expect(
      planFilters({
        originalLanguage: "",
        minimumYear: 0,
        maximumRuntimeMinutes: null,
      }).originalLanguage,
    ).toBeUndefined();
    expect(
      planFilters({ minimumYear: 2023, originalLanguage: "de" }),
    ).toMatchObject({ minimumYear: 2023, originalLanguage: "de" });
    expect(() => planFilters({ minimumYear: 2500 })).toThrow();
  });
  it("requires a signed identity before daily or research access", async () => {
    const r = await askRoute(
      new Request("https://example.com/v4/ask/daily?type=movie"),
      { ...env, CURSOR_SIGNING_SECRET: "test" },
    );
    expect(r?.status).toBe(401);
  });
  it.each([
    "http://example.com",
    "https://user:password@example.com",
    "https://127.0.0.1/x",
    "https://themoviedb.org/x",
    "https://imdb.com/title/tt1",
    "file:///etc/passwd",
  ])("rejects unsafe/unauthorized evidence %s", (u) =>
    expect(safeResearchUrl(u)).toBeUndefined(),
  );
  it("validates bounded multilingual queries, anchors and explicit feedback", () => {
    expect(
      AskRequestSchema.parse(
        request({
          query: "أفلام عن الذاكرة والهوية",
          library: [{ id: 1, type: "movie", kind: "dislike" }],
        }),
      ).library[0].kind,
    ).toBe("dislike");
    expect(
      AskRequestSchema.safeParse(
        request({
          anchors: Array(5).fill({ tmdbId: 1, title: "A", mediaType: "movie" }),
        }),
      ).success,
    ).toBe(false);
    expect(() => clefProbability("90%")).toThrow();
    expect(clefProbability(0.8)).toBe(0.8);
    expect(
      clefVerdict({
        type: "choice",
        choice: "weak",
        probabilities: { strong: 0.4, relevant: 0.2 },
      }),
    ).toBe(0);
    expect(
      clefVerdict({
        type: "choice",
        choice: "relevant",
        probabilities: { strong: 0.2, relevant: 0.65 },
      }),
    ).toBeCloseTo(0.59);
    expect(() => clefVerdict({ type: "choice", choice: "strong" })).toThrow();
    expect(clefProbability({ type: "noul", noul: 0.9301 })).toBe(0.9301);
  });
  it("researches once, returns real citations and cached findings without further credits", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      Response.json({
        results: [
          {
            title: "Official release",
            url: "https://www.sony.com/film",
            content: "A film opens in December 2026.",
          },
        ],
        usage: { credits: 1 },
      }),
    );
    await runInDurableObject(
      env.ASK_RESEARCH.getByName("test-web"),
      async (instance) => {
        (instance as any).env.TAVILY_API_KEY = "test-only";
        const service = instance as unknown as AskResearch;
        const a = await service.web({
            query: "new German releases",
            recent: true,
          }),
          b = await service.web({ query: "new German releases", recent: true });
        expect(a.evidence[0].url).toBe("https://www.sony.com/film");
        expect(a.credits).toBe(1);
        expect(b.credits).toBe(0);
        expect(b.cached).toBe(true);
        expect(fetch).toHaveBeenCalledTimes(1);
        const options = fetch.mock.calls[0][1];
        expect(JSON.parse(String(options?.body))).toMatchObject({
          search_depth: "basic",
          auto_parameters: false,
          include_raw_content: false,
          include_usage: true,
        });
      },
    );
  });
  it("fails honestly on search outage and quota exhaustion without replacing models", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("", { status: 429 }),
    );
    await runInDurableObject(
      env.ASK_RESEARCH.getByName("test-quota"),
      async (instance, state) => {
        (instance as any).env.TAVILY_API_KEY = "test-only";
        const service = instance as unknown as AskResearch;
        await expect(
          service.web({ query: "current films", recent: true }),
        ).rejects.toMatchObject({ code: "RESEARCH_QUOTA" });
        const day = new Date().toISOString().slice(0, 10);
        state.storage.sql.exec(
          "INSERT OR REPLACE INTO ask_cache VALUES(?,?,?)",
          "quota:search:" + day,
          "40",
          Date.now() + 86400000,
        );
        await expect(
          service.web({ query: "other current films", recent: true }),
        ).rejects.toMatchObject({ code: "RESEARCH_QUOTA" });
      },
    );
  });
  it("cancellation aborts work and does not populate its cache", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, options) => {
      await new Promise((_, reject) => {
        options?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Cancelled", "AbortError")),
        );
      });
      return Response.json({});
    });
    await runInDurableObject(
      env.ASK_RESEARCH.getByName("test-cancel"),
      async (instance, state) => {
        (instance as any).env.TAVILY_API_KEY = "test-only";
        const service = instance as unknown as AskResearch;
        const work = service.research("owner", "job", {
          query: "new films",
          recent: true,
        });
        await new Promise((r) => setTimeout(r, 25));
        await service.cancel("owner", "job");
        await expect(work).rejects.toThrow();
        expect(
          state.storage.sql
            .exec("SELECT key FROM ask_cache WHERE key LIKE 'web:%'")
            .toArray(),
        ).toHaveLength(0);
      },
    );
  });
  it("allows one delayed reset-propagation recovery without resetting any quota",()=>{
    const early=Date.parse('2026-10-20T00:02:00Z');expect(researchQuotaRetryAt(early)).toBe(early+900000);
    expect(researchQuotaRetryAt(Date.parse('2026-10-20T12:00:00Z'))).toBe(Date.parse('2026-10-21T00:01:00Z'));
  });
  it("a successful same-model probe removes only a stale circuit flag, preserving real quotas",async()=>{
    await runInDurableObject(env.ASK_RESEARCH.getByName("stale-quota-reopens"),async(instance,state)=>{
      const service=instance as unknown as AskResearch,e=(instance as any).env,original=e.AI;
      const day=new Date().toISOString().slice(0,10),blocked='quota:provider-blocked:'+day;
      const put=(key:string,value:unknown)=>state.storage.sql.exec('INSERT OR REPLACE INTO ask_cache VALUES(?,?,?)',key,JSON.stringify(value),Date.now()+86400000);
      put(blocked,true);put('quota:ai:'+day,7);put('quota:search:'+day,4);put('quota:neurons:'+day,100);
      const run=vi.fn(async()=>({response:'OK',usage:{neurons:3}}));e.AI={run};
      try{
        expect((await service.dailyForClient('movie')).state).toBe('warming');
        await (service as any).recoverQuotaCircuit();await (service as any).recoverQuotaCircuit();
        expect(run).toHaveBeenCalledTimes(1);expect(run.mock.calls[0]).toEqual(['@cf/openai/gpt-oss-120b',expect.objectContaining({max_tokens:16})]);
        expect(state.storage.sql.exec('SELECT value FROM ask_cache WHERE key=?',blocked).toArray()).toHaveLength(0);
        for(const[key,value]of [['quota:ai:'+day,7],['quota:search:'+day,4],['quota:neurons:'+day,103]])expect(JSON.parse(state.storage.sql.exec<{value:string}>('SELECT value FROM ask_cache WHERE key=?',key).one().value)).toBe(value);
      }finally{e.AI=original;}
    });
  });
  it("an unavailable probe keeps the circuit closed and cannot repeat within an hour",async()=>{
    await runInDurableObject(env.ASK_RESEARCH.getByName("stale-quota-still-unavailable"),async(instance,state)=>{
      const service=instance as unknown as AskResearch,e=(instance as any).env,original=e.AI,key='quota:provider-blocked:'+new Date().toISOString().slice(0,10);
      state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)',key,'true',Date.now()+86400000);
      const run=vi.fn(async()=>{throw Error('4006 daily free allocation');});e.AI={run};
      try{await (service as any).recoverQuotaCircuit();await (service as any).recoverQuotaCircuit();expect(run).toHaveBeenCalledTimes(1);expect(state.storage.sql.exec('SELECT value FROM ask_cache WHERE key=?',key).toArray()).toHaveLength(1);}
      finally{e.AI=original;}
    });
  });
  it("schedules one bounded empty bootstrap and serves its published result instantly",async()=>{
    await runInDurableObject(env.ASK_RESEARCH.getByName("self-healing-editorial"),async(instance,state)=>{
      const service=instance as unknown as AskResearch;
      const first=await service.dailyForClient("movie");expect(first.state).toBe("warming");expect(first.results).toHaveLength(0);
      expect((await service.dailyForClient("tv")).retryAt).toBe(first.retryAt);
      const refresh=vi.spyOn(service,"refreshDaily").mockImplementation(async()=>{
        for(const type of ["movie","tv"])state.storage.sql.exec("INSERT OR REPLACE INTO ask_cache VALUES(?,?,?)","daily:"+type,JSON.stringify({results:Array.from({length:20},(_,i)=>({tmdbId:i+1})),publishedAt:Date.now()}),Date.now()+86400000);
        return {published:true,counts:[20,20]};
      });
      state.storage.sql.exec("UPDATE ask_cache SET value=? WHERE key='daily:refreshAt'",JSON.stringify(Date.now()-1));
      await service.alarm();expect(refresh).toHaveBeenCalledTimes(1);expect((await service.dailyForClient("movie")).state).toBe("ready");
    });
  });
  it("defers empty editorial research to the next UTC quota day without invoking a model",async()=>{
    await runInDurableObject(env.ASK_RESEARCH.getByName("quota-bootstrap-day"),async(instance,state)=>{
      const service=instance as unknown as AskResearch;
      state.storage.sql.exec("INSERT INTO ask_cache VALUES(?,?,?)","quota:provider-blocked:"+new Date().toISOString().slice(0,10),'true',Date.now()+86400000);
      state.storage.sql.exec("INSERT INTO ask_cache VALUES(?,?,?)","quota:provider-blocked:"+new Date().toISOString().slice(0,10)+':checkedAt',JSON.stringify(Date.now()),Date.now()+86400000);
      const refresh=vi.spyOn(service,"refreshDaily");const result=await service.dailyForClient("tv");
      expect(result.state).toBe("limited");expect(result.retryAt).toBeGreaterThan(Date.now());expect(new Date(result.retryAt).getUTCDate()).not.toBe(new Date().getUTCDate());
      expect(refresh).not.toHaveBeenCalled();expect(await state.storage.getAlarm()).toBe(result.retryAt);
    });
  });
  it("retries an empty bootstrap after five minutes, retaining the longer populated backoff", async () => {
    await runInDurableObject(env.ASK_RESEARCH.getByName("bootstrap-backoff"),async(instance,state)=>{
      const service=instance as unknown as AskResearch;
      state.storage.sql.exec("INSERT INTO ask_cache VALUES(?,?,?)","daily:attempt",JSON.stringify(Date.now()-301000),Date.now()+86400000);
      expect(await service.needsRefresh()).toBe(true);
      expect(await service.needsRefresh()).toBe(false);
      for(const type of ["movie","tv"])state.storage.sql.exec("INSERT INTO ask_cache VALUES(?,?,?)","daily:"+type,JSON.stringify({results:[{tmdbId:1}],publishedAt:Date.now()-86400001}),Date.now()+86400000);
      state.storage.sql.exec("UPDATE ask_cache SET value=? WHERE key=?",JSON.stringify(Date.now()-301000),"daily:attempt");
      expect(await service.needsRefresh()).toBe(false);
    });
  });
  it("publishes both complete editorial collections atomically and excludes the previous collection",async()=>{
    await runInDurableObject(env.ASK_RESEARCH.getByName("atomic-editorial-success"),async(instance,state)=>{
      const service=instance as unknown as AskResearch;
      for(const type of ["movie","tv"])state.storage.sql.exec("INSERT INTO ask_cache VALUES(?,?,?)","daily:"+type,JSON.stringify({results:[{tmdbId:7}],publishedAt:123}),Date.now()+86400000);
      const recommend=vi.spyOn(service,"recommend").mockImplementation(async(_owner,raw)=>({results:Array.from({length:20},(_,i)=>({tmdbId:i+100,mediaType:(raw as any).mediaType})),metrics:{},cached:false}) as any);
      expect(await service.refreshDaily()).toMatchObject({published:true,counts:[20,20]});
      const [movie,tv]=await Promise.all([service.daily("movie"),service.daily("tv")]);
      expect(movie.publishedAt).toBe(tv.publishedAt);expect(movie.publishedAt).toBeGreaterThan(123);
      for(const call of recommend.mock.calls)expect((call[1] as any).filters.excludedTmdbIds).toEqual([7]);
    });
  });
  it("retains both last-good editorial collections on refresh failure", async () => {
    await runInDurableObject(
      env.ASK_RESEARCH.getByName("test-last-good"),
      async (instance, state) => {
        const service = instance as unknown as AskResearch;
        const original = { results: [{ tmdbId: 7 }], publishedAt: 123 };
        for (const type of ["movie", "tv"])
          state.storage.sql.exec(
            "INSERT INTO ask_cache VALUES(?,?,?)",
            "daily:" + type,
            JSON.stringify(original),
            Date.now() + 86400000,
          );
        await expect(service.refreshDaily()).rejects.toThrow();
        expect(await service.daily("movie")).toEqual(original);
        expect(await service.daily("tv")).toEqual(original);
      },
    );
  });
  it("one cancelled listener does not abort another viewer sharing the same research", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      await new Promise((r) => setTimeout(r, 80));
      return Response.json({
        results: [
          {
            title: "Official films",
            url: "https://www.bfi.org.uk/new",
            content: "New independent films announced in 2026.",
          },
        ],
      });
    });
    await runInDurableObject(
      env.ASK_RESEARCH.getByName("shared-cancellation"),
      async (instance) => {
        (instance as any).env.TAVILY_API_KEY = "test-only";
        const service = instance as unknown as AskResearch;
        const a = service.research("a", "1", {
            query: "new independent films",
            recent: true,
          }),
          b = service.research("b", "2", {
            query: "new independent films",
            recent: true,
          });
        const failure = expect(a).rejects.toThrow();
        await new Promise((r) => setTimeout(r, 25));
        await service.cancel("a", "1");
        await failure;
        expect((await b).evidence).toHaveLength(1);
        expect(fetch.mock.calls.filter(([url,init])=>String(url).includes("api.tavily.com/search")&&JSON.parse(String(init?.body)).query==="new independent films")).toHaveLength(1);
      },
    );
  });
  it.each(["movie","tv"] as const)("streams %s matches from one Tavily search and Gemini selection without Clef",async(mediaType)=>{
    const titles=Array.from({length:28},(_,i)=>({title:'Film '+i,year:2025}));
    const calls:string[]=[];
    let sourceText='Distinctive movies about identity.';
    vi.spyOn(globalThis,'fetch').mockImplementation(async(u,init)=>{
      const url=String(u),headers=new Headers(init?.headers);
      // Daily-bootstrap alarms from other fixtures can run concurrently. Only
      // this coordinator's credentials belong to this strict request-count check.
      if(headers.get('Authorization')!=='Bearer fast-pipeline-fixture'&&headers.get('x-goog-api-key')!=='fast-pipeline-fixture')return new Response('Unrelated fixture',{status:503});
      calls.push(url);
      if(url.includes('tavily.com'))return Response.json({results:[{title:'Research',url:'https://www.bfi.org.uk/list',content:sourceText}],usage:{credits:1}});
      const pair=new WebSocketPair();pair[1].accept();
      pair[1].addEventListener('message',event=>{
        const message=JSON.parse(String(event.data));
        if(message.setup)pair[1].send(JSON.stringify({setupComplete:{}}));
        if(message.clientContent)pair[1].send(JSON.stringify({toolCall:{functionCalls:[{name:'recommend_titles',args:{candidates:titles}}]}}));
      });
      return new Response(null,{status:101,webSocket:pair[0]});
    });
    await runInDurableObject(env.ASK_RESEARCH.getByName('fast-pipeline-complete-'+mediaType),async instance=>{
      const service=instance as unknown as AskResearch,e=(instance as any).env;
      const old={AI:e.AI,ASK_RESEARCH:e.ASK_RESEARCH,TAVILY_API_KEY:e.TAVILY_API_KEY,GEMINI_API_KEY:e.GEMINI_API_KEY};
      const run=vi.fn();e.AI={run};e.TAVILY_API_KEY='fast-pipeline-fixture';e.GEMINI_API_KEY='fast-pipeline-fixture';
      const identity=vi.fn(async(_type:string,title:string)=>({tmdbId:Number(title.split(' ')[1])+1,mediaType,title,releaseDate:'2025-01-01',genres:[mediaType==='tv'?'Sci-Fi & Fantasy':'Science Fiction'],originCountries:['US'],originalLanguage:'en',runtimeMinutes:110,matchLevel:'Relevant',finalScore:0,matchReasons:[],retrievalSources:[]}));
      e.ASK_RESEARCH={getByName:()=>({identity,anchorContext:async()=>({tmdbId:999,mediaType,title:'Anchor film',year:'2020',creators:['A director']}),resolveBatch:async(type:string,items:Array<{title:string}>)=>Promise.all(items.map(p=>identity(type,p.title)))})};
      try {
        const snapshots:any[]=[];const raw=request({mediaType,filters:{minimumYear:2024,originalLanguage:'en',excludedTmdbIds:[1]}});
        const result=await (service as any).recommendInternal('owner',raw,(value:any)=>snapshots.push(value));
        expect(result.results).toHaveLength(24);expect(result.results.some((v:any)=>v.tmdbId===1)).toBe(false);
        expect(snapshots[0].results).toHaveLength(1);
        expect(result.metrics).toMatchObject({geminiCalls:1,gptCalls:0,clefCalls:0,tavilyCredits:1,pipeline:'tavily-gemini-tmdb'});
        expect(run).not.toHaveBeenCalled();expect(calls).toHaveLength(2);
        const cached=await service.recommend('owner',raw);expect(cached.cached).toBe(true);expect(calls).toHaveLength(2);
        const similar=await service.recommend('owner',request({mediaType,mode:'similar',query:'',anchors:[{tmdbId:999,title:'Anchor film',mediaType}],library:[{id:1,type:mediaType,kind:'watched'},{id:2,type:mediaType,kind:'favorite'},{id:3,type:mediaType,kind:'dislike'}]}));
        expect(similar.results).toHaveLength(24);expect(run).not.toHaveBeenCalled();
        expect(similar.results.some(v=>v.tmdbId===1)).toBe(true);
        expect(similar.results.some(v=>v.tmdbId===2)).toBe(true);
        expect(similar.results.some(v=>v.tmdbId===3)).toBe(false);
        const beforeHints=calls.length;
        const shortlist=await service.recommend('talk-owner',request({mediaType,query:'Thoughtful science fiction recommendations',candidateHints:titles.slice(1,8),pageSize:8}));
        expect(shortlist.results).toHaveLength(7);
        expect(shortlist.metrics).toMatchObject({geminiCalls:0,clefCalls:0,pipeline:'tavily-gemini-live-tmdb'});
        expect(shortlist.researchBrief?.excerpts[0]).toMatchObject({url:'https://www.bfi.org.uk/list',text:'Distinctive movies about identity.'});
        expect(calls.length-beforeHints).toBe(1);
        sourceText='Film 40 (2025). A thoughtful science fiction movie about identity.';
        const beforeFallback=calls.length;
        const fallback=await service.recommend('talk-owner',request({mediaType,query:'Other thoughtful science fiction choices',candidateHints:titles.slice(0,2),filters:{excludedTitles:titles.slice(0,2).map(v=>v.title)},pageSize:8}));
        expect(fallback.results).toEqual([]); // Uncurated search titles must not bypass the rejected shortlist.
        expect(fallback.metrics.geminiCalls).toBe(0);
        expect(calls.length-beforeFallback).toBe(1);
      } finally {Object.assign(e,old);}
    });
  });
});

it("enforces declared genres and maps TV catalogue genres without weakening hard filters", () => {
  const empty = planFilters({});
  expect(researchFilters("movie",empty,empty,"Recommend three thoughtful science fiction movies, with one short reason each.").includedGenres).toEqual(["Science Fiction"]);
  expect(researchFilters("movie",empty,empty,"Recommend movies without science fiction").excludedGenres).toContain("Science Fiction");
  expect(
    researchFilters(
      "movie",
      empty,
      empty,
      "Science fiction films about identity",
    ).includedGenres,
  ).toEqual(["Science Fiction"]);
  expect(
    researchFilters(
      "tv",
      empty,
      planFilters({ includedGenres: ["Science Fiction", "Action"] }),
      "",
    ).includedGenres,
  ).toEqual(["Sci-Fi & Fantasy", "Action & Adventure"]);
  expect(
    researchFilters("movie", empty, empty, "أفلام خيال علمي عن الهوية")
      .includedGenres,
  ).toEqual(["Science Fiction"]);
  expect(() =>
    researchFilters(
      "movie",
      planFilters({ excludedGenres: ["Science Fiction"] }),
      empty,
      "Science fiction films",
    ),
  ).toThrow();
});

it("rejects malformed individual identities without discarding valid researched candidates",()=>{
 const parsed=PicksSchema.parse({candidates:[{title:"Arrival",year:2016},{title:"Unknown date",year:0},{title:"Wrong type",year:"2024"},{title:"",year:2023},{title:"Dark",year:2017}]});
 expect(parsed.candidates.map(p=>p.title)).toEqual(["Arrival","Dark"]);
 expect(()=>PicksSchema.parse({candidates:Array(65).fill({title:"A",year:2023})})).toThrow();
});

it("reports a Gemini quota failure without retrying or invoking Cloudflare models",async()=>{
 const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async u=>String(u).includes('tavily.com')?Response.json({results:[{title:'Research',url:'https://example.org/list',content:'Film recommendations'}]}):Response.json({error:{message:'Quota'}},{status:429}));
 await runInDurableObject(env.ASK_RESEARCH.getByName('fast-quota'),async instance=>{
  const service=instance as unknown as AskResearch,e=(instance as any).env,old={GEMINI_API_KEY:e.GEMINI_API_KEY,TAVILY_API_KEY:e.TAVILY_API_KEY};e.GEMINI_API_KEY='test';e.TAVILY_API_KEY='test';
  try{await expect(service.recommend('owner',request())).rejects.toThrow('ASK_SERVICE:AI_QUOTA:429');expect(fetch).toHaveBeenCalledTimes(2);}finally{Object.assign(e,old);}
 });
});

it("keeps Favorites first even after many rejections, with saved intent and playback distinct",()=>{
 const items=[...Array.from({length:16},(_,i)=>({id:100+i,type:"movie" as const,kind:"dislike" as const})),...Array.from({length:12},(_,i)=>({id:1+i,type:"movie" as const,kind:"favorite" as const})),{id:200,type:"movie" as const,kind:"saved" as const},{id:300,type:"tv" as const,kind:"watched" as const},{id:100,type:"movie" as const,kind:"favorite" as const}];
 const snapshot=researchTaste(items);expect(snapshot).toHaveLength(8);expect(snapshot[0].kind).toBe("favorite");
 expect(snapshot.filter(v=>v.kind==="favorite").length).toBeGreaterThanOrEqual(4);
 expect(snapshot.some(v=>v.kind==="saved")).toBe(true);expect(snapshot.some(v=>v.kind==="watched")).toBe(true);
 expect(snapshot.filter(v=>v.id===100)).toEqual([{id:100,type:"movie",kind:"dislike"}]);
});

it('quota recovery respects both daily and monthly ledgers without resetting counters',()=>{
 const now=Date.UTC(2026,9,19,23,40);const counts={aiDay:96,aiMonth:300,searchDay:12,searchMonth:200,neurons:1200};
 const day=researchBudgetState(counts,now);expect(day.limited).toBe(true);expect(day.remaining.aiDay).toBe(0);expect(day.retryAt).toBe(Date.UTC(2026,9,20,0,1));
 expect(researchBudgetState({...counts,aiDay:0},now).limited).toBe(false);
 const month=researchBudgetState({...counts,aiMonth:2400},now);expect(month.retryAt).toBe(Date.UTC(2026,10,1,0,1));
 expect(counts.aiDay).toBe(96);
});

it('an active editorial refresh stays warming without scheduling duplicate work',async()=>{
 await runInDurableObject(env.ASK_RESEARCH.getByName('inflight-editorial-read'),async(instance,state)=>{
  const service=instance as unknown as AskResearch;
  let release!:()=>void;const wait=new Promise<void>(r=>release=r);
  vi.spyOn(service,'recommend').mockImplementation(async(_owner,raw)=>{await wait;return {results:Array.from({length:20},(_,i)=>({tmdbId:i+1,mediaType:(raw as any).mediaType})),metrics:{},cached:false} as any});
  const needs=vi.spyOn(service,'needsRefresh');
  const refreshing=service.refreshDaily();
  const warming=await service.dailyForClient('movie');
  expect(warming.state).toBe('warming');expect(warming.retryAt).toBeGreaterThan(Date.now());expect(needs).not.toHaveBeenCalled();
  state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','quota:ai:'+new Date().toISOString().slice(0,10),'96',Date.now()+86400000);
  expect((await service.dailyForClient('movie')).state).toBe('warming');
  release();await refreshing;expect((await service.dailyForClient('movie')).state).toBe('ready');
 });
});

it('AI quota cannot blank verified catalogue suggestions or consume another inference',async()=>{
 await runInDurableObject(env.ASK_RESEARCH.getByName('catalogue-quota-display'),async(instance,state)=>{
  const service=instance as unknown as AskResearch;
  state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','quota:ai:'+new Date().toISOString().slice(0,10),'96',Date.now()+86400000);
  const pair=['movie','tv'].map(mediaType=>({results:Array.from({length:24},(_,i)=>({tmdbId:i+1,mediaType,title:'Verified '+i,finalScore:0,retrievalSources:[`https://www.themoviedb.org/${mediaType}/${i+1}`]})),publishedAt:Date.now(),source:'catalogue'}));
  vi.spyOn(service,'catalogueDaily').mockResolvedValue(pair as any);
  const inference=vi.spyOn(service,'recommend');
  expect(await service.dailyForClient('movie')).toMatchObject({state:'ready',source:'catalogue',results:pair[0].results});
  expect(await service.dailyForClient('tv')).toMatchObject({state:'ready',source:'catalogue',results:pair[1].results});
  expect(inference).not.toHaveBeenCalled();
 });
});
it('stale research uses a fresh atomic catalogue pair without pretending it is AI research',async()=>{
 await runInDurableObject(env.ASK_RESEARCH.getByName('catalogue-stale-research'),async(instance,state)=>{
  const service=instance as unknown as AskResearch;
  for(const type of ['movie','tv'])state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','daily:'+type,JSON.stringify({results:[{tmdbId:99}],publishedAt:Date.now()-4*86400000}),Date.now()+86400000);
  state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','daily:attempt',JSON.stringify(Date.now()),Date.now()+86400000);
  const pair=['movie','tv'].map(mediaType=>({results:Array.from({length:24},(_,i)=>({tmdbId:i+1,mediaType,title:'Current '+i,finalScore:0,matchReasons:[],retrievalSources:[`https://www.themoviedb.org/${mediaType}/${i+1}`]})),publishedAt:Date.now(),source:'catalogue'}));
  vi.spyOn(service,'catalogueDaily').mockResolvedValue(pair as any);
  const research=vi.spyOn(service,'recommend');
  expect(await service.dailyForClient('movie')).toMatchObject({state:'ready',...pair[0]});
  expect(await service.dailyForClient('tv')).toMatchObject({state:'ready',...pair[1]});
  expect(research).not.toHaveBeenCalled();
  expect((await service.daily('movie')).results).toEqual([{tmdbId:99}]);
 });
});

it('catalogue outage retains the previous valid pair without consuming AI or search budget',async()=>{
 await runInDurableObject(env.ASK_RESEARCH.getByName('catalogue-last-good'),async(instance,state)=>{
  const service=instance as unknown as AskResearch;const pair=['movie','tv'].map(mediaType=>({results:Array.from({length:24},(_,i)=>({tmdbId:i+1,mediaType,title:'Retained '+i})),publishedAt:Date.now()-2*86400000,source:'catalogue'}));
  pair.forEach((v,i)=>state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','daily:catalogue:'+(i?'tv':'movie'),JSON.stringify(v),Date.now()+86400000));
  vi.spyOn(globalThis,'fetch').mockRejectedValue(Error('offline'));
  expect(await service.catalogueDaily()).toEqual(pair);
 });
});


describe('progressive research transport',()=>{
 const value=(n:number)=>({results:Array.from({length:n},(_,i)=>({tmdbId:i+1})),metrics:{accepted:n}});
 const decode=(bytes:Uint8Array)=>JSON.parse(new TextDecoder().decode(bytes));
 it('transfers actual byte streams through Durable Object RPC',async()=>{
  const stub=env.ASK_RESEARCH.getByName('stream-rpc');
  const input=AskRequestSchema.parse(request());
  const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(researchCacheIdentity(input))));
  const key='stable-results-v14:'+Array.from(new Uint8Array(bytes)).map(b=>b.toString(16).padStart(2,'0')).join('');
  await runInDurableObject(stub,async(_instance,state)=>{
   state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)',key,JSON.stringify({...value(24),cachedAt:Date.now()}),Date.now()+86400000);
  });
  const response=new Response(await stub.recommendStream('owner',input));
  const events=(await response.text()).trim().split('\n').map(line=>JSON.parse(line));
  expect(events.at(-1)).toMatchObject({requestId:input.requestId,type:'results',inProgress:false,cached:true,results:value(24).results});
 });
 it('delivers an evaluated batch while the rest is pending and conflates slow readers',async()=>{
  await runInDurableObject(env.ASK_RESEARCH.getByName('stream-batches'),async instance=>{
   const service=instance as unknown as AskResearch;
   let advance!:(n:number)=>void,finish!:()=>void;
   const completion=new Promise<void>(r=>finish=r);
   vi.spyOn(service as any,'recommendInternal').mockImplementation(async(_owner:any,_raw:any,progress:any)=>{
    advance=n=>progress(value(n));await completion;return value(24);
   });
   const id=crypto.randomUUID(),reader=(await service.recommendStream('owner',request({requestId:id}))).getReader();
   expect(decode((await reader.read()).value!)).toMatchObject({requestId:id,type:'progress',revision:1});
   await vi.waitFor(()=>expect(advance).toBeTypeOf("function"));
   advance(5);
   expect(decode((await reader.read()).value!)).toMatchObject({inProgress:true,results:value(5).results});
   advance(8);advance(12);advance(18);finish();
   const rest:any[]=[];for(;;){const next=await reader.read();if(next.done)break;rest.push(decode(next.value));}
   expect(rest.at(-1)).toMatchObject({inProgress:false,results:value(24).results});
   expect(rest.length).toBeLessThanOrEqual(3);
   expect(rest.every((v,i)=>!i||v.revision>rest[i-1].revision)).toBe(true);
  });
 });
 it('consumer cancellation aborts its waiter, and quota errors never fabricate results',async()=>{
  await runInDurableObject(env.ASK_RESEARCH.getByName('stream-cancel'),async instance=>{
   const service=instance as unknown as AskResearch;let cancelled=false;
   const work=vi.spyOn(service as any,'recommendInternal').mockImplementation(async(_o:any,_r:any,_p:any,signal:any)=>{
    await new Promise<void>(r=>signal.addEventListener('abort',()=>{cancelled=true;r();}));return value(0);
   });
   const reader=(await service.recommendStream('owner',request())).getReader();await reader.read();await vi.waitFor(()=>expect(work).toHaveBeenCalled());await reader.cancel();expect(cancelled).toBe(true);
   work.mockRejectedValue(Error('ASK_SERVICE:AI_QUOTA:429'));
   const failed=(await service.recommendStream('owner',request())).getReader();await failed.read();
   expect(decode((await failed.read()).value!)).toMatchObject({type:'error',error:{code:'AI_QUOTA'}});
   expect((await failed.read()).done).toBe(true);
  });
 });
 it('retains real partial matches after a later failure',async()=>{
  await runInDurableObject(env.ASK_RESEARCH.getByName('stream-partial'),async(instance,state)=>{
   const service=instance as unknown as AskResearch;
   vi.spyOn(service as any,'build').mockImplementation(async()=>{
    const key=[...(service as any).progressListeners.keys()][0];
    (service as any).latestProgress.set(key,value(5));throw Error('ASK_SERVICE:AI_QUOTA:429');
   });
   const reader=(await service.recommendStream('owner',request())).getReader();await reader.read();
   const result=decode((await reader.read()).value!);expect(result).toMatchObject({inProgress:false,incomplete:true,results:value(5).results});
   expect((await reader.read()).done).toBe(true);
   const row=state.storage.sql.exec<{value:string;expires:number}>("SELECT value,expires FROM ask_cache WHERE key LIKE 'stable-results-v14:%'").one();
   expect(JSON.parse(row.value).cachedAt).toBeGreaterThan(Date.now()-1000);expect(row.expires).toBeGreaterThan(Date.now()+86000000);
  });
 });
 it('migrates legacy evaluated partial caches without models, but never revives old or empty data',async()=>{
  await runInDurableObject(env.ASK_RESEARCH.getByName('stream-legacy'),async(instance,state)=>{
   const service=instance as any,now=Date.now();
   const put=(key:string,data:unknown,expires:number)=>state.storage.sql.exec('INSERT OR REPLACE INTO ask_cache VALUES(?,?,?)',key,JSON.stringify(data),expires);
   put('legacy',value(12),now-60000);expect(service.retainedResults('legacy').results).toHaveLength(12);
   put('too-old',value(12),now-86400000);expect(service.retainedResults('too-old')).toBeUndefined();
   put('empty',value(0),now+60000);expect(service.retainedResults('empty')).toBeUndefined();
  });
 });
});


it('shares pinned-model research across Android aliases without merging taste or constraints',()=>{
 const input=AskRequestSchema.parse(request());
 const mobile=AskRequestSchema.parse({...input,aiModel:'cloudflare-gpt-oss-120b',geminiModel:'cloudflare-gpt-oss-120b'});
 expect(JSON.stringify(researchCacheIdentity(input))).toBe(JSON.stringify(researchCacheIdentity(mobile)));
 expect(JSON.stringify(researchCacheIdentity(input))).not.toBe(JSON.stringify(researchCacheIdentity({...mobile,library:[{id:1,type:'movie',kind:'favorite'}]})));
 expect(JSON.stringify(researchCacheIdentity(input))).not.toBe(JSON.stringify(researchCacheIdentity({...mobile,filters:{...mobile.filters,originalLanguage:'de'}})));
});
