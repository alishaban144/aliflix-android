import { DurableObject } from 'cloudflare:workers';
import { z } from 'zod';
import { verifyIdentity } from './watchParty';
import type { RecommendationEnv } from './types';
import { TalkContextSchema, TalkContext, KnowledgeSchema, KnowledgeRequest, explicitlyRequestsSpoilers, permittedEpisode, retrieveKnowledge, Evidence } from './talkEvidence';
import { issueLiveToken, TALK_MODEL, TALK_VOICE, boundedJson } from './talkProviders';
const MINUTE=60000,DAY=86400000;
const json=(data:unknown,status=200)=>Response.json(data,{status,headers:{'cache-control':'no-store'}});
const sessionSchema=z.object({context:TalkContextSchema.default(()=>TalkContextSchema.parse({}))}).strict();
const requestSchema=z.object({session:z.string().uuid(),question:z.string().max(1600),request:KnowledgeSchema}).strict();
export async function talkRoute(request:Request,env:RecommendationEnv):Promise<Response|null> {
 const path=new URL(request.url).pathname;if(!path.startsWith('/v1/talk'))return null;
 if(request.method!=='POST'||!['/v1/talk/session','/v1/talk/knowledge','/v1/talk/close'].includes(path))return json({error:'UPDATE_REQUIRED'},426);
 try {
  const owner=await verifyIdentity(request.headers.get('Authorization')?.replace(/^Bearer /,'')||'',env.CURSOR_SIGNING_SECRET);
  if(Number(request.headers.get('Content-Length')||0)>24000)return json({error:'REQUEST_LIMIT'},413);
  const bodyRaw=await boundedJson(new Response(request.body),24000);
  if(!(await env.RECOMMENDATION_RATE_LIMITER.limit({key:'talk-live:'+owner.sub})).success)return json({error:'TRY_LATER'},429);
  const user=env.TALK_SESSIONS.getByName('user:'+owner.sub);
  if(path.endsWith('/session')) {
   const body=sessionSchema.parse(bodyRaw);const ip=request.headers.get('CF-Connecting-IP')||'unknown';
   const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(ip+env.CURSOR_SIGNING_SECRET)))).map(x=>x.toString(16).padStart(2,'0')).join('');
   if(!await env.TALK_SESSIONS.getByName('quota:ip:'+hash).reserve(1,12)||!await env.TALK_SESSIONS.getByName('quota:global').reserve(1,40))return json({error:'DAILY_LIMIT'},429);
   return json(await user.open(body.context));
  }
  if(path.endsWith('/close')) {const body=z.object({session:z.string().uuid()}).strict().parse(bodyRaw);await user.close(body.session);return json({closed:true});}
  const body=requestSchema.parse(bodyRaw);return json(await user.knowledge(body.session,body.request,body.question));
 }catch(e) {
  const code=e instanceof Error?e.message:'';
  if(e instanceof z.ZodError||e instanceof SyntaxError)return json({error:'INVALID_REQUEST'},400);
  if(['LIVE_UNAVAILABLE','SOURCE_UNAVAILABLE','EMPTY_RESPONSE'].includes(code))return json({error:'LIVE_UNAVAILABLE'},503);
  if(code==='RESPONSE_LIMIT')return json({error:'REQUEST_LIMIT'},413);
  if(code==='SESSION_EXPIRED')return json({error:code},410);if(code==='TOOL_LIMIT')return json({error:code},429);
  return json({error:'UNAUTHORIZED'},401);
 }
}
type Session={context:TalkContext;expires:number;calls:number;searches:number;grants:string[]};
export class TalkSession extends DurableObject<RecommendationEnv> {
 constructor(ctx:DurableObjectState,env:RecommendationEnv){super(ctx,env);ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS memory (key TEXT PRIMARY KEY,value TEXT NOT NULL,expires INTEGER NOT NULL)');for(const ws of ctx.getWebSockets())ws.close(1012,'Update');}
 private read<T>(key:string):T|undefined {const r=this.ctx.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=? AND expires>?',key,Date.now()).toArray()[0];return r?JSON.parse(r.value):undefined;}
 private put(key:string,value:unknown,expires:number){this.ctx.storage.sql.exec('INSERT OR REPLACE INTO memory VALUES (?,?,?)',key,JSON.stringify(value),expires);}
 async reserve(amount:number,limit:number):Promise<boolean>{const day=Math.floor(Date.now()/DAY),key='quota:'+day;this.ctx.storage.sql.exec('DELETE FROM memory WHERE expires<?',Date.now());
  const r=this.ctx.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=?',key).toArray()[0];const n=Number(r?.value||0);if(n+amount>limit)return false;
  this.ctx.storage.sql.exec('INSERT OR REPLACE INTO memory VALUES(?,?,?)',key,String(n+amount),(day+2)*DAY);await this.ctx.storage.setAlarm((day+2)*DAY);return true;}
 async open(context:TalkContext) {const expires=Date.now()+20*MINUTE,session=crypto.randomUUID();const token=await issueLiveToken(this.env,context,expires);
  this.ctx.storage.sql.exec("DELETE FROM memory WHERE key LIKE 'live:%'");this.put('live:'+session,{context,expires,calls:0,searches:0,grants:[]} satisfies Session,expires);
  await this.ctx.storage.setAlarm(expires);return {...token,session};}
 async close(session:string){this.ctx.storage.sql.exec('DELETE FROM memory WHERE key=?','live:'+session);}
 async knowledge(session:string,q:KnowledgeRequest,question:string) {
  const state=this.read<Session>('live:'+session);if(!state)throw Error('SESSION_EXPIRED');
  const key=JSON.stringify([q.title.toLowerCase(),q.year,q.kind,q.season,q.episode]);const explicit=explicitlyRequestsSpoilers(question);
  if(explicit&&!state.grants.includes(key))state.grants.push(key);
  const episodeRequested=q.kind==='tv'&&q.season!=null&&q.episode!=null&&/\bs\d+\s*e\d+\b|\bepisode\s*\d+|\bfolge\s*\d+|الحلقة\s*\d+/i.test(question);
  const allowPlot=q.kind==='movie'?explicit||state.grants.includes(key):episodeRequested||state.grants.includes(key);
  this.put('live:'+session,state,state.expires);
  const cacheKey='evidence:'+JSON.stringify([key,allowPlot,state.context.imdbId,state.context.viewedRanges,state.context.completed]);const cached=this.read<Evidence[]>(cacheKey);
  if(cached)return {model:TALK_MODEL,voice:TALK_VOICE,evidence:cached,cached:true};
  if(++state.calls>12)throw Error('TOOL_LIMIT');
  const allowSearch=!!this.env.TAVILY_API_KEY;
  state.grants=state.grants.slice(-12);this.put('live:'+session,state,state.expires);
  let evidence:Evidence[]=[];try{evidence=await retrieveKnowledge(this.env,q,allowPlot,AbortSignal.timeout(7000),allowSearch,state.context,()=>this.searchBudget(session));}catch{}
  this.put(cacheKey,evidence,Date.now()+(evidence.length?DAY:30000));
  const count=this.ctx.storage.sql.exec<{n:number}>("SELECT COUNT(*) n FROM memory WHERE key LIKE 'evidence:%'").one().n;
  if(count>40)this.ctx.storage.sql.exec("DELETE FROM memory WHERE key IN(SELECT key FROM memory WHERE key LIKE 'evidence:%' ORDER BY expires LIMIT ?)",count-40);
  return {model:TALK_MODEL,voice:TALK_VOICE,evidence,cached:false,boundary:allowPlot?'requested title/episode only':'No unviewed plot details; answer reliable general knowledge naturally',unavailable:!evidence.length};
 }
 private async searchBudget(session:string){const current=this.read<Session>('live:'+session);if(!current||current.searches>=2)return false;current.searches++;this.put('live:'+session,current,current.expires);return this.env.TALK_SESSIONS.getByName('quota:search').reserve(1,20);}
 async alarm(){this.ctx.storage.sql.exec('DELETE FROM memory WHERE expires<=?',Date.now());const row=this.ctx.storage.sql.exec<{next:number}>('SELECT MIN(expires) next FROM memory').one();if(row.next)await this.ctx.storage.setAlarm(row.next);}
}
