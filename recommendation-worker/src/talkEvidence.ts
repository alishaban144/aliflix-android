import { z } from 'zod';
import { boundedJson } from './talkProviders';
import type { RecommendationEnv } from './types';
export const TalkContextSchema=z.object({stickyLanguage:z.boolean().optional(),responseLanguage:z.string().regex(/^[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,2}$/).optional(),id:z.number().int().positive().optional(),type:z.enum(['movie','tv']).optional(),
 imdbId:z.string().regex(/^tt\d{1,12}$/).optional(),season:z.number().int().min(0).max(1000).default(1),episode:z.number().int().min(1).max(10000).default(1),
 position:z.number().finite().min(0).max(604800).default(0),completed:z.boolean().default(false),
 library:z.array(z.object({imdbId:z.string().regex(/^tt\d{1,12}$/),kind:z.enum(['saved','favorite','watched']),type:z.enum(['movie','tv'])}).strict()).max(8).default([]),
 recentProgress:z.array(z.object({imdbId:z.string().regex(/^tt\d{1,12}$/),type:z.enum(['movie','tv']),
 season:z.number().int().min(0).max(1000),episode:z.number().int().min(1).max(10000),position:z.number().finite().min(0).max(604800),
 duration:z.number().finite().min(0).max(604800),completed:z.boolean()}).strict()).max(8).optional(),
 viewedRanges:z.array(z.object({season:z.number().int().min(0).max(1000),first:z.number().int().min(1).max(10000),last:z.number().int().min(1).max(10000)}).strict().refine(r=>r.last>=r.first)).max(120).default([])}).strict();
export type TalkContext=z.infer<typeof TalkContextSchema>;
export type Evidence={id:string;title:string;text:string;url:string;license:string;season?:number;episode?:number};
export const KnowledgeSchema=z.object({title:z.string().trim().min(1).max(120),year:z.number().int().min(1880).max(2100).optional(),
 kind:z.enum(['movie','tv']),season:z.number().int().min(0).max(1000).optional(),episode:z.number().int().min(1).max(10000).optional(),
 query:z.string().trim().max(500)}).strict();
export type KnowledgeRequest=z.infer<typeof KnowledgeSchema>;
export function stripMarkup(s:string) {return s.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/gi,'').replace(/<[^>]*>/g,' ').replace(/&amp;/g,'&').replace(/&#39;|&apos;/g,"'").replace(/&quot;/g,'"').replace(/&#(\d+);/g,(_,n)=>String.fromCodePoint(Number(n))).replace(/\[\d+\]/g,'').replace(/\s+/g,' ').trim().slice(0,6500);}
const normalize=(s:string)=>s.normalize('NFKD').toLowerCase().replace(/\p{M}/gu,'').replace(/\([^)]*\)/g,'').replace(/^(the|a|an)\s+/,'').replace(/[^\p{L}\p{N}]/gu,'');
export function explicitlyRequestsSpoilers(text:string):boolean {
 if(/\b(no spoilers?|without spoilers?|spoiler.?free|nicht spoilern|keine spoiler)\b|بدون حرق|لا تحرق/i.test(text)) return false;
 return /\b(ending|ends?|finale|final (scene|photograph|photo)|twist|dies?|death|killer|spoilers?|schluss|ende|stirbt|tod|aufl[oö]sung|finale)\b|نهاية|النهايه|يموت|موت|القاتل|الصورة الأخيرة|الصوره الاخيره/i.test(text);
}
export function requestedEpisodeBoundary(text:string):{season?:number;episode:number}|undefined {
 const both=text.match(/\bs(\d+)\s*e(\d+)\b|\bseason\s*(\d+)\s*[,.: -]*episode\s*(\d+)|\bstaffel\s*(\d+)\s*[,.: -]*folge\s*(\d+)/i);
 if(both){const values=both.slice(1).filter(v=>v!=null);return {season:Number(values[0]),episode:Number(values[1])};}
 const one=text.match(/\bepisode\s*(\d+)|\bfolge\s*(\d+)|الحلقة\s*(\d+)/i);if(one)return {episode:Number(one.slice(1).find(v=>v!=null))};
}
export function avoidsSpoilers(text:string):boolean {return /\bno spoilers?(?!\s+(?:after|beyond|past))|\bwithout spoilers?|\bspoiler.?free|keine spoiler|nicht spoilern|بدون حرق|لا تحرق/i.test(text);}
export function permittedEpisode(c:TalkContext,s:number,e:number):boolean {return s===c.season&&e===c.episode?c.completed:c.viewedRanges.some(r=>r.season===s&&e>=r.first&&e<=r.last);}
const WIKI='https://en.wikipedia.org/w/api.php?format=json&origin=*&';
async function json(url:string,signal:AbortSignal,limit=512000):Promise<any> {
 return boundedJson(await fetch(url,{signal:AbortSignal.any([signal,AbortSignal.timeout(2500)]),headers:{'user-agent':'Aliflix/3.1.170 (+https://github.com/alishaban144/aliflix-android)','accept':'application/json'},cf:{cacheTtl:3600}}),limit);
}
export function reusableUrl(value:string):boolean {try {const u=new URL(value);return u.protocol==='https:'&&!u.username&&!u.password&&u.port===''&&
 (u.hostname==='en.wikipedia.org'&&u.pathname.startsWith('/wiki/')||u.hostname==='www.tvmaze.com'&&/^\/(shows|episodes)\/\d+/.test(u.pathname));}catch{return false;}}
