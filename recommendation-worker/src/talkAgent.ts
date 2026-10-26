import { DurableObject } from 'cloudflare:workers';
import { z } from 'zod';
import { AskRequestSchema, WebResearchSchema } from './askResearch';
import { RecommendationFiltersSchema } from './schemas';
import { TmdbClient } from './tmdb';
import { verifyIdentity } from './watchParty';
import { boundedJson } from './talkProviders';
import type { RecommendationEnv, RecommendationResult } from './types';

const HOUR=3600000;
const DateOnly=z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine(v=>{const stamp=Date.parse(v+'T12:00:00Z');return Number.isFinite(stamp)&&new Date(stamp).toISOString().slice(0,10)===v;},'Use a real calendar date');
const Identity=z.object({id:z.number().int().positive(),type:z.enum(['movie','tv']),title:z.string().max(180).optional()});
const Step=z.object({
 id:z.string().regex(/^[a-z][a-z0-9_-]{0,31}$/),
 kind:z.enum(['research','discover','catalogue','episodes','schedule','collection','calendar','franchise','filmography','festival']),
 dependsOn:z.array(z.string().max(32)).max(6).default([]),
 query:z.string().max(2000).optional(),mediaType:z.enum(['movie','tv']).default('movie'),
 titles:z.array(Identity).max(12).default([]),
 filters:RecommendationFiltersSchema.default(()=>RecommendationFiltersSchema.parse({})),
 budgetMinutes:z.number().int().min(1).max(10080).optional(),
 count:z.number().int().min(1).max(24).optional(),
 breakMinutes:z.number().int().min(0).max(120).default(0),
 season:z.number().int().min(1).max(100).optional(),
 personId:z.number().int().positive().optional(),
 offset:z.number().int().min(0).max(500).default(0),
 mergeMode:z.enum(['any','all']).default('any'),
 onlyReleased:z.boolean().default(false),
 startDate:DateOnly.optional(),
 deadline:DateOnly.optional(),
}).strict().transform(step=>({...step,count:step.count??(step.kind==='schedule'&&step.titles.length?step.titles.length:6)}));
export const MissionPlan=z.object({
 name:z.string().trim().min(1).max(80),goal:z.string().trim().min(3).max(2000),
 steps:z.array(Step).min(1).max(10),
 repeatHours:z.number().int().min(6).max(720).optional(),
 delivery:z.enum(['cards','collection']).default('cards'),
}).strict().superRefine((plan,ctx)=>{
 const seen=new Set<string>();
 for(const [index,step] of plan.steps.entries()){
  if(seen.has(step.id)||step.dependsOn.some(id=>!seen.has(id)))ctx.addIssue({code:'custom',message:'Steps must be a unique ordered dependency graph',path:['steps',index]});
  if(step.kind==='schedule'&&!step.budgetMinutes)ctx.addIssue({code:'custom',message:'Schedule needs an actual time budget',path:['steps',index]});
  if(step.kind==='episodes'&&step.titles.some(t=>t.type!=='tv'))ctx.addIssue({code:'custom',message:'Episodes require series identities',path:['steps',index]});
  if((step.kind==='calendar'||step.kind==='festival')&&(!step.startDate||!step.deadline||!step.budgetMinutes||step.deadline<step.startDate))ctx.addIssue({code:'custom',message:'Calendar needs ordered dates and a daily time budget',path:['steps',index]});
  if((step.kind==='calendar'||step.kind==='festival')&&step.startDate&&step.deadline){
   const days=(Date.parse(step.deadline+'T12:00:00Z')-Date.parse(step.startDate+'T12:00:00Z'))/86400000+1;
   if(days>(step.kind==='festival'?14:90))ctx.addIssue({code:'custom',message:'Split longer plans into bounded date windows',path:['steps',index]});
  }
  if(step.kind==='filmography'&&!step.personId)ctx.addIssue({code:'custom',message:'Use a verified person identity',path:['steps',index]});
  seen.add(step.id);
 }
});
type Plan=z.infer<typeof MissionPlan>;
type Snapshot={library:z.infer<typeof AskRequestSchema>['library'];completed:string[]};
type Output={results?:RecommendationResult[];evidence?:Array<{title:string;url:string;text:string;published?:string}>;episodes?:Episode[];minutes?:number;unknownRuntime?:number[];calendar?:Array<{date:string;episodes:Episode[];minutes:number}>;festival?:Array<{date:string;results:RecommendationResult[];minutes:number}>;unscheduled?:number;totalAvailable?:number;nextOffset?:number;truncated?:boolean};
type Episode={id:number;type:'tv';season:number;episode:number;airDate:string;runtimeMinutes?:number};
type Mission={id:string;revision:number;plan:Plan;status:'queued'|'running'|'ready'|'waiting'|'paused'|'failed'|'cancelled'|'needs_input';collection?:RecommendationResult[];
 outputs:Record<string,Output>;snapshot:Snapshot;updatedAt:number;nextRunAt?:number;error?:string;change:number;signature?:string};
