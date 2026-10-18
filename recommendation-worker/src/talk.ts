import { DurableObject } from 'cloudflare:workers';
import { z } from 'zod';
import { verifyIdentity } from './watchParty';
import type { RecommendationEnv } from './types';
import { TalkContextSchema, TalkContext, KnowledgeSchema, KnowledgeRequest, explicitlyRequestsSpoilers, permittedEpisode, retrieveKnowledge, Evidence, requestedEpisodeBoundary, avoidsSpoilers, resolveViewingTitle, TalkLibraryItemSchema, TalkViewingPageSchema } from './talkEvidence';
import { issueLiveToken, TALK_MODEL, TALK_VOICE, boundedJson } from './talkProviders';
const MINUTE=60000,DAY=86400000;
const json=(data:unknown,status=200)=>Response.json(data,{status,headers:{'cache-control':'no-store'}});
const sessionSchema=z.object({context:TalkContextSchema.default(()=>TalkContextSchema.parse({}))}).strict();
const requestSchema=z.object({session:z.string().uuid(),question:z.string().max(1600),request:KnowledgeSchema}).strict();
export async function talkRoute(request:Request,env:RecommendationEnv):Promise<Response|null> {
 const path=new URL(request.url).pathname;if(!path.startsWith('/v1/talk'))return null;
 if(request.method!=='POST'||!['/v1/talk/session','/v1/talk/knowledge','/v1/talk/close','/v1/talk/viewing'].includes(path))return json({error:'UPDATE_REQUIRED'},426);
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
  if(path.endsWith('/viewing')) {const body=z.object({session:z.string().uuid(),items:z.array(TalkLibraryItemSchema).max(8).optional(),page:TalkViewingPageSchema.optional()}).strict().parse(bodyRaw);return json(await user.viewing(body.session,body.items,body.page));}
  if(path.endsWith('/close')) {const body=z.object({session:z.string().uuid(),forget:z.boolean().default(false)}).strict().parse(bodyRaw);await user.close(body.session,body.forget);return json({closed:true});}
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
  this.ctx.storage.sql.exec("DELETE FROM memory WHERE key LIKE 'live:%' OR key LIKE 'viewing:%'");this.put('live:'+session,{context,expires,calls:0,searches:0,grants:[]} satisfies Session,expires);
  await this.ctx.storage.setAlarm(expires);return {...token,session};}
 async viewing(session:string,items?:z.infer<typeof TalkLibraryItemSchema>[],page?:z.infer<typeof TalkViewingPageSchema>) {
  const state=this.read<Session>('live:'+session);if(!state)throw Error('SESSION_EXPIRED');
  const cacheKey='viewing:'+session+':'+JSON.stringify([items||null,page||null]);
  const existing=this.read<unknown>(cacheKey);if(existing)return existing;
  if(++state.calls>12)throw Error('TOOL_LIMIT');this.put('live:'+session,state,state.expires);
  const signal=AbortSignal.timeout(3500);
  // Catalogue cards need not carry IMDb IDs. Resolve only external identity,
  // never catalogue titles/overviews, and only when personal context is asked for.
  if(!state.context.imdbId&&state.context.id&&state.context.type) {
   try {
    const url=new URL(`https://api.themoviedb.org/3/${state.context.type}/${state.context.id}/external_ids`);
    const headers:Record<string,string>={accept:'application/json'};
    if(this.env.TMDB_READ_ACCESS_TOKEN)headers.Authorization='Bearer '+this.env.TMDB_READ_ACCESS_TOKEN;
    else if(this.env.TMDB_API_KEY)url.searchParams.set('api_key',this.env.TMDB_API_KEY);
    const external=await boundedJson(await fetch(url,{headers,signal}),8192) as {imdb_id?:unknown};
    if(typeof external.imdb_id==='string'&&/^tt\d{1,12}$/.test(external.imdb_id)) {
     const current=this.read<Session>('live:'+session);if(!current)throw Error('SESSION_EXPIRED');
     current.context.imdbId=external.imdb_id;state.context=current.context;this.put('live:'+session,current,current.expires);
    }
   }catch{}
  }
  const identities=(items||[...(state.context.companionVersion===2?state.context.library:[]),
   ...(state.context.imdbId?[{imdbId:state.context.imdbId,type:state.context.type||'tv',id:state.context.id}]:[]),
   ...(state.context.recentProgress||[]),...(state.context.companionVersion===2?[]:state.context.library)])
   .filter((v,i,a)=>a.findIndex(x=>x.imdbId&&v.imdbId?x.imdbId===v.imdbId:!!x.id&&x.id===v.id&&x.type===v.type)===i).slice(0,8);
  const titles:Record<string,unknown>[]=[];
  // Bound fan-out to four lookups; the shared deadline also bounds partial outages.
  for(let at=0;at<identities.length;at+=4) {
   titles.push(...await Promise.all(identities.slice(at,at+4).map(async item=>{
    let imdbId=item.imdbId;
    if(!imdbId&&item.id&&!signal.aborted) {
     const key=`identity:${item.type}:${item.id}`;
     imdbId=this.read<string>(key);
     if(!imdbId)try {
      const url=new URL(`https://api.themoviedb.org/3/${item.type}/${item.id}/external_ids`);
      const headers:Record<string,string>={accept:'application/json'};
      if(this.env.TMDB_READ_ACCESS_TOKEN)headers.Authorization='Bearer '+this.env.TMDB_READ_ACCESS_TOKEN;
      else if(this.env.TMDB_API_KEY)url.searchParams.set('api_key',this.env.TMDB_API_KEY);
      const external=await boundedJson(await fetch(url,{headers,signal}),8192) as {imdb_id?:unknown};
      if(typeof external.imdb_id==='string'&&/^tt\d{1,12}$/.test(external.imdb_id)) {
       imdbId=external.imdb_id;
       if(!this.read<Session>('live:'+session))throw Error('SESSION_EXPIRED');
       this.put(key,imdbId,Date.now()+7*DAY);
       this.ctx.storage.sql.exec("DELETE FROM memory WHERE key LIKE 'identity:%' AND key NOT IN (SELECT key FROM memory WHERE key LIKE 'identity:%' ORDER BY expires DESC LIMIT 96)");
      }
     }catch{}
    }
    const resolved=imdbId?await resolveViewingTitle(imdbId,item.type,signal).catch(()=>({})):{};
    return {...item,...(imdbId?{imdbId}:{}),...resolved};
   })));
  }
  const result={context:state.context,titles,...(page?{page:{...page,nextOffset:page.offset+identities.length,hasMore:page.offset+identities.length<page.total}}:{}),
   tastePriority:'Favorites are the strongest explicit taste signal; saved/My List is secondary intent. Recent playback is evidence of starting, not necessarily finishing. Do not recommend already favored/watched titles as new discoveries. Request further library pages when needed.',
   boundary:'Only actual completed episodes are fully viewed. Position seconds are not timed plot evidence. Never assume missing history was watched.'};
  // An asynchronous lookup must not restore context after the owner closes it.
  if(!this.read<Session>('live:'+session))throw Error('SESSION_EXPIRED');
  const ttl=titles.every(t=>!!t.title)?5*MINUTE:30000;
  this.put(cacheKey,result,Math.min(state.expires,Date.now()+ttl));return result;
 }
 async close(session:string,forget=false){if(forget)this.ctx.storage.sql.exec('DELETE FROM memory');else {this.ctx.storage.sql.exec('DELETE FROM memory WHERE key=?','live:'+session);this.ctx.storage.sql.exec('DELETE FROM memory WHERE key LIKE ?','viewing:'+session+':%');}}
 async knowledge(session:string,q:KnowledgeRequest,question:string) {
  const state=this.read<Session>('live:'+session);if(!state)throw Error('SESSION_EXPIRED');
  const key=JSON.stringify([q.title.toLowerCase(),q.year,q.kind,q.season,q.episode]);
  const boundary=requestedEpisodeBoundary(question);
  const exactEpisode=!!boundary&&boundary.episode===q.episode&&(boundary.season==null||boundary.season===q.season);
  const avoids=avoidsSpoilers(question);
  if(avoids)state.grants=[];
  const explicit=!avoids&&explicitlyRequestsSpoilers(question)&&(q.kind==='movie'||!boundary||exactEpisode);
  const episodeRequested=!avoids&&exactEpisode&&/explain|recap|summari[sz]e|what.*(?:happen|know)|end|erkl[aä]r|zusammenfass|اشرح|ملخص|ماذا/i.test(question);
  if((explicit||episodeRequested)&&!state.grants.includes(key))state.grants.push(key);
  const allowPlot=!avoids&&(explicit||episodeRequested||state.grants.includes(key));
  this.put('live:'+session,state,state.expires);
  const cacheKey='evidence:'+JSON.stringify([key,allowPlot,state.context.imdbId,state.context.viewedRanges,state.context.completed]);const cached=this.read<Evidence[]>(cacheKey);
  if(cached)return {model:TALK_MODEL,voice:TALK_VOICE,evidence:cached,cached:true};
  if(++state.calls>12)throw Error('TOOL_LIMIT');
  const allowSearch=!!this.env.TAVILY_API_KEY;
  state.grants=state.grants.slice(-12);this.put('live:'+session,state,state.expires);
  let evidence:Evidence[]=[];try{evidence=await retrieveKnowledge(this.env,q,allowPlot,AbortSignal.timeout(7000),allowSearch,state.context,()=>this.searchBudget(session));}catch{}
  if(!this.read<Session>('live:'+session))throw Error('SESSION_EXPIRED');
  this.put(cacheKey,evidence,Date.now()+(evidence.length?DAY:30000));
  const count=this.ctx.storage.sql.exec<{n:number}>("SELECT COUNT(*) n FROM memory WHERE key LIKE 'evidence:%'").one().n;
  if(count>40)this.ctx.storage.sql.exec("DELETE FROM memory WHERE key IN(SELECT key FROM memory WHERE key LIKE 'evidence:%' ORDER BY expires LIMIT ?)",count-40);
  return {model:TALK_MODEL,voice:TALK_VOICE,evidence,cached:false,boundary:allowPlot?'requested title/episode only':'No unviewed plot details; answer reliable general knowledge naturally',unavailable:!evidence.length};
 }
 private async searchBudget(session:string){const current=this.read<Session>('live:'+session);if(!current||current.searches>=2)return false;current.searches++;this.put('live:'+session,current,current.expires);return this.env.TALK_SESSIONS.getByName('quota:search').reserve(1,20);}
 async alarm(){this.ctx.storage.sql.exec('DELETE FROM memory WHERE expires<=?',Date.now());const row=this.ctx.storage.sql.exec<{next:number}>('SELECT MIN(expires) next FROM memory').one();if(row.next)await this.ctx.storage.setAlarm(row.next);}
}
