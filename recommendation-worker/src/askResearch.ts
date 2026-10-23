import { liveRecommendationPicks, tavilyCandidatePicks } from './fastRecommendations';
import { candidateEvidence } from "./askEvidence";
import { DurableObject } from "cloudflare:workers";
import { z } from "zod";
import {
  RecommendationRequestSchema,
  RecommendationFiltersSchema,
} from "./schemas";
import { TmdbClient, TmdbDetails } from "./tmdb";
import { mergeFilters, passesHardFilters, normalize } from "./ranking";
import { toCandidate, mergeDetails } from "./engine";
import { boundedJson } from "./talkProviders";
import { verifyIdentity } from "./watchParty";
import type {
  RecommendationEnv,
  RecommendationResult,
  MediaType,
} from "./types";
import { ServiceError } from "./types";

const DAY = 86_400_000;
const Taste = z
  .object({
    id: z.number().int().positive(),
    type: z.enum(["movie", "tv"]),
    kind: z.enum(["favorite", "saved", "watched", "dislike"]),
  })
  .strict();
export const AskRequestSchema = RecommendationRequestSchema.and(
  z.object({ library: z.array(Taste).max(80).default([]), candidateHints:z.array(z.object({title:z.string().trim().min(1).max(180),year:z.number().int().min(1870).max(2200)})).max(8).optional() }),
);
/** Explicit quotas keep negative feedback from crowding every Favorite out of
 * the bounded taste snapshot. Saved intent and playback remain separate roles. */
export function researchTaste(items:Array<z.infer<typeof Taste>>){
 const rejected=new Set(items.filter(v=>v.kind==='dislike').map(v=>v.type+':'+v.id));
 const priority=['favorite','saved','dislike','watched'] as const;
 const unique=new Map<string,z.infer<typeof Taste>>();
 for(const kind of priority)for(const item of items.filter(v=>v.kind===kind)){
  const key=item.type+':'+item.id;if(rejected.has(key)&&kind!=='dislike')continue;
  if(!unique.has(key))unique.set(key,item);
 }
 const all=[...unique.values()];const selected=priority.flatMap((kind,i)=>all.filter(v=>v.kind===kind).slice(0,[4,2,1,1][i]));
 const keys=new Set(selected.map(v=>v.type+':'+v.id));
 return [...selected,...all.filter(v=>!keys.has(v.type+':'+v.id))].slice(0,8);
}
export const WebResearchSchema = z
  .object({
    query: z.string().trim().min(3).max(600),
    recent: z.boolean().default(false),
  })
  .strict();
type AskRequest = z.infer<typeof AskRequestSchema>;
// Research models are pinned server-side; legacy client aliases must not
// fragment identical researched requests between Android and Talk.
export const researchCacheIdentity=(request:AskRequest)=>({...request,requestId:undefined,cursor:undefined,aiModel:undefined,geminiModel:undefined});

export type WebFinding = {
  title: string;
  url: string;
  text: string;
  published?: string;
  attribution: string;
};
type ResearchMedia = RecommendationResult & {
  imdbId?: string;
  productionCompanyIds?: number[];
  collectionId?: number;
};
type Research = {
  answer?: string;
  evidence: WebFinding[];
  cached: boolean;
  credits: number;
  retrievedAt: number;
};
type Usage = {
  elapsedMs: number;
  firstResultsMs?:number;
  tavilyCredits: number;
  researchCached: boolean;
  gptCalls: number;
  geminiCalls?: number;
  pipeline?: string;
  clefCalls: number;
  generated: number;
  reusedCandidates?: number;
  verified: number;
  accepted: number;
  neurons: number | null;
  meteredNeurons?: number;
  neuronEstimate?: number;
  aiUsage?: Array<{
    model: string;
    usage: {
      inputTokens: number;
      outputTokens: number;
      totalTokens: number;
      neurons?: number;
    };
  }>;
};
type ResearchResult={results:RecommendationResult[];metrics:Usage;cached?:boolean;inProgress?:boolean;incomplete?:boolean};
const clean = (v: unknown, max = 1200) =>
  typeof v === "string"
    ? v
        .replace(/<[^>]*>/g, " ")
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, max)
    : "";
export function safeResearchUrl(raw: string): string | undefined {
  try {
    const u = new URL(raw);
    if (
      u.protocol !== "https:" ||
      u.username ||
      u.password ||
      u.port ||
      !u.hostname.includes(".") ||
      /^(?:localhost|127\.|10\.|192\.168\.|169\.254\.|\[)/.test(u.hostname) ||
      /(?:^|\.)(?:themoviedb\.org|imdb\.com|fandom\.com|reddit\.com)$/.test(
        u.hostname,
      )
    )
      return;
    u.hash = "";
    return u.toString();
  } catch {
    return;
  }
}
const digest = async (v: unknown) =>
  Array.from(
    new Uint8Array(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(JSON.stringify(v)),
      ),
    ),
  )
    .map((v) => v.toString(16).padStart(2, "0"))
    .join("");

/** A provider still reporting yesterday's quota shortly after midnight gets
 * one delayed recovery probe, rather than being latched unavailable all day. */
