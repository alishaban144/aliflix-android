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
        expect(fetch).toHaveBeenCalledTimes(1);
      },
    );
  });
  it("returns 24 distinct genuine identities only after evidence, hard constraints and independent Clef acceptance", async () => {
    const titles = Array.from({ length: 30 }, (_, i) => ({
      title: "Film " + i,
      year: 2025,
      evidence: [0],
      reason: "Memory and identity are central to its narrative.",
    }));
    vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      Response.json({
        results: [
          {
            title: "Research collection",
            url: "https://www.bfi.org.uk/lists/memory",
            content: titles
              .map((t) => t.title + ": memory and identity story.")
              .join(" "),
          },
        ],
      }),
    );
    await runInDurableObject(
      env.ASK_RESEARCH.getByName("pipeline-complete"),
      async (instance) => {
        const e = (instance as any).env;
        const original = {
          AI: e.AI,
          ASK_RESEARCH: e.ASK_RESEARCH,
          TAVILY_API_KEY: e.TAVILY_API_KEY,
        };
        try {
          e.TAVILY_API_KEY = "test-only";
          const ai = vi.fn(async (model: string, input: any) =>
            model.includes("clef")
              ? {
                  answers: Object.fromEntries(
                    Object.keys(input.questions).map((key, i) => [
                      key,
                      {
                        type: "choice",
                        choice: i === 0 ? "weak" : "strong",
                        probabilities: {
                          strong: i === 0 ? 0.1 : 0.9,
                          relevant: 0.05,
                          weak: 0.05,
                          unsupported: 0,
                        },
                      },
                    ]),
                  ),
                }
              : {
                  response: JSON.stringify(
                    input.messages[0].content.startsWith("Interpret")
                      ? {
                          intent: "Memory and identity",
                          search: "memory identity film list",
                          hardFilters: {},
                        }
                      : { candidates: titles },
                  ),
                },
          );
          e.AI = { run: ai };
          const identity = async (_type: string, title: string) => ({
            tmdbId: Number(title.split(" ")[1]) + 1,
            mediaType: "movie",
            title,
            releaseDate: "2025-01-01",
            genres: ["Science Fiction"],
            originCountries: ["US"],
            originalLanguage: "en",
            runtimeMinutes: 110,
            matchLevel: "Relevant",
            finalScore: 0,
            matchReasons: [],
            retrievalSources: [],
          });
          e.ASK_RESEARCH = {
            getByName: () => ({
              identity,
              resolveBatch: async (
                type: string,
                items: Array<{ title: string }>,
              ) => Promise.all(items.map((p) => identity(type, p.title))),
              evidenceBatch: async () => [],
            }),
          };
          const result = await (instance as unknown as AskResearch).recommend(
            "user",
            request({ filters: { minimumYear: 2024, originalLanguage: "en" } }),
          );
          expect(result.results).toHaveLength(24);
          expect(new Set(result.results.map((v) => v.tmdbId)).size).toBe(24);
          expect(result.results.some((v) => v.tmdbId === 1)).toBe(false);
          expect(
            result.results.every((v) =>
              v.retrievalSources[0].startsWith("https://www.bfi.org.uk"),
            ),
          ).toBe(true);
          expect(ai.mock.calls.map((c) => c[0])).toEqual([
            "@cf/openai/gpt-oss-120b",
            "@cf/openai/gpt-oss-120b",
            "@cf/cloudflare/clef-flash",
            "@cf/cloudflare/clef-flash",
            "@cf/cloudflare/clef-flash",
          ]);
          expect(result.metrics).toMatchObject({
            gptCalls: 2,
            clefCalls: 3,
            verified: 30,
            tavilyCredits: 1,
            neurons: null,
          });
        } finally {
          Object.assign(e, original);
        }
      },
    );
  });
});

it("enforces declared genres and maps TV catalogue genres without weakening hard filters", () => {
  const empty = planFilters({});
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

it("stops queuing new inference after the provider's daily allocation is exhausted",async()=>{
 await runInDurableObject(env.ASK_RESEARCH.getByName("provider-allocation-stop"),async(instance)=>{
  const service=instance as unknown as AskResearch;const e=(instance as any).env;const original=e.AI;
  const run=vi.fn(async()=>{throw Error("4006: daily free allocation exhausted");});
  e.AI={run};try{
   await expect(service.recommend("a",request({query:"first identity request"}))).rejects.toThrow("ASK_SERVICE:AI_QUOTA:429");
   await expect(service.recommend("b",request({query:"different identity request"}))).rejects.toThrow("ASK_SERVICE:AI_QUOTA:429");
   expect(run).toHaveBeenCalledTimes(1);
  }finally{e.AI=original;}
 });
});

it("keeps Favorites first even after many rejections, with saved intent and playback distinct",()=>{
 const items=[...Array.from({length:16},(_,i)=>({id:100+i,type:"movie" as const,kind:"dislike" as const})),...Array.from({length:12},(_,i)=>({id:1+i,type:"movie" as const,kind:"favorite" as const})),{id:200,type:"movie" as const,kind:"saved" as const},{id:300,type:"tv" as const,kind:"watched" as const},{id:100,type:"movie" as const,kind:"favorite" as const}];
 const snapshot=researchTaste(items);expect(snapshot).toHaveLength(8);expect(snapshot[0].kind).toBe("favorite");
 expect(snapshot.filter(v=>v.kind==="favorite").length).toBeGreaterThanOrEqual(4);
 expect(snapshot.some(v=>v.kind==="saved")).toBe(true);expect(snapshot.some(v=>v.kind==="watched")).toBe(true);
 expect(snapshot.filter(v=>v.id===100)).toEqual([{id:100,type:"movie",kind:"dislike"}]);
});