const Request=z.object({operation:z.enum(['create','update','list','read','pause','resume','cancel','remember','forget','memories']),
 id:z.string().uuid().optional(),expectedRevision:z.number().int().positive().optional(),plan:MissionPlan.optional(),
 memory:z.object({key:z.string().trim().min(1).max(60).refine(v=>!v.startsWith('__')),value:z.string().trim().min(1).max(500)}).optional(),
 library:z.array(z.object({id:z.number().int().positive(),type:z.enum(['movie','tv']),kind:z.enum(['favorite','saved','watched','dislike'])})).max(80).default([]),
 completed:z.array(z.string().max(80)).max(1000).default([]),
}).strict();

/** Exact runtime packing; unknown metadata never becomes a guessed duration. */
export function agentProgramme(items:RecommendationResult[],budget:number,count:number,gap=0){
 const known=items.filter(v=>v.runtimeMinutes&&v.runtimeMinutes>0);
 let states=new Map<string,{items:RecommendationResult[];minutes:number;score:number}>();states.set('0:0',{items:[],minutes:0,score:0});
 for(const [rank,item] of known.entries())for(const state of [...states.values()]){
  const minutes=state.minutes+item.runtimeMinutes!+(state.items.length?gap:0),size=state.items.length+1;
  if(minutes>budget||size>count)continue;
  const key=size+':'+minutes,score=state.score+known.length-rank;
  if(!states.has(key)||states.get(key)!.score<score)states.set(key,{items:[...state.items,item],minutes,score});
 }
 const best=[...states.values()].filter(v=>v.items.length===count).sort((a,b)=>b.score-a.score||a.minutes-b.minutes)[0];
 return {results:best?.items||[],minutes:best?.minutes||0,unknownRuntime:items.filter(v=>!v.runtimeMinutes).map(v=>v.tmdbId)};
}

export function agentCalendar(episodes:Episode[],start:string,end:string,budget:number,gap=0){
 const days:Array<{date:string;episodes:Episode[];minutes:number}>=[];
 let at=0;
 for(let stamp=Date.parse(start+'T12:00:00Z');Number.isFinite(stamp)&&stamp<=Date.parse(end+'T12:00:00Z')&&days.length<90;stamp+=86400000){
  const day={date:new Date(stamp).toISOString().slice(0,10),episodes:[] as Episode[],minutes:0};
  while(at<episodes.length){
   const episode=episodes[at];const cost=episode.runtimeMinutes;
   if(!cost||cost+(day.episodes.length?gap:0)+day.minutes>budget)break;
   day.minutes+=cost+(day.episodes.length?gap:0);day.episodes.push(episode);at++;
  }
  days.push(day);
 }
 return {calendar:days,unscheduled:episodes.length-at};
}
export function agentFestival(items:RecommendationResult[],start:string,end:string,budget:number,count:number,gap=0){
 const festival:Array<{date:string;results:RecommendationResult[];minutes:number}>=[];let remaining=items;let unscheduled=0;
 for(let stamp=Date.parse(start+'T12:00:00Z');stamp<=Date.parse(end+'T12:00:00Z')&&festival.length<14;stamp+=86400000){
  const programme=agentProgramme(remaining,budget,count,gap);
  if(!programme.results.length)unscheduled++;
  festival.push({date:new Date(stamp).toISOString().slice(0,10),results:programme.results,minutes:programme.minutes});
  const ids=new Set(programme.results.map(v=>v.mediaType+':'+v.tmdbId));remaining=remaining.filter(v=>!ids.has(v.mediaType+':'+v.tmdbId));
 }
 return {festival,unscheduled,results:festival.flatMap(v=>v.results)};
}