export function researchQuotaRetryAt(now:number):number {
 const midnight=Date.parse(new Date(now).toISOString().slice(0,10));
 return now-midnight<900000?now+900000:midnight+86400000+60000;
}
export function researchBudgetState(counts:{aiDay:number;aiMonth:number;searchDay:number;searchMonth:number;neurons:number},now=Date.now()) {
 const day=Date.parse(new Date(now).toISOString().slice(0,10))+DAY+60000;
 const date=new Date(now);const month=Date.UTC(date.getUTCFullYear(),date.getUTCMonth()+1,1)+60000;
 const remaining={aiDay:Math.max(0,96-counts.aiDay),aiMonth:Math.max(0,2400-counts.aiMonth),searchDay:Math.max(0,40-counts.searchDay),searchMonth:Math.max(0,800-counts.searchMonth),neurons:Math.max(0,9000-counts.neurons)};
 const retryAt=Math.max(remaining.aiDay===0||remaining.searchDay===0||remaining.neurons===0?day:0,remaining.aiMonth===0||remaining.searchMonth===0?month:0);
 return {remaining,limited:retryAt>now,retryAt};
}
export function planFilters(raw: unknown) {
  const value =
    raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  return RecommendationFiltersSchema.parse(
    Object.fromEntries(
      Object.entries(value).filter(
        ([key, v]) =>
          v !== null &&
          v !== "" &&
          !(
            v === 0 &&
            [
              "minimumYear",
              "maximumYear",
              "minimumRuntimeMinutes",
              "maximumRuntimeMinutes",
            ].includes(key)
          ),
      ),
    ),
  );
}
/** Preserve explicit catalogue constraints independently of model phrasing. */
export function researchFilters(
  type: MediaType,
  request: z.infer<typeof RecommendationFiltersSchema>,
  planned: z.infer<typeof RecommendationFiltersSchema>,
  query: string,
) {
  const filters = mergeFilters(request, planned);
  const declared =
    /^(?:science fiction|sci[- ]?fi)\s+(?:films?|movies?|series|shows?)\b|^science[- ]fiction[- ]filme|^أفلام\s+خيال\s+علمي/i.test(
      query.trim(),
    )
      ? "Science Fiction"
      : undefined;
  const canonical = (genre: string) => {
    if (type !== "tv") return genre;
    const key = normalize(genre);
    return [
      "science fiction",
      "sci fi",
      "fantasy",
      "science fiction fantasy",
      "sci fi fantasy",
    ].includes(key)
      ? "Sci-Fi & Fantasy"
      : ["action", "adventure", "action adventure"].includes(key)
        ? "Action & Adventure"
        : ["war", "politics", "war politics"].includes(key)
          ? "War & Politics"
          : genre;
  };
  filters.includedGenres = [
    ...new Set(
      [...filters.includedGenres, ...(declared ? [declared] : [])].map(
        canonical,
      ),
    ),
  ];
  filters.excludedGenres = [...new Set(filters.excludedGenres.map(canonical))];
  if (
    filters.includedGenres.some((v) =>
      filters.excludedGenres.some((e) => normalize(v) === normalize(e)),
    )
  )
    throw new ServiceError(
      "CONTRADICTORY_FILTERS",
      "Conflicting genre filters",
      400,
      false,
    );
  return filters;
}
export function researchFailure(error: unknown) {
  const message = error instanceof Error ? error.message : "";
  const match = /ASK_SERVICE:([A-Z_]+):(\d{3})/.exec(message);
  return error instanceof ServiceError
    ? { code: error.code, status: error.status }
    : match
      ? { code: match[1], status: Number(match[2]) }
      : undefined;
}
const wireFailure = (error: unknown) => {
  const failure = researchFailure(error);
  return new Error(
    `ASK_SERVICE:${failure?.code || "RESEARCH_UNAVAILABLE"}:${failure?.status || 503}`,
  );
};
function remaining(signal: AbortSignal) {
  signal.throwIfAborted();
}
function boundedInference<T>(
  work: Promise<T>,
  signal: AbortSignal,
  ms: number,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new ServiceError("AI_TIMEOUT", "Research timed out", 503, true));
    }, ms);
    const aborted = () => {
      cleanup();
      reject(
        new ServiceError("RESEARCH_CANCELLED", "Research cancelled", 499, true),
      );
    };
    const cleanup = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", aborted);
    };
    if (signal.aborted) {
      aborted();
      return;
    }
    signal.addEventListener("abort", aborted, { once: true });
    work.then(
      (value) => {
        cleanup();
        resolve(value);
      },
      (error) => {
        cleanup();
        reject(error);
      },
    );
  });
}
async function lanes<T, R>(
  items: T[],
  count: number,
  fn: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(count, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i], i);
      }
    }),
  );
  return out;
}
export function clefProbability(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value))
    return Math.max(0, Math.min(1, value));
  if (value && typeof value === "object") {
    const v = value as Record<string, unknown>;
    for (const key of ["noul", "probability", "value", "yes", "true"])
      if (typeof v[key] === "number") return clefProbability(v[key]);
  }
  throw new ServiceError(
    "CLEF_UNAVAILABLE",
    "Relevance evaluation unavailable",
    503,
    true,
  );
}
export function clefVerdict(value: unknown): number {
  if (!value || typeof value !== "object")
    throw new ServiceError(
      "CLEF_UNAVAILABLE",
      "Relevance evaluation unavailable",
      503,
      true,
    );
  const v = value as {
    type?: string;
    choice?: string;
    probabilities?: Record<string, number>;
  };
  if (
    v.type !== "choice" ||
    !["strong", "relevant", "weak", "unsupported"].includes(v.choice || "")
  )
    throw new ServiceError(
      "CLEF_UNAVAILABLE",
      "Relevance evaluation unavailable",
      503,
      true,
    );
  if (v.choice !== "strong" && v.choice !== "relevant") return 0;
  const probabilities = v.probabilities;
  if (
    !probabilities ||
    !Number.isFinite(probabilities.strong) ||
    !Number.isFinite(probabilities.relevant)
  )
    throw new ServiceError(
      "CLEF_UNAVAILABLE",
      "Relevance evaluation unavailable",
      503,
      true,
    );
  // This is only an internal ordering signal, never a verified match percentage.
  return (
    clefProbability(probabilities.strong) +
    0.6 * clefProbability(probabilities.relevant)
  );
}
const PickSchema = z.object({
  title: z.string().min(1).max(180),
  year: z.number().int().min(1870).max(2200),
  evidence: z.array(z.number().int().min(0).max(15)).max(4).default([]),
  reason: z.string().max(140).default(""),
});
export const PicksSchema = z.object({ candidates: z.array(z.unknown()).max(64).transform(items=>items.flatMap(item=>{
  const valid=PickSchema.safeParse(item);return valid.success?[valid.data]:[];
})) });
/** One global coordinator owns free-tier reservations and atomic editorial publication.
 * Identity shards isolate TMDB fan-out; every persisted row is bounded and expires. */
export async function editorialSeedCandidates(tmdb:TmdbClient,type:MediaType,minimumYear:number,maximumYear:number) {
  const pages=await lanes([1,2,3],2,page=>tmdb.discover(type,{
    page,sort_by:"popularity.desc",...(type==="tv"?{"first_air_date.gte":`${minimumYear}-01-01`,"first_air_date.lte":`${maximumYear}-12-31`}:{"primary_release_date.gte":`${minimumYear}-01-01`,"primary_release_date.lte":`${maximumYear}-12-31`})
  }));
  const seen=new Set<number>();
  return pages.flatMap(p=>p.results).flatMap(item=>{
    const title=(type==="tv"?item.name:item.title)?.trim();
    const year=Number((type==="tv"?item.first_air_date:item.release_date)?.slice(0,4));
    if(!title||!item.id||seen.has(item.id)||!Number.isInteger(year)||year<minimumYear||year>maximumYear)return [];
    seen.add(item.id);return [{title,year,id:item.id,evidence:[] as number[],reason:""}];
  }).slice(0,60);
}