export async function wikipediaTitle(q:KnowledgeRequest,signal:AbortSignal):Promise<{title:string;imdbId?:string;pageid:number;year?:number}|undefined> {
 const query=`${q.title} ${q.year||''} ${q.kind==='movie'?'film':'television series'}`;
 const r=await json(WIKI+'action=query&generator=search&gsrnamespace=0&gsrlimit=4&gsrsearch='+encodeURIComponent(query)+'&prop=pageprops',signal);
 const pages=Object.values(r.query?.pages||{}) as any[];
 const ids=pages.map(p=>p.pageprops?.wikibase_item).filter((x:unknown)=>typeof x==='string'&&/^Q\d+$/.test(x));
 if(!ids.length)return;
 const entities=await json('https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims|sitelinks&ids='+ids.join('|'),signal);
 for(const page of pages) {
  const item=entities.entities?.[page.pageprops?.wikibase_item];if(!item)continue;
  const values=(key:string)=>(item.claims?.[key]||[]).map((v:any)=>v.mainsnak?.datavalue?.value);
  const year=values('P577').map((v:any)=>Number(v?.time?.slice(1,5))).filter((v:number)=>v>1800);
  const imdb=values('P345').find((v:unknown)=>typeof v==='string'&&/^tt\d+$/.test(v));
  const exact=normalize(page.title)===normalize(q.title);
  if(!exact||q.year&&!year.includes(q.year))continue;
  // Reject a remake/television adaptation that happens to share a title.
  const types=values('P31').map((v:any)=>v?.id);
  if(q.kind==='movie'&&types.some((v:string)=>['Q5398426','Q581714','Q15416'].includes(v)))continue;
  return {title:page.title,imdbId:imdb,pageid:page.pageid,year:q.year||year[0]};
 }
}
export async function wikipediaEvidence(title:string,signal:AbortSignal):Promise<Evidence[]> {
 // Revision IDs come from query/revisions, not the unsupported parse prop=revid.
 const info=await json(WIKI+'action=query&prop=info|revisions&rvprop=ids&inprop=url&titles='+encodeURIComponent(title),signal);
 const page=(Object.values(info.query?.pages||{}) as any[])[0];const revision=page?.revisions?.[0]?.revid;
 if(!Number.isInteger(revision))return [];
 const parse=await json(WIKI+`action=parse&oldid=${revision}&prop=sections`,signal);
 const sections=(parse.parse?.sections||[]).filter((s:any)=>/^(plot|plot summary|synopsis|ending|interpretations?|analysis|themes|ambiguity)/i.test(stripMarkup(s.line||''))).slice(0,3);
 const results=await Promise.allSettled(sections.map(async(s:any)=>{
  if(!/^\d{1,3}$/.test(s.index))return null;
  const body=await json(WIKI+`action=parse&oldid=${revision}&prop=text&section=${s.index}`,signal);
  return stripMarkup(body.parse?.text?.['*']||'');
 }));
 const text=results.flatMap(r=>r.status==='fulfilled'&&r.value?[r.value]:[]).join('\n').slice(0,9000);
 return text?[{id:'wikipedia-'+page.pageid+'-'+revision,title:page.title,text,url:`https://en.wikipedia.org/w/index.php?oldid=${revision}`,license:'Wikipedia contributors · CC BY-SA 4.0 · adapted'}]:[];
}
export async function tvEpisodeEvidence(q:KnowledgeRequest,imdb:string|undefined,signal:AbortSignal):Promise<Evidence[]> {
 let show:any;
 if(imdb) {try{show=await json('https://api.tvmaze.com/lookup/shows?imdb='+imdb,signal);}catch{}}
 if(!show) {
  const matches=await json('https://api.tvmaze.com/search/shows?q='+encodeURIComponent(q.title),signal);
  show=matches.map((v:any)=>v.show).find((s:any)=>normalize(s.name)===normalize(q.title)&&(!q.year||s.premiered?.startsWith(String(q.year))));
 }
 if(!show)return [];
 if(q.season==null||q.episode==null)return [{id:'tvmaze-show-'+show.id,title:show.name,text:JSON.stringify({name:show.name,year:show.premiered?.slice(0,4),genres:show.genres,imdbId:show.externals?.imdb}),url:show.url,license:'TVmaze · CC BY-SA 4.0'}];
 const ep=await json(`https://api.tvmaze.com/shows/${show.id}/episodebynumber?season=${q.season}&number=${q.episode}`,signal);
 if(ep.season!==q.season||ep.number!==q.episode||!ep.summary)return [];
 return [{id:'tvmaze-episode-'+ep.id,title:`${show.name} · S${ep.season} E${ep.number} · ${ep.name}`,text:stripMarkup(ep.summary),url:ep.url,license:'TVmaze · CC BY-SA 4.0',season:ep.season,episode:ep.number}];
}
export async function tavilyEvidence(env:RecommendationEnv,q:KnowledgeRequest,signal:AbortSignal):Promise<Evidence[]> {
 if(!env.TAVILY_API_KEY)return [];
 const query=`${q.title} ${q.year||''} ${q.kind==='tv'?`season ${q.season||1} episode ${q.episode||1}`:'film'} ${q.query}`.slice(0,400);
 const r=await boundedJson(await fetch('https://api.tavily.com/search',{method:'POST',signal,headers:{'authorization':'Bearer '+env.TAVILY_API_KEY,'content-type':'application/json'},
  body:JSON.stringify({query,search_depth:'basic',max_results:3,include_domains:['en.wikipedia.org','tvmaze.com'],include_answer:false,include_raw_content:false})}));
 // Search discovers sources, not licences. Fetch only independently validated
 // reusable pages; never pass third-party snippets, TMDB or arbitrary URLs.
 const results=(r as any).results||[];
 const evidence:Evidence[]=[];
 for(const hit of results.filter((v:any)=>reusableUrl(v.url)).slice(0,2)) {
  const u=new URL(hit.url);const content=stripMarkup(String(hit.content||'')).slice(0,3500);
  if(q.kind==='tv') {
   const label=(String(hit.title||'')+' '+content).toLowerCase();
   const number=label.match(/(?:season|s)\s*(\d+)\s*[,.: -]*(?:episode|e)\s*(\d+)|(\d+)x(\d+)/i);
   const values=number?.slice(1).filter((v:any)=>v!=null);
   if(u.hostname==='www.tvmaze.com'&&u.pathname.startsWith('/episodes/')&&values&&Number(values[0])===q.season&&Number(values[1])===q.episode&&normalize(label).includes(normalize(q.title))&&content.length>40)
    evidence.push({id:'tvmaze-cached-'+u.pathname.split('/')[2],title:String(hit.title),text:content,url:u.toString(),license:'TVmaze · CC BY-SA 4.0 · cached extract via Tavily',season:q.season,episode:q.episode});
   continue;
  }
  if(u.hostname!=='en.wikipedia.org')continue;
  const title=decodeURIComponent(u.pathname.slice(6)).replaceAll('_',' ');
  if(normalize(title)!==normalize(q.title))continue;
  try {const exact=await wikipediaTitle({...q,title},signal);if(exact)evidence.push(...await wikipediaEvidence(exact.title,signal));}catch{}
  if(!evidence.length&&q.year&&content.includes(String(q.year))&&content.length>40)
   evidence.push({id:'wikipedia-cached-'+encodeURIComponent(title),title,text:content,url:u.toString(),license:'Wikipedia contributors · CC BY-SA 4.0 · cached extract via Tavily'});
 }

 return evidence;
}
export async function retrieveKnowledge(env:RecommendationEnv,q:KnowledgeRequest,allowPlot:boolean,signal:AbortSignal,allowSearch:boolean,context?:TalkContext,reserveSearch:()=>Promise<boolean>=async()=>true):Promise<Evidence[]> {
 let title:Awaited<ReturnType<typeof wikipediaTitle>>;
 try{title=await wikipediaTitle(q,signal);}catch{}
 if(q.kind==='tv') {
  const watched=!!context&&!!title?.imdbId&&title.imdbId===context.imdbId&&q.season!=null&&q.episode!=null&&permittedEpisode(context,q.season,q.episode);
  let result:Evidence[]=[];try{result=await tvEpisodeEvidence(allowPlot||watched?q:{...q,season:undefined,episode:undefined},title?.imdbId,signal);}catch{}
  if(!result.length&&(allowPlot||watched)&&q.episode!=null&&allowSearch&&await reserveSearch())try{result=await tavilyEvidence(env,q,signal);}catch{}
  return result;
 }
 if(!allowPlot)return title?[{id:'wikidata-title-'+title.pageid,title:title.title,text:JSON.stringify(title),url:'https://en.wikipedia.org/wiki/'+encodeURIComponent(title.title),license:'Wikidata · CC0; Wikipedia title · CC BY-SA 4.0'}]:[];
 let result:Evidence[]=[];if(title)try{result=await wikipediaEvidence(title.title,signal);}catch{}
 if(!result.length&&allowSearch&&await reserveSearch())try{result=await tavilyEvidence(env,q,signal);}catch{}
 return result;
}