export class TalkAgent extends DurableObject<RecommendationEnv> {
 private controllers=new Map<string,AbortController>();
 constructor(ctx:DurableObjectState,env:RecommendationEnv){super(ctx,env);ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS missions (id TEXT PRIMARY KEY, data TEXT NOT NULL)');ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS preferences (key TEXT PRIMARY KEY,value TEXT NOT NULL)');}
 private all(){return this.ctx.storage.sql.exec<{data:string}>('SELECT data FROM missions').toArray().map(r=>JSON.parse(r.data) as Mission);}
 private get(id:string){return this.all().find(m=>m.id===id);}
 private put(m:Mission){this.ctx.storage.sql.exec('INSERT OR REPLACE INTO missions VALUES (?,?)',m.id,JSON.stringify(m));}
 async command(raw:unknown){
  const r=Request.parse(raw);
  if(typeof raw==='object'&&raw!==null&&'library' in raw)this.ctx.storage.sql.exec('INSERT OR REPLACE INTO preferences VALUES (?,?)','__snapshot',JSON.stringify({library:r.library,completed:r.completed}));
  if(r.operation==='memories')return {memories:this.memories()};
  if(r.operation==='remember'){
   if(!r.memory)throw Error('INVALID_REQUEST');
   if(this.memories().length>=32&&!this.memories().some(m=>m.key===r.memory!.key))throw Error('TASK_LIMIT');
   this.ctx.storage.sql.exec('INSERT OR REPLACE INTO preferences VALUES (?,?)',r.memory.key,r.memory.value);return {status:'completed',memories:this.memories()};
  }
  if(r.operation==='forget'){
   if(!r.memory)throw Error('INVALID_REQUEST');this.ctx.storage.sql.exec('DELETE FROM preferences WHERE key=?',r.memory.key);return {status:'completed',memories:this.memories()};
  }
  if(r.operation==='list')return {missions:this.all().sort((a,b)=>b.updatedAt-a.updatedAt).slice(0,32).map(m=>{
   const last=m.plan.steps.at(-1)!.id,output=m.outputs[last];
   return {id:m.id,revision:m.revision,status:m.status,updatedAt:m.updatedAt,change:m.change,error:m.error,
    plan:{name:m.plan.name,goal:m.plan.goal.slice(0,200),steps:[{id:last,kind:m.plan.steps.at(-1)!.kind}]},
    outputs:{[last]:output?{...output,moreResults:(output.results?.length||0)>12||(output.episodes?.length||0)>24,evidence:output.evidence?.map(e=>({title:e.title,url:e.url,published:e.published})),
     results:output.results?.slice(0,12).map(v=>({tmdbId:v.tmdbId,mediaType:v.mediaType,title:v.title,releaseDate:v.releaseDate,posterPath:v.posterPath,runtimeMinutes:v.runtimeMinutes})),episodes:output.episodes?.slice(0,24)}:{}}};
  })};
  if(r.operation==='create'){
   if(!r.plan)throw Error('INVALID_REQUEST');
   if(this.all().filter(m=>m.status!=='cancelled').length>=16)throw Error('TASK_LIMIT');
   const m:Mission={id:crypto.randomUUID(),revision:1,plan:r.plan,status:'queued',outputs:{},snapshot:{library:r.library,completed:r.completed},updatedAt:Date.now(),change:0};
   this.put(m);await this.ctx.storage.setAlarm(Date.now()+100);return this.public(m);
  }
  const m=r.id?this.get(r.id):undefined;if(!m)throw Error('TASK_NOT_FOUND');
  if(r.operation==='read')return this.public(m);
  if(r.expectedRevision!==m.revision)return {status:'changed',current:this.public(m)};
  this.controllers.get(m.id)?.abort();this.controllers.delete(m.id);
  m.revision++;m.updatedAt=Date.now();
  if(r.operation==='cancel'||r.operation==='pause'){m.status=r.operation==='cancel'?'cancelled':'paused';m.nextRunAt=undefined;}
  else {
   if(r.operation==='update'){
    if(!r.plan)throw Error('INVALID_REQUEST');
    const retained:Record<string,Output>={};
    for(const step of r.plan.steps){
     const previous=m.plan.steps.find(v=>v.id===step.id);
     const goalChanged=m.plan.goal!==r.plan.goal&&!step.query&&['research','discover'].includes(step.kind);
     if(previous&&JSON.stringify(previous)===JSON.stringify(step)&&!goalChanged&&step.dependsOn.every(id=>!!retained[id])&&m.outputs[step.id])retained[step.id]=m.outputs[step.id];
    }
    m.plan=r.plan;m.outputs=retained;m.collection=undefined;
   }
   m.snapshot={library:r.library,completed:r.completed};m.status='queued';m.error=undefined;m.nextRunAt=undefined;
  }
  this.put(m);await this.schedule();return this.public(m);
 }
 private memories(){return this.ctx.storage.sql.exec<{key:string;value:string}>("SELECT key,value FROM preferences WHERE key <> '__snapshot' ORDER BY key").toArray();}
 private public(m:Mission){return {...m,snapshot:undefined,instruction:'This is durable app work, not a completed action until ready. Use exact task id/revision to steer it. Do not describe match explanations. Results are catalogue identities; never claim playback or downloads started.'};}
 private async schedule(){
  const due=this.all().flatMap(m=>m.status==='queued'?[Date.now()+100]:m.status==='running'?[m.updatedAt+120000]:m.status==='waiting'&&m.nextRunAt?[m.nextRunAt]:[]);
  if(due.length)await this.ctx.storage.setAlarm(Math.min(...due));else await this.ctx.storage.deleteAlarm();
 }
 async alarm(){
  // One mission per invocation bounds fanout and per-user research spend.
  const m=this.all().filter(m=>m.status==='queued'||m.status==='running'&&m.updatedAt+120000<=Date.now()||m.status==='waiting'&&(m.nextRunAt||Infinity)<=Date.now()).sort((a,b)=>a.updatedAt-b.updatedAt)[0];
  if(!m){await this.schedule();return;}
  const controller=new AbortController();this.controllers.set(m.id,controller);const revision=m.revision;
  const current=()=>this.get(m.id)?.revision===revision&&!controller.signal.aborted;
  if(m.status==='waiting')m.outputs={};
  const snapshot=this.ctx.storage.sql.exec<{value:string}>('SELECT value FROM preferences WHERE key=?','__snapshot').toArray()[0];
  if(snapshot)m.snapshot=JSON.parse(snapshot.value);
  m.status='running';m.updatedAt=Date.now();this.put(m);await this.ctx.storage.setAlarm(Date.now()+120000);
  try{
   for(const step of m.plan.steps){
    if(!current())return;
    if(m.outputs[step.id])continue;
    const dependencies=step.dependsOn.map(id=>m.outputs[id]).filter(Boolean);
    m.outputs[step.id]=await this.step(m,step,dependencies,controller.signal);
    if(!current())return;this.put(m);break;
   }
   if(m.plan.steps.some(step=>!m.outputs[step.id])){m.status='queued';return;}
   const final=m.outputs[m.plan.steps.at(-1)!.id];
   if(m.plan.steps.at(-1)!.kind==='schedule'&&!final.results?.length){m.status='needs_input';m.error='NO_FEASIBLE_PROGRAMME';return;}
   if(final.truncated&&m.plan.steps.at(-1)!.kind==='calendar'){m.status='needs_input';m.error='INCOMPLETE_EPISODE_CATALOGUE';return;}
   if(final.unscheduled){m.status='needs_input';m.error='INSUFFICIENT_TIME_OR_RUNTIME_DATA';return;}
   if(m.plan.steps.at(-1)!.kind==='collection'&&m.plan.steps.at(-1)!.mergeMode==='all'&&!final.results?.length){m.status='needs_input';m.error='NO_COMMON_MATCH';return;}
   if(m.plan.delivery==='collection'&&m.plan.repeatHours&&final.results){m.collection=[...(m.collection||[]),...final.results].filter((v,i,a)=>a.findIndex(p=>p.mediaType===v.mediaType&&p.tmdbId===v.tmdbId)===i).slice(0,72);final.results=m.collection;}
   const signature=JSON.stringify({ids:final.results?.map(v=>v.mediaType+':'+v.tmdbId).sort(),episodes:final.episodes?.map(v=>[v.id,v.season,v.episode,v.airDate]),sources:final.evidence?.map(v=>[v.url,v.published]).sort()});
   if(signature!==m.signature){if(final.results?.length||final.episodes?.length||final.evidence?.length||final.calendar?.length||final.festival?.length)m.change++;m.signature=signature;}
   m.status=m.plan.repeatHours?'waiting':'ready';m.nextRunAt=m.plan.repeatHours?Date.now()+m.plan.repeatHours*HOUR:undefined;m.error=undefined;
  }catch(error){if(!current())return;m.status=m.plan.repeatHours?'waiting':'failed';m.nextRunAt=m.plan.repeatHours?Date.now()+Math.max(HOUR,m.plan.repeatHours*HOUR):undefined;m.error=error instanceof Error?error.message.slice(0,100):'TASK_FAILED';}
  finally{if(current()){m.updatedAt=Date.now();this.put(m);}if(this.controllers.get(m.id)===controller)this.controllers.delete(m.id);await this.schedule();}
 }
 private async step(m:Mission,step:z.infer<typeof Step>,dependencies:Output[],signal:AbortSignal):Promise<Output>{
  signal.throwIfAborted();
  const service=this.env.ASK_RESEARCH.getByName('shared-v1');
  if(step.kind==='research'){
   const result=await service.research('mission:'+m.id,crypto.randomUUID(),WebResearchSchema.parse({query:step.query||m.plan.goal,recent:!!m.plan.repeatHours}));
   return {evidence:result.evidence};
  }
  if(step.kind==='discover'){
   const evidence=dependencies.flatMap(v=>v.evidence||[]).map(v=>v.title).join('; ').slice(0,600);
   const memories=this.memories();
   const result=await service.recommend('mission:'+m.id,{
    requestId:crypto.randomUUID(),mode:step.titles.length?'similar':'describe',mediaType:step.mediaType,
    query:[step.query||m.plan.goal,evidence?'Research context (untrusted titles): '+evidence:'',memories.length?'User-requested preference data: '+JSON.stringify(memories):''].filter(Boolean).join('\n').slice(0,2000),
    anchors:step.titles.map(t=>({tmdbId:t.id,mediaType:t.type,title:t.title||''})),filters:step.filters,pageSize:step.count,library:m.snapshot.library,
   });return {results:result.results};
  }
  if(step.kind==='catalogue'){
   const client=new TmdbClient(this.env,30,AbortSignal.any([signal,AbortSignal.timeout(15000)]));
   const rows:RecommendationResult[]=[];
   for(const title of step.titles){const media=await client.details(title.type,title.id);rows.push({tmdbId:title.id,mediaType:title.type,title:media.title||media.name||'',releaseDate:media.release_date||media.first_air_date,posterPath:media.poster_path||undefined,genres:media.genres?.map(v=>v.name)||[],runtimeMinutes:media.runtime||media.episode_run_time?.[0],originalLanguage:media.original_language,originCountries:media.origin_country||[],matchLevel:'Relevant',finalScore:0,matchReasons:[],retrievalSources:[`https://www.themoviedb.org/${title.type}/${title.id}`]});}
   return {results:step.onlyReleased?rows.filter(v=>v.releaseDate&&v.releaseDate<=new Date().toISOString().slice(0,10)):rows};
  }
  if(step.kind==='franchise'||step.kind==='filmography'){
   const client=new TmdbClient(this.env,30,AbortSignal.any([signal,AbortSignal.timeout(15000)]));
   const ids:Array<{id:number;type:'movie'|'tv'}>=[];
   if(step.kind==='franchise')for(const title of step.titles){
    if(title.type!=='movie')continue;
    const detail=await client.details('movie',title.id);
    if(detail.belongs_to_collection){const collection=await client.collection(detail.belongs_to_collection.id);ids.push(...collection.parts.map(v=>({id:v.id,type:'movie' as const})));}
   }else {
    const credits=await client.personCombinedCredits(step.personId!);
    ids.push(...(credits.crew||[]).filter(v=>v.job==='Director'||v.job==='Creator').sort((a,b)=>(a.release_date||a.first_air_date||'9999').localeCompare(b.release_date||b.first_air_date||'9999')).map(v=>({id:v.id,type:(v.media_type==='tv'?'tv':'movie') as 'tv'|'movie'})));
   }
   const rows:RecommendationResult[]=[];
   const unique=ids.filter((v,i,a)=>a.findIndex(p=>p.id===v.id&&p.type===v.type)===i);
   for(const title of unique.slice(step.offset,step.offset+12)){
    const output=await this.step(m,{...step,kind:'catalogue',titles:[title]},[],signal);rows.push(...output.results||[]);
   }
   return {results:rows.filter(v=>v.releaseDate&&v.releaseDate<=new Date().toISOString().slice(0,10)).sort((a,b)=>(a.releaseDate||'').localeCompare(b.releaseDate||'')),totalAvailable:unique.length,truncated:step.offset+12<unique.length,nextOffset:step.offset+12<unique.length?step.offset+12:undefined};
  }
  if(step.kind==='episodes'){
   const client=new TmdbClient(this.env,30,AbortSignal.any([signal,AbortSignal.timeout(15000)])),episodes:Episode[]=[];let truncated=false;
   for(const title of step.titles){
    const available=step.season?[step.season]:(await client.seasons(title.id)).seasons.filter(v=>v.number>0).map(v=>v.number);
    if(available.length>12)truncated=true;const seasons=available.slice(0,12);
    for(const season of seasons)for(const e of (await client.episodes(title.id,season)).episodes){
     if(e.airDate&&e.airDate<=new Date().toISOString().slice(0,10)&&!m.snapshot.completed.includes(`tv:${title.id}:s${season}:e${e.number}`))episodes.push({id:title.id,type:'tv',season,episode:e.number,airDate:e.airDate,runtimeMinutes:e.runtime||undefined});
    }
   }return {episodes:episodes.slice(0,300),truncated:truncated||episodes.length>300};
  }
  // Explicit identities are valid inputs too: hydrate them through the same
  // canonical catalogue/episode code, rather than treating an empty dependency
  // list as proof that the requested programme is impossible.
  if(step.titles.length){
   const direct=await this.step(m,{...step,kind:step.kind==='calendar'?'episodes':'catalogue'},[],signal);
   dependencies=[...dependencies,direct];
  }
  const results=dependencies.flatMap(v=>v.results||[]).filter((v,i,a)=>a.findIndex(p=>p.tmdbId===v.tmdbId&&p.mediaType===v.mediaType)===i)
    .filter(v=>step.mergeMode!=='all'||dependencies.every(d=>d.results?.some(p=>p.tmdbId===v.tmdbId&&p.mediaType===v.mediaType)));
  if(step.kind==='calendar')return {...agentCalendar(dependencies.flatMap(v=>v.episodes||[]),step.startDate!,step.deadline!,step.budgetMinutes!,step.breakMinutes),truncated:dependencies.some(v=>v.truncated)};
  if(step.kind==='festival')return agentFestival(results,step.startDate!,step.deadline!,step.budgetMinutes!,step.count,step.breakMinutes);
  if(step.kind==='schedule')return agentProgramme(results,step.budgetMinutes!,step.count,step.breakMinutes);
  return {results:results.slice(0,step.count),episodes:dependencies.flatMap(v=>v.episodes||[])};
 }
}

export async function agentRoute(request:Request,env:RecommendationEnv):Promise<Response|null>{
 if(new URL(request.url).pathname!=='/v4/ask/agent')return null;
 if(request.method!=='POST')return new Response(null,{status:405});
 try{
  const owner=await verifyIdentity(request.headers.get('Authorization')?.replace(/^Bearer /,'')||'',env.CURSOR_SIGNING_SECRET);
  if(!(await env.RECOMMENDATION_RATE_LIMITER.limit({key:'agent:'+owner.sub})).success)return Response.json({error:{code:'TRY_LATER'}},{status:429});
  const raw=await boundedJson(new Response(request.body),48000);
  return Response.json(await env.TALK_AGENTS.getByName(owner.sub).command(raw),{headers:{'cache-control':'no-store'}});
 }catch(error){const code=error instanceof z.ZodError?'INVALID_REQUEST':error instanceof Error?error.message:'TASK_FAILED';return Response.json({error:{code}},{status:code==='TASK_CHANGED'?409:400});}
}