export function catalogueSuggestions(items:Array<import('./types').TmdbListItem & {adult?:boolean}>,type:MediaType,genres:Map<number,string>,today=new Date().toISOString().slice(0,10)):RecommendationResult[] {
 const seen=new Set<number>();const minimum=Number(today.slice(0,4))-3;
 return items.flatMap(item=>{
  const date=(type==='movie'?item.release_date:item.first_air_date)||'';
  if(item.adult||!item.poster_path||!/^\d{4}-\d{2}-\d{2}$/.test(date)||date>today||Number(date.slice(0,4))<minimum||(item.vote_count||0)<20||(item.vote_average||0)<6||seen.has(item.id))return [];
  const c=toCandidate(item,type,genres);if(!c)return [];seen.add(item.id);
  return [{tmdbId:c.tmdbId,mediaType:type,title:c.title,originalTitle:c.originalTitle,posterPath:c.posterPath,backdropPath:c.backdropPath,releaseDate:c.releaseDate,genres:c.genres,originalLanguage:c.originalLanguage,originCountries:c.originCountries,tmdbRating:c.tmdbRating,tmdbVoteCount:c.tmdbVoteCount,matchLevel:'Relevant' as const,finalScore:0,matchReasons:[],retrievalSources:[`https://www.themoviedb.org/${type}/${c.tmdbId}`]}];
 }).slice(0,24);
}