/** Independent title identity only: TVMaze CC BY-SA and Wikidata CC0. No TMDB prose. */
export async function resolveViewingTitle(imdbId:string,kind:'movie'|'tv',signal:AbortSignal):Promise<{title:string;year?:number;url:string;license:string}|undefined> {
 if(!/^tt\d{1,12}$/.test(imdbId))return;
 try {
  if(kind==='tv') {try {
   const show=await json('https://api.tvmaze.com/lookup/shows?imdb='+imdbId,AbortSignal.any([signal,AbortSignal.timeout(1000)]),64000);
   if(show.externals?.imdb===imdbId&&typeof show.name==='string'&&/^https:\/\/www\.tvmaze\.com\/shows\/\d+/.test(show.url))
    return {title:show.name.slice(0,120),year:Number(show.premiered?.slice(0,4))||undefined,url:show.url,license:'TVMaze · CC BY-SA'};
  }catch{ /* Wikidata can still resolve the same exact ID. */ }}
  const result=await json('https://www.wikidata.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=2&format=json&srsearch='+encodeURIComponent('haswbstatement:P345='+imdbId),signal,32000);
  const ids=(result.query?.search||[]).map((v:any)=>v.title).filter((id:unknown)=>typeof id==='string'&&/^Q\d+$/.test(id));
  if(!ids.length)return;
  const labels=await json('https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=en&ids='+ids.join('|'),signal,16000);
  for(const id of ids) {
   const title=labels.entities?.[id]?.labels?.en?.value;if(typeof title!=='string')continue;
   // Fetch only this identifier claim, not every claim of a large film entity.
   const claims=await json('https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity='+id+'&property=P345',signal,32000);
   if(!claims.claims?.P345?.some((v:any)=>v.mainsnak?.datavalue?.value===imdbId))continue;
   return {title:title.slice(0,120),url:'https://www.wikidata.org/wiki/'+id,license:'Wikidata · CC0'};
  }
 }catch{}
}