export class AskResearch extends DurableObject<RecommendationEnv> {
  private flights = new Map<string, Promise<unknown>>();
  private controllers = new Map<string, AbortController>();
  private webControllers = new Map<string, AbortController>();
  private webWaiters = new Map<string, number>();
  private recommendationControllers = new Map<string, AbortController>();
  private recommendationWaiters = new Map<string, number>();
  private progressListeners=new Map<string,Set<(value:ResearchResult)=>void>>();
  private latestProgress=new Map<string,ResearchResult>();
  private cleanupScheduled = false;
  constructor(ctx: DurableObjectState, env: RecommendationEnv) {
    super(ctx, env);
    ctx.storage.sql.exec(
      "CREATE TABLE IF NOT EXISTS ask_cache (key TEXT PRIMARY KEY, value TEXT NOT NULL, expires INTEGER NOT NULL)",
    );
  }
  private read<T>(key: string): T | undefined {
    const row = this.ctx.storage.sql
      .exec<{
        value: string;
      }>(
        "SELECT value FROM ask_cache WHERE key=? AND expires>?",
        key,
        Date.now(),
      )
      .toArray()[0];
    return row ? JSON.parse(row.value) : undefined;
  }
  private put(key: string, value: unknown, ttl: number) {
    this.ctx.storage.sql.exec(
      "INSERT OR REPLACE INTO ask_cache VALUES (?,?,?)",
      key,
      JSON.stringify(value),
      Date.now() + ttl,
    );
    this.ctx.storage.sql.exec(
      "DELETE FROM ask_cache WHERE expires<?",
      Date.now(),
    );
    this.ctx.storage.sql.exec(
      "DELETE FROM ask_cache WHERE key NOT LIKE 'quota:%' AND key NOT LIKE 'daily:%' AND key NOT IN (SELECT key FROM ask_cache ORDER BY expires DESC LIMIT 512)",
    );
    if (!this.cleanupScheduled) {
      this.cleanupScheduled = true;
      this.ctx.waitUntil(this.ctx.storage.setAlarm(Date.now() + DAY));
    }
  }
  async alarm() {
    this.ctx.storage.sql.exec(
      "DELETE FROM ask_cache WHERE expires<=?",
      Date.now(),
    );
    const refreshAt=this.read<number>('daily:refreshAt');
    if(refreshAt && refreshAt<=Date.now()) {
      this.ctx.storage.sql.exec("DELETE FROM ask_cache WHERE key='daily:refreshAt'");
      await this.recoverQuotaCircuit();
      const blocked=this.read<boolean>('quota:provider-blocked:'+new Date().toISOString().slice(0,10))||this.researchBudget().limited;
      const key='daily:bootstrap:'+new Date().toISOString().slice(0,10);
      const attempts=this.read<number>(key)||0;
      if(blocked)this.put('daily:refreshAt',this.quotaRetryAt(),2*DAY);
      else if(attempts<3) {
        this.put(key,attempts+1,2*DAY);
        try { await this.refreshDaily(); }
        catch(error) {
          const quota=['AI_QUOTA','RESEARCH_QUOTA'].includes(researchFailure(error)?.code||'');
          if(quota || attempts<2)this.put('daily:refreshAt',quota?this.quotaRetryAt():Date.now()+300000,2*DAY);
          console.warn(JSON.stringify({event:'ask_bootstrap_retained',code:researchFailure(error)?.code||'RESEARCH_UNAVAILABLE'}));
        }
      }
    }
    const next = this.ctx.storage.sql
      .exec<{ at: number | null }>("SELECT MIN(expires) AS at FROM ask_cache")
      .one().at;
    const pending=this.read<number>('daily:refreshAt');
    if (next || pending)
      await this.ctx.storage.setAlarm(Math.max(Date.now()+1000,Math.min(next||Infinity,pending||Infinity)));
    else this.cleanupScheduled = false;
  }
  private researchBudget() {
    const date=new Date().toISOString(),day=date.slice(0,10),month=date.slice(0,7);
    return researchBudgetState({aiDay:this.read<number>('quota:ai:'+day)||0,aiMonth:this.read<number>('quota:ai:'+month)||0,searchDay:this.read<number>('quota:search:'+day)||0,searchMonth:this.read<number>('quota:search:'+month)||0,neurons:this.read<number>('quota:neurons:'+day)||0});
  }
  private reserve(kind: "search" | "ai", count = 1) {
    const now = new Date(),
      month = now.toISOString().slice(0, 7),
      day = now.toISOString().slice(0, 10);
    const limits = kind === "search" ? [40, 800] : [96, 2400];
    const keys = [`quota:${kind}:${day}`, `quota:${kind}:${month}`];
    const values = keys.map((k) => this.read<number>(k) || 0);
    if (values.some((n, i) => n + count > limits[i]))
      throw new ServiceError(
        "RESEARCH_QUOTA",
        "Research limit reached",
        429,
        true,
      );
    keys.forEach((k, i) => this.put(k, values[i] + count, 40 * DAY));
  }
  private async single<T>(key: string, fn: () => Promise<T>): Promise<T> {
    const existing = this.flights.get(key);
    if (existing) return existing as Promise<T>;
    if (this.flights.size >= 4)
      throw new ServiceError("RESEARCH_BUSY", "Research is busy", 429, true);
    const task = fn();
    this.flights.set(key, task);
    try {
      return await task;
    } finally {
      this.flights.delete(key);
    }
  }
  async reserveExternalSearch() {
    this.reserve("search");
  }
  /** A quota rejection is a circuit breaker, not proof of a full-day outage.
   * One tiny same-model probe per hour can reopen it. Only its availability
   * flag is removed; all real daily/monthly/neuron counters remain intact. */
  private async recoverQuotaCircuit() {
    return this.single('quota-recovery',async()=>{
      const day=new Date().toISOString().slice(0,10),key='quota:provider-blocked:'+day;
      if(!this.read<boolean>(key)||!this.env.AI||Date.now()-(this.read<number>(key+':checkedAt')||0)<3600000)return;
      const ledger='quota:neurons:'+day,held=this.read<number>(ledger)||0;
      if(held+10>9000)return;
      this.put(key+':checkedAt',Date.now(),2*DAY);this.put(ledger,held+10,2*DAY);
      const run=this.env.AI.run('@cf/openai/gpt-oss-120b',{messages:[{role:'user',content:'Reply OK.'}],max_tokens:16,reasoning_effort:'low'});
      const settle=run.then(result=>{
        const n=(result as {usage?:{neurons?:number}})?.usage?.neurons;
        this.put(ledger,Math.max(0,(this.read<number>(ledger)||0)-10)+(typeof n==='number'&&Number.isFinite(n)?n:10),2*DAY);
      },()=>{this.put(ledger,Math.max(0,(this.read<number>(ledger)||0)-10),2*DAY);});
      this.ctx.waitUntil(settle);
      try {
        await boundedInference(run,new AbortController().signal,8000);
        this.ctx.storage.sql.exec('DELETE FROM ask_cache WHERE key IN (?,?)',key,key+':retryAt');
        console.log(JSON.stringify({event:'ask_quota_circuit_reopened'}));
      }catch{
        const retryAt=Math.min(this.nextResearchDay(),Date.now()+3600000);
        this.put(key+':retryAt',retryAt,2*DAY);
      }
    });
  }
  async cancel(owner: string, id: string) {
    this.controllers.get(owner + ":" + id)?.abort();
  }
  async web(
    input: z.infer<typeof WebResearchSchema>,
    signal?: AbortSignal,
    reusable = false,
  ): Promise<Research> {
    const key = "web-v2:" + (await digest({ ...input, reusable })),
      cached = this.read<Research>(key);
    if (cached) return { ...cached, cached: true, credits: 0 };
    const shared = this.webControllers.get(key) || new AbortController();
    this.webControllers.set(key, shared);
    this.webWaiters.set(key, (this.webWaiters.get(key) || 0) + 1);
    try {
      return await boundedInference(
        this.single(key, async () => {
          if (!this.env.TAVILY_API_KEY)
            throw new ServiceError(
              "SEARCH_UNAVAILABLE",
              "Web research unavailable",
              503,
              true,
            );
          remaining(shared.signal);
          this.reserve("search");
          const response = await fetch("https://api.tavily.com/search", {
            method: "POST",
            headers: {
              Authorization: "Bearer " + this.env.TAVILY_API_KEY,
              "content-type": "application/json",
            },
            signal: AbortSignal.any([
              shared.signal,
              AbortSignal.timeout(10000),
            ]),
            body: JSON.stringify({
              query: input.query,
              search_depth: "basic",
              auto_parameters: false,
              max_results: reusable ? 20 : 8,
              ...(reusable
                ? {
                    include_domains: ["en.wikipedia.org", "www.tvmaze.com"],
                    include_domains_mode: "restrict",
                  }
                : {}),
              include_raw_content: false,
              include_answer: "basic",
              include_usage: true,
              ...(input.recent ? { time_range: "year", include_published_date: true } : {}),
            }),
          });
          if (!response.ok)
            throw new ServiceError(
              response.status === 429 ? "RESEARCH_QUOTA" : "SEARCH_UNAVAILABLE",
              "Web research unavailable",
              response.status === 429 ? 429 : 503,
              true,
            );
          const body = (await boundedJson(response, 128000)) as {
            answer?:string;
            results?: Array<Record<string, unknown>>;
            usage?: { credits?: number };
          };
          const evidence = (body.results || [])
            .flatMap((r) => {
              const url = safeResearchUrl(String(r.url || "")),
                text = clean(r.content, 1600),
                title = clean(r.title, 180);
              return url && text && title
                ? [
                    {
                      url,
                      title,
                      text,
                      published: clean(r.published_date, 40) || undefined,
                      attribution:
                        "Source excerpt via Tavily; original publisher retains rights. Factual research only.",
                    },
                  ]
                : [];
            })
            .slice(0, reusable ? 20 : 8);
          if (!evidence.length)
            throw new ServiceError(
              "NO_RESEARCH",
              "No reliable research found",
              422,
              true,
            );
          const result = {
            evidence,
            answer:clean(body.answer,6000),
            cached: false,
            credits: body.usage?.credits ?? 1,
            retrievedAt: Date.now(),
          };
          this.put(
            key,
            result,
            input.recent ? 3_600_000 : reusable ? 7 * DAY : DAY,
          );
          return result;
        }),
        signal || AbortSignal.timeout(12000),
        12000,
      );
    } finally {
      const count = (this.webWaiters.get(key) || 1) - 1;
      if (count <= 0) {
        shared.abort();
        this.webWaiters.delete(key);
        if (this.webControllers.get(key) === shared)
          this.webControllers.delete(key);
      } else this.webWaiters.set(key, count);
    }
  }
  async research(
    owner: string,
    id: string,
    input: z.infer<typeof WebResearchSchema>,
  ) {
    const controller = new AbortController();
    this.controllers.set(owner + ":" + id, controller);
    try {
      return await this.web(input, controller.signal);
    } catch (error) {
      throw wireFailure(error);
    } finally {
      this.controllers.delete(owner + ":" + id);
    }
  }
  async identity(
    type: MediaType,
    title: string,
    year: number,
    id?: number,
    deadline = Date.now() + 7000,
  ): Promise<ResearchMedia | null> {
    const key = "identity-v3:" + (await digest([type, title, year, id])),
      cached = this.read<ResearchMedia>(key);
    if (cached) return cached;
    const tmdb = new TmdbClient(
      this.env,
      5,
      AbortSignal.timeout(Math.max(1, Math.min(7000, deadline - Date.now()))),
    );
    let item;
    if (id) item = await tmdb.researchDetails(type, id);
    else {
      const page = await tmdb.searchTitle(type, title);
      item = page.results.find(
        (v) =>
          [v.title, v.name, v.original_title, v.original_name].some(
            (t) => t && normalize(t) === normalize(title),
          ) &&
          (!year || Math.abs(Number((v.release_date || v.first_air_date || "").slice(0, 4))-year)<=1),
      );
    }
    if (!item) return null;
    const d = (id ? item : await tmdb.researchDetails(type, item.id)) as TmdbDetails,
      c = toCandidate(item, type, new Map());
    if (!c) return null;
    mergeDetails(c, d);
    // TMDB titles, IDs and structured facts validate identity. Its editorial prose
    // is deliberately excluded from all model inputs and research caches.
    const result: RecommendationResult = {
      tmdbId: c.tmdbId,
      mediaType: type,
      title: c.title,
      originalTitle: c.originalTitle,
      posterPath: c.posterPath,
      backdropPath: c.backdropPath,
      releaseDate: c.releaseDate,
      genres: c.genres,
      runtimeMinutes: c.runtimeMinutes,
      originalLanguage: c.originalLanguage,
      originCountries: c.originCountries,
      tmdbRating: c.tmdbRating,
      tmdbVoteCount: c.tmdbVoteCount,
      status: c.status,
      matchLevel: "Relevant",
      finalScore: 0,
      matchReasons: [],
      retrievalSources: [],
    };
    const value = {
      ...result,
      imdbId: d.imdb_id || d.external_ids?.imdb_id || undefined,
      productionCompanyIds: c.productionCompanyIds,
      collectionId: c.collectionId,
    };
    this.put(key, value, 7 * DAY);
    return value;
  }
  async anchorContext(type:MediaType,id:number) {
    const key=`anchor-context-v1:${type}:${id}`,cached=this.read<{tmdbId:number;mediaType:MediaType;title?:string;originalTitle?:string;year:string;genres?:string[];countries?:string[];language?:string;creators?:string[];keywords?:string[]}>(key);
    if(cached)return cached;
    const d=await new TmdbClient(this.env,3,AbortSignal.timeout(2500)).researchAnchor(type,id);
    const value={tmdbId:id,mediaType:type,title:d.title||d.name,originalTitle:d.original_title||d.original_name,
      year:(d.release_date||d.first_air_date||'').slice(0,4),genres:d.genres?.map(v=>v.name),
      countries:d.origin_country,language:d.original_language,
      creators:type==='tv'?d.created_by?.map(v=>v.name):d.credits?.crew?.filter(v=>v.job==='Director').map(v=>v.name),
      keywords:(d.keywords?.keywords||d.keywords?.results)?.slice(0,12).map(v=>v.name)};
    this.put(key,value,7*DAY);return value;
  }
  async resolveBatch(
    type: MediaType,
    items: Array<{ title: string; year: number; id?:number }>,
    deadline: number,
  ): Promise<Array<ResearchMedia | null>> {
    if (items.length > 40) throw new Error("REQUEST_LIMIT");
    return lanes(items, 8, async (p) => {
      const shard = await digest([type, p.title, p.year]);
      return this.env.ASK_RESEARCH.getByName("identity:" + shard)
        .identity(type, p.title, p.year, p.id, deadline)
        .catch(() => null);
    });
  }
  async evidenceBatch(
    items: Array<{ title: string; year: number; imdbId?: string }>,
    deadline: number,
  ): Promise<Array<{ title: string; finding: WebFinding }>> {
    if (items.length > 40) throw new Error("REQUEST_LIMIT");
    const key = "evidence-v4:" + (await digest(items)),
      cached = this.read<Array<{ title: string; finding: WebFinding }>>(key);
    if (cached) return cached;
    const facts = await candidateEvidence(
      items,
      AbortSignal.timeout(Math.max(1, Math.min(16000, deadline - Date.now()))),
    );
    const values = [...facts].map(([title, finding]) => ({ title, finding }));
    this.put(
      key,
      values,
      values.length >= Math.ceil(items.length * 0.8) ? 7 * DAY : 60000,
    );
    return values;
  }
  async recommend(owner:string,raw:unknown):Promise<ResearchResult>{return this.recommendInternal(owner,raw);}
  async recommendStream(owner:string,raw:unknown):Promise<ReadableStream<Uint8Array>> {
    const request=AskRequestSchema.parse(raw),abort=new AbortController(),encoder=new TextEncoder();
    let controller:ReadableByteStreamController,pending:Uint8Array|undefined,done=false,closed=false,revision=0;
    const flush=()=>{if(closed)return;if(pending&&(controller.desiredSize??0)>0){const next=pending;pending=undefined;controller.enqueue(next);}if(done&&!pending&&!closed){closed=true;controller.close();}};
    const send=(value:Record<string,unknown>)=>{if(closed)return;pending=encoder.encode(JSON.stringify({requestId:request.requestId,revision:++revision,...value})+'\n');flush();};
    return new ReadableStream({
      type:'bytes',
      start:c=>{
        controller=c;
        send({type:'progress',inProgress:true});
        this.ctx.waitUntil(this.recommendInternal(owner,request,value=>send({type:'results',...value,inProgress:true}),abort.signal)
          .then(value=>send({type:'results',...value,inProgress:false}))
          .catch(error=>send({type:'error',inProgress:false,error:{code:researchFailure(error)?.code||'RESEARCH_UNAVAILABLE',message:'Research unavailable'}}))
          .finally(()=>{done=true;flush();}));
      },
      pull:()=>flush(),
      cancel:()=>{closed=true;pending=undefined;abort.abort();},
    },{highWaterMark:1});
  }
  private retainedResults(key:string):ResearchResult|undefined {
    const row=this.ctx.storage.sql.exec<{value:string;expires:number}>('SELECT value,expires FROM ask_cache WHERE key=?',key).toArray()[0];
    if(!row)return;
    const value=JSON.parse(row.value) as ResearchResult&{cachedAt?:number};
    const created=value.cachedAt??row.expires-(value.results?.length>=20?DAY:120000);
    if(value.results?.length&&Date.now()-created>=0&&Date.now()-created<DAY)return value;
  }
  private async recommendInternal(owner: string, raw: unknown,onProgress?:(value:ResearchResult)=>void,externalSignal?:AbortSignal):Promise<ResearchResult> {
    const request = AskRequestSchema.parse(raw),
      controller = new AbortController(),
      job = owner + ":" + request.requestId;
    const key = await digest(researchCacheIdentity(request));
    const shared =
      this.recommendationControllers.get(key) || new AbortController();
    this.recommendationControllers.set(key, shared);
    this.recommendationWaiters.set(
      key,
      (this.recommendationWaiters.get(key) || 0) + 1,
    );
    this.controllers.set(job, controller);
    const progressKey='fast-results-v5:'+key;
    if(onProgress){const listeners=this.progressListeners.get(progressKey)||new Set();listeners.add(onProgress);this.progressListeners.set(progressKey,listeners);const latest=this.latestProgress.get(progressKey);if(latest)onProgress(latest);}
    try {
      if(externalSignal)remaining(externalSignal);
      return await boundedInference(
        this.build(
          request,
          AbortSignal.any([shared.signal, AbortSignal.timeout(40000)]),
          owner === "scheduled",
        ),
        externalSignal?AbortSignal.any([controller.signal,externalSignal]):controller.signal,
        41000,
      );
    } catch (error) {
      const partial=this.latestProgress.get(progressKey);
      if(partial?.results.length&&!controller.signal.aborted&&!externalSignal?.aborted){
        const retained={...partial,inProgress:false,incomplete:true,cachedAt:Date.now()};
        this.put(progressKey,retained,DAY);return retained;
      }
      console.warn(
        "ask_pipeline_failed",
        error instanceof Error
          ? error.name + ": " + error.message.slice(0, 180)
          : "unknown",
      );
      throw wireFailure(error);
    } finally {
      if(onProgress){this.progressListeners.get(progressKey)?.delete(onProgress);if(!this.progressListeners.get(progressKey)?.size)this.progressListeners.delete(progressKey);}
      this.controllers.delete(job);
      const count = (this.recommendationWaiters.get(key) || 1) - 1;
      if (count <= 0) {
        this.latestProgress.delete(progressKey);
        shared.abort();
        this.recommendationWaiters.delete(key);
        if (this.recommendationControllers.get(key) === shared)
          this.recommendationControllers.delete(key);
      } else this.recommendationWaiters.set(key, count);
    }
  }
  private async build(request: AskRequest, signal: AbortSignal, _editorial=false):Promise<ResearchResult> {
    const started=Date.now(), key='fast-results-v5:'+await digest(researchCacheIdentity(request));
    const cached=this.read<ResearchResult>(key);
    if(cached?.results.length)return {...cached,cached:true};
    return this.single(key,async()=>{
      const usage:Usage={elapsedMs:0,tavilyCredits:0,researchCached:false,gptCalls:0,geminiCalls:0,clefCalls:0,generated:0,verified:0,accepted:0,neurons:0,pipeline:'tavily-gemini-tmdb'};
      const anchors=request.anchors||(request.anchor?[request.anchor]:[]);
      const anchorPromise=Promise.all(anchors.map(a=>this.env.ASK_RESEARCH.getByName(`anchor:${a.mediaType}:${a.tmdbId}`).anchorContext(a.mediaType,a.tmdbId).catch(()=>({...a,year:'',creators:[] as string[],originalTitle:a.title}))));
      const query=[request.previousQuery,request.query,request.refinementQuery].filter(Boolean).join('\n');
      const canonicalAnchors=await anchorPromise;
      const searchQuery=[anchors.length?'Best '+(request.mediaType==='tv'?'TV series':'films')+' similar to '+canonicalAnchors.map(a=>'"'+a.title+'" '+a.year+(a.originalTitle!==a.title?' '+a.originalTitle:'')+' '+(a.creators||[]).join(' ')).join(' and '):query||'Excellent distinctive films and series',anchors.length?query:'',anchors.length?'same creator tone atmosphere strongest related recommendations with release years':request.mediaType==='tv'?'TV series recommendations with release years':'film recommendations titles and release years',request.filters.includedGenres.join(' '),request.filters.minimumYear?'since '+request.filters.minimumYear:'',request.filters.maximumYear?'before '+request.filters.maximumYear:''].filter(Boolean).join(' ').slice(0,600);
      const tastePromise=Promise.all(researchTaste(request.library).filter(v=>v.kind==='favorite').slice(0,4).map(v=>this.env.ASK_RESEARCH.getByName('taste:'+v.type+':'+v.id).identity(v.type,'',0,v.id,started+6500).catch(()=>null)));
      let research:Research={evidence:[],cached:false,credits:0,retrievedAt:Date.now()};
      try{research=await this.web({query:searchQuery,recent:false},signal);}
      catch(error){remaining(signal);console.warn(JSON.stringify({event:'ask_search_unavailable',code:researchFailure(error)?.code||'SEARCH_UNAVAILABLE'}));}
      remaining(signal);
      const filmFindings=research.evidence.filter(e=>!/complaints|reportfraud|robocalls/i.test(e.url)&&/\b(?:films?|movies?|cinema|director|television|series|thriller|comedy|drama|sci.fi)\b|أفلام|فيلم|مسلسل|Filme/i.test(e.title+' '+e.text));
      if(!filmFindings.length)research.answer=undefined;
      research.evidence=filmFindings;
      usage.tavilyCredits=research.credits;usage.researchCached=research.cached;
      const favorites=(await tastePromise).filter(v=>!!v).map(v=>({title:v.title,year:v.releaseDate?.slice(0,4)}));
      usage.geminiCalls=1;
      const filters=researchFilters(request.mediaType,request.filters,RecommendationFiltersSchema.parse({}),query);
      // Similar is a comparison catalogue: watching/liking a strong match does
      // not make it dissimilar. Explicit dislikes and filters still exclude it.
      const excluded=new Set([...request.library.filter(v=>v.type===request.mediaType&&(v.kind==='dislike'||request.mode!=='similar'&&v.kind!=='saved')).map(v=>v.id),...anchors.filter(a=>a.mediaType===request.mediaType).map(a=>a.tmdbId)]);
      const matched=new Map<number,RecommendationResult>();
      const snapshot=()=>[...matched.entries()].sort((a,b)=>a[0]-b[0]).map(v=>v[1]).filter((v,i,a)=>a.findIndex(m=>m.tmdbId===v.tmdbId)===i).slice(0,Math.min(24,request.pageSize));
      const lanes=Array.from({length:3},()=>Promise.resolve());
      const pending:Array<{pick:{title:string;year:number};index:number}>=[];
      let scheduled=false,batchNumber=0;
      const apply=(m:ResearchMedia|null,index:number)=>{
        if(!m||excluded.has(m.tmdbId))return;
        const c=toCandidate({id:m.tmdbId,...(request.mediaType==='tv'?{name:m.title,first_air_date:m.releaseDate}:{title:m.title,release_date:m.releaseDate})},request.mediaType,new Map());
        if(!c)return;Object.assign(c,m,{productionCompanyIds:m.productionCompanyIds||[]});
        if(!passesHardFilters(c,filters))return;
        remaining(signal);
        matched.set(index,{...m,finalScore:0,matchReasons:[],retrievalSources:research.evidence.map(e=>e.url)});
        usage.firstResultsMs??=Date.now()-started;
        const progress={results:snapshot(),metrics:{...usage,elapsedMs:Date.now()-started},inProgress:true};
        this.latestProgress.set(key,progress);this.progressListeners.get(key)?.forEach(listener=>listener(progress));
      };
      const flush=()=>{
        scheduled=false;
        while(pending.length){
          const batch=pending.splice(0,4),lane=batchNumber++%lanes.length;
          lanes[lane]=lanes[lane].then(async()=>{
            remaining(signal);
            const shard=await digest(batch.map(v=>v.pick));
            const rows=await this.env.ASK_RESEARCH.getByName('fast-resolver:'+shard).resolveBatch(request.mediaType,batch.map(v=>v.pick),Math.min(started+25000,Date.now()+5000));
            rows.forEach((m,i)=>apply(m,batch[i].index));
          });
          void lanes[lane].catch(()=>{});
        }
      };
      const enqueue=(pick:{title:string;year:number},index:number)=>{
        pending.push({pick,index});if(!scheduled){scheduled=true;queueMicrotask(flush);}
      };
      const sourcePicks=tavilyCandidatePicks(research.answer||'',research.evidence);
      if(!request.candidateHints?.length)sourcePicks.slice(0,12).forEach((pick,index)=>enqueue(pick,100+index));
      let picks:Array<{title:string;year:number}>;
      try {if(request.candidateHints?.length){picks=request.candidateHints;picks.forEach(enqueue);usage.geminiCalls=0;usage.pipeline='tavily-gemini-live-tmdb';}else picks=await liveRecommendationPicks(this.env,{query,mode:request.mode,mediaType:request.mediaType,anchors:await anchorPromise,filters:request.filters,favorites,count:Math.min(24,request.pageSize),searchAnswer:research.answer,sources:research.evidence.map(e=>({...e,text:e.text.slice(0,1200)}))},signal,enqueue);}
      catch(error){
        remaining(signal);
        picks=sourcePicks;
        if(!picks.length)throw error;
        usage.pipeline='tavily-tmdb';
        picks.forEach(enqueue);
        console.warn(JSON.stringify({event:'ask_tavily_capacity_recovery',count:picks.length}));
      }
      usage.generated=picks.length;
      flush();await Promise.all(lanes);
      remaining(signal);
      const results=snapshot();
      usage.elapsedMs=Date.now()-started;usage.firstResultsMs??=usage.elapsedMs;usage.verified=results.length;usage.accepted=results.length;
      const value={results,metrics:usage,cached:false};
      if(results.length)this.put(key,value,usage.pipeline==='tavily-tmdb'?300000:DAY);
      console.log(JSON.stringify({event:'ask_fast_complete',...usage}));
      return value;
    });
  }
  async daily(type: MediaType) {
    return (
      this.read<{ results: RecommendationResult[]; publishedAt: number }>(
        "daily:" + type,
      ) || { results: [], publishedAt: 0 }
    );
  }
  async catalogueDaily() {
    return this.single('catalogue-daily',async()=>{
      const old=['movie','tv'].map(type=>this.read<{results:RecommendationResult[];publishedAt:number;source:string}>('daily:catalogue:'+type));
      if(old.every(v=>v&&v.results.length>=20&&v.publishedAt>Date.now()-DAY))return old;
      try {
        const today=new Date().toISOString().slice(0,10);const minimum=Number(today.slice(0,4))-3;
        const tmdb=new TmdbClient(this.env,14,AbortSignal.timeout(8500));
        const collections=await lanes(['movie','tv'] as MediaType[],2,async type=>{
          const [pages,genre]=await Promise.all([lanes([1,2,3],2,page=>tmdb.discover(type,{page,sort_by:'popularity.desc','vote_count.gte':20,'vote_average.gte':6,...(type==='tv'?{'first_air_date.gte':`${minimum}-01-01`,'first_air_date.lte':today}:{'primary_release_date.gte':`${minimum}-01-01`,'primary_release_date.lte':today})})),tmdb.genres(type)]);
          return catalogueSuggestions(pages.flatMap(p=>p.results),type,new Map(genre.genres.map(g=>[g.id,g.name])),today);
        });
        if(collections.some(v=>v.length<20))throw Error('CATALOGUE_INCOMPLETE');
        const publishedAt=Date.now();const pair=collections.map(results=>({results,publishedAt,source:'catalogue'}));
        this.ctx.storage.transactionSync(()=>pair.forEach((v,i)=>this.put('daily:catalogue:'+(i?'tv':'movie'),v,400*DAY)));
        return pair;
      }catch(error){console.warn('ask_catalogue_retained',error instanceof Error?error.name:'unavailable');return old;}
    });
  }
  private nextResearchDay(){return Date.parse(new Date().toISOString().slice(0,10))+DAY+60000;}
  private quotaRetryAt(){return Math.max(this.read<number>('quota:provider-blocked:'+new Date().toISOString().slice(0,10)+':retryAt')||0,this.researchBudget().retryAt)||this.nextResearchDay();}
  /** First installation must bootstrap without an operator POST. The alarm owns
   * long research work; GET stays fast and never starts an unbounded waitUntil. */
  async dailyForClient(type:MediaType) {
    const collection=await this.daily(type);
    const refreshing=this.flights.has("daily-refresh");
    const budget=this.researchBudget();
    const limited=!!this.read<boolean>('quota:provider-blocked:'+new Date().toISOString().slice(0,10))||budget.limited;
    let pending=this.read<number>('daily:refreshAt');
    if(!collection.results.length && limited && !budget.limited && (this.read<number>('quota:neurons:'+new Date().toISOString().slice(0,10))||0)+10<=9000 && Date.now()-(this.read<number>('quota:provider-blocked:'+new Date().toISOString().slice(0,10)+':checkedAt')||0)>=3600000) {
      pending=Date.now()+1000;this.put('daily:refreshAt',pending,2*DAY);await this.ctx.storage.setAlarm(pending);
      const fallback=collection.results.length?undefined:(await this.catalogueDaily())[type==='tv'?1:0];
      const visible=collection.results.length?collection:fallback||collection;
      return {...visible,state:visible.results.length?'ready':'warming',retryAt:pending};
    }
    if(!collection.results.length && !pending && !refreshing) {
      const attempts=this.read<number>('daily:bootstrap:'+new Date().toISOString().slice(0,10))||0;
      if(limited || attempts<3 && await this.needsRefresh()) {
        pending=limited?this.quotaRetryAt():Date.now()+1000;
        this.put('daily:refreshAt',pending,2*DAY);
        await this.ctx.storage.setAlarm(pending);
      }
    }
    const fallback=collection.results.length?undefined:(await this.catalogueDaily())[type==='tv'?1:0];
    const visible=collection.results.length?{...collection,source:'research'}:fallback||collection;
    return {...visible,state:visible.results.length?'ready':refreshing?'warming':limited?'limited':pending?'warming':'unavailable',retryAt:refreshing?Date.now()+5000:pending||0,budget,lastFailure:this.read('daily:lastFailure'),lastEvaluation:this.read('daily:lastEvaluation')};
  }
  async refreshDaily() {
    return this.single("daily-refresh", async () => {
      await this.recoverQuotaCircuit();
      const date = new Date(),
        year = date.getUTCFullYear();
      const old = await Promise.all([this.daily("movie"), this.daily("tv")]);
      const collections = await lanes(
        ["movie", "tv"] as MediaType[],
        1,
        async (type, i) =>
          this.recommend("scheduled", {
            requestId: crypto.randomUUID(),
            mode: "describe",
            query: `Current, popular and noteworthy ${type === "movie" ? "films" : "series"} released ${year - 3} through ${year}. A diverse editorial discovery collection for ${date.toISOString().slice(0, 10)}. Avoid yesterday's picks where equally strong fresh choices exist.`,
            mediaType: type,
            filters: {
              minimumYear: year - 3,
              maximumYear: year,
              excludedTmdbIds: old[i].results.slice(0, 32).map((v) => v.tmdbId),
            },
            pageSize: 24,
          }),
      );
      console.log(JSON.stringify({event:"ask_daily_evaluated",counts:collections.map(c=>c.results.length),metrics:collections.map(c=>c.metrics)}));
        this.put('daily:lastEvaluation',{counts:collections.map(c=>c.results.length),at:Date.now()},2*DAY);
      if (collections.some((c) => c.results.length < 20))
        throw new ServiceError(
          "EDITORIAL_INCOMPLETE",
          "Daily collection retained",
          503,
          true,
        );
      // Both collections publish together only after the complete protocol succeeds.
      const publishedAt=Date.now();
      this.ctx.storage.transactionSync(() => {
        collections.forEach((c, i) =>
          this.put(
            "daily:" + (i ? "tv" : "movie"),
            { results: c.results, publishedAt, metrics: c.metrics },
            400 * DAY,
          ),
        );
      });
      return {
        published: true,
        counts: collections.map((c) => c.results.length),
      };
    }).catch(error=>{this.put('daily:lastFailure',{code:researchFailure(error)?.code||'RESEARCH_UNAVAILABLE',at:Date.now()},2*DAY);console.warn("ask_daily_failed",error instanceof Error?error.message.slice(0,180):"unknown");throw wireFailure(error);});
  }
  async needsRefresh() {
    const last = this.read<number>("daily:attempt") || 0;
    const [a, b] = await Promise.all([this.daily("movie"), this.daily("tv")]);
    // A transient first-run outage must not leave the carousel empty for an hour.
    // Keep repeated bootstrap attempts bounded; populated collections retain the
    // longer backoff and their last-good data.
    const backoff = a.results.length && b.results.length ? 3_600_000 : 300_000;
    if (Date.now() - last < backoff) return false;
    if (new Date(Math.min(a.publishedAt,b.publishedAt)).toISOString().slice(0,10)===new Date().toISOString().slice(0,10)) return false;
    this.put("daily:attempt", Date.now(), DAY);
    return true;
  }
}

export async function askRoute(
  request: Request,
  env: RecommendationEnv,
  ctx?: ExecutionContext,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (!url.pathname.startsWith("/v4/ask/")) return null;
  try {
    const owner = await verifyIdentity(
      request.headers.get("authorization")?.replace(/^Bearer /, "") || "",
      env.CURSOR_SIGNING_SECRET,
    );
    if (
      !(
        await env.RECOMMENDATION_RATE_LIMITER.limit({ key: "ask:" + owner.sub })
      ).success
    )
      throw new ServiceError("RATE_LIMITED", "Try again shortly", 429, true);
    const service = env.ASK_RESEARCH.getByName("shared-v1");
    if (url.pathname === "/v4/ask/daily" && request.method === "GET") {
      const type = z.enum(["movie", "tv"]).parse(url.searchParams.get("type"));
        return Response.json(await service.dailyForClient(type), {
        headers: { "cache-control": "no-store" },
      });
    }
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const raw = await boundedJson(new Response(request.body), 24000);
    if (url.pathname.endsWith("/refresh-daily")) {
      if (!(await service.needsRefresh()))
        return Response.json({ cached: true });
      return Response.json(await service.refreshDaily());
    }
    if (url.pathname.endsWith("/cancel")) {
      const b = z.object({ requestId: z.string().uuid() }).strict().parse(raw);
      await service.cancel(owner.sub, b.requestId);
      return Response.json({ cancelled: true });
    }
    if (url.pathname.endsWith("/research")) {
      const b = WebResearchSchema.parse(raw);
      return Response.json(
        await service.research(owner.sub, crypto.randomUUID(), b),
      );
    }
    if (url.pathname.endsWith("/recommendations")) {
      const b = AskRequestSchema.parse(raw);
      if(url.searchParams.get('stream')==='1')return new Response(await service.recommendStream(owner.sub,b),{headers:{'content-type':'application/x-ndjson; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'}});
      const result = await service.recommend(owner.sub, b);
      return Response.json(
        {
          requestId: b.requestId,
          ...result,
          totalResults: result.results.length,
          nextCursor: null,
          hasMore: false,
        },
        { headers: { "cache-control": "no-store" } },
      );
    }
    return new Response(null, { status: 404 });
  } catch (error) {
    const remote = researchFailure(error);
    if (remote)
      return Response.json(
        {
          error: {
            code: remote.code,
            message:
              remote.status === 429
                ? "Research limit reached"
                : "Research unavailable",
            retryable: true,
          },
        },
        { status: remote.status },
      );
    if (error instanceof ServiceError)
      return Response.json(
        {
          error: {
            code: error.code,
            message: error.message,
            retryable: error.retryable,
          },
        },
        { status: error.status },
      );
    if (error instanceof z.ZodError)
      return Response.json(
        { error: { code: "INVALID_REQUEST", retryable: false } },
        { status: 400 },
      );
    if (error instanceof Error && error.message === "UNAUTHORIZED")
      return Response.json(
        { error: { code: "UNAUTHORIZED", retryable: false } },
        { status: 401 },
      );
    console.warn(
      "ask_research_failed",
      error instanceof Error ? error.message.slice(0, 180) : "error",
    );
    return Response.json(
      {
        error: {
          code: "RESEARCH_UNAVAILABLE",
          message: "Research unavailable",
          retryable: true,
        },
      },
      { status: 503 },
    );
  }
}
