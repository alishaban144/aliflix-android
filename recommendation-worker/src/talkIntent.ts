import type { RecommendationResult } from './types';
import { discoveryConstraints } from './discoveryConstraints';
import { RecommendationFiltersSchema } from './schemas';

/** Only complete, mechanically checkable catalogue refinements may reuse a
 * researched collection. A new premise, anchor or request for additional
 * choices still needs research; retained results never pretend to be exhaustive. */
export function refineTalkCollection(results:RecommendationResult[],question:string):RecommendationResult[]|null {
 const q=question.toLowerCase().replace(/\.\s*(?:keep|preserve|retain)\s+(?:all\s+)?(?:the\s+)?(?:other|remaining|same)\s+constraints(?:\s+(?:from|of)\s+(?:this|the|that|previous)\s+search)?[.!?\s]*$/,'').trim().replace(/[.!?]+$/,'');
 const prefix='(?:(?:only|just|show(?: me)?|give(?: me)?)\\s+)?';
 const media='(?:(?:films?|movies?|titles?|series|shows?)\\s+)?';
 const date='(?:(?:from|since|after|before|until|through)\\s+(?:18|19|20)\\d{2}(?:\\s+(?:onwards?|and later|and newer))?|(?:more\\s+)?(?:recent|newer|latest))';
 const runtime='(?:under|less than|at most)\\s+\\d{1,3}\\s+(?:minutes?|mins?)';
 const status='(?:ended|finished|completed|running|ongoing|returning)\\s+(?:series|shows?)';
 if(!new RegExp('^'+prefix+'(?:'+media+'(?:'+date+'|'+runtime+')|'+status+')$','i').test(q))return null;
 const filters=discoveryConstraints(RecommendationFiltersSchema.parse({}),q);
 const seriesStatus=talkSeriesStatus(q);
 const kept=results.filter(value=>{
  const year=Number(value.releaseDate?.slice(0,4));
  if((filters.minimumYear||filters.maximumYear)&&!Number.isInteger(year))return false;
  if(filters.minimumYear&&year<filters.minimumYear||filters.maximumYear&&year>filters.maximumYear)return false;
  if(filters.maximumRuntimeMinutes&&(value.runtimeMinutes==null||value.runtimeMinutes>filters.maximumRuntimeMinutes))return false;
  if(seriesStatus&&(value.mediaType!=='tv'||value.status?.toLowerCase()!==(seriesStatus==='ended'?'ended':'returning series')))return false;
  return true;
 });
 return kept.length?kept:null;
}

export function canRetainTalkResearch(failure:{status:number;code:string}|undefined):boolean {
 return !!failure&&([429,500,502,503,504].includes(failure.status)||(failure.status===422&&failure.code==='NO_RESEARCH'));
}
export function talkRecommendationCount(question:string,proposed?:number):number {
 const number=/\b(one|two|three|[123])\s+(?:[\p{L}-]+\s+){0,4}(?:titles?|movies?|films?|series|shows?|choices?|recommendations?)\b/iu.exec(question)?.[1]?.toLowerCase();
 return number?(({one:1,two:2,three:3} as Record<string,number>)[number]||Number(number)):proposed||3;
}
/** Chat is a collection surface; voice retains a small spoken selection. */
export function talkChatRecommendationCount(question:string):number {
 const found=/\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|[1-9]\d?)\+?\s+(?:[\p{L}-]+\s+){0,4}(?:titles?|movies?|films?|series|shows?|choices?|recommendations?)\b/iu.exec(question)?.[1]?.toLowerCase();
 const words=['zero','one','two','three','four','five','six','seven','eight','nine','ten','eleven','twelve'];
 const count=found?(words.indexOf(found)>0?words.indexOf(found):found==='fifteen'?15:found==='twenty'?20:Number(found)):12;
 return Math.max(1,Math.min(24,count));
}
export function talkSeriesStatus(question:string):'ended'|'returning'|undefined {
 const segments=question.split('User refinement:');if(segments.length>1){for(const segment of segments.reverse()){const value=talkSeriesStatus(segment);if(value)return value}return undefined}
 if(/\b(?:not|no|exclude|without)\s+(?:any\s+)?(?:ended|finished|completed)\s+(?:series|shows?)\b/i.test(question))return 'returning';
 if(/\b(?:ended|finished|completed)\s+(?:series|shows?)\b|\b(?:series|shows?)\s+(?:that\s+)?(?:have\s+)?ended\b/i.test(question))return 'ended';
 if(/\b(?:running|ongoing|returning)\s+(?:series|shows?)\b/i.test(question))return 'returning';
 return undefined;
}
export function talkRecommendationQuery(question:string|undefined,toolQuery:string,previousQuery?:string):string {
 const current=question?.trim();
 if(!current)return toolQuery;
 // The captured prior request owns inherited constraints. A model expansion
 // must not add an unrequested recent-only era to a relative mood refinement.
 return /\b(?:those|these|them|same ones|more like|more of|that kind|similar ones|less|still|lighter|darker|only|more recent|newer|ended series|running series|same constraints)\b/i.test(current)
  ? `${previousQuery||toolQuery}\nUser refinement: ${current}`.slice(-2000) : current;
}

/** Resolve era preference at the request boundary, rather than trusting model memory. */
export function talkRecommendationPolicy(query: string, now = new Date()) {
  const year = now.getUTCFullYear();
  const eraRequested = /\b(?:classic|classics|older|old|retro|vintage|any (?:year|era)|all time|of all time|before|between|from (?:the )?(?:19|20)\d{2}|(?:19|20)\d0s|(?:19|20)\d{2}\s*(?:to|through|[-–])\s*(?:19|20)\d{2})\b|كلاسيك|قديم|جميع السنوات|älter|klassiker|vor \d{4}/i.test(query) || /^(?:19|20)\d{2}\b/.test(query.trim());
  const recentRequested=/\b(?:recent|latest|new|current|this year|last (?:three|3) years)\b|حديث|جديد|aktuell|neueste/i.test(query);
  return eraRequested||!recentRequested ? { query, filters: {} } : {
    query: `${query}\nRecommend released ${year - 3}–${year} titles first, as of ${now.toISOString().slice(0,10)}. Use dated current sources. Exclude unreleased announcements. Do not substitute a decades-old classic.`,
    filters: { minimumYear: year - 3, maximumYear: year },
  };
}
/** Quota recovery for simple discovery only. Complex premises, named anchors,
 * exclusions and older eras still require the normal research pipeline. */
export function cachedTalkDiscovery(query:string,collection:{results:RecommendationResult[];publishedAt:number},type:'movie'|'tv',excludedIds:Set<number>,excludedTitles:string[],now=new Date()):RecommendationResult[] {
 const year=now.getUTCFullYear();
 let rest=query.toLowerCase().replace(/[’']/g,' ').replace(/[-–]/g,' ')
  .replace(new RegExp(`\\b${year-3}\\s+(?:(?:to|through|and)\\s+)?${year}\\b`,'g'),' ');
 const genres:string[]=[];
 const patterns:Array<[RegExp,string]>=[[/\b(?:science fiction|sci fi|scifi)\b/g,'Science Fiction'],[/\bhorror\b/g,'Horror'],[/\bthrillers?\b/g,'Thriller'],[/\bcomed(?:y|ies)\b/g,'Comedy'],[/\bdramas?\b/g,'Drama'],[/\baction\b/g,'Action'],[/\bcrime\b/g,'Crime'],[/\bromance\b/g,'Romance'],[/\bdocumentar(?:y|ies)\b/g,'Documentary']];
 for(const [pattern,genre] of patterns)rest=rest.replace(pattern,()=>{genres.push(genre);return ' ';});
 rest=rest.replace(/\b(?:i|would|like|you|can|could|please|instead|suggest|suggestions|recommend|recommendations|find|give|me|us|a|an|the|some|one|two|three|[123]|recent|latest|new|released|releases|movies?|films?|series|shows?|include|their|release|years?|to|watch|for|from|between|with|today|now|tonight|something|best|good|top)\b/g,'').replace(/[\s,.!?;:]/g,'');
 if(rest||new Set(genres).size>1||collection.publishedAt<now.getTime()-7*86400000||collection.publishedAt>now.getTime()+60000)return [];
 const titleKey=(v:string)=>v.toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');
 const excluded=new Set(excludedTitles.map(titleKey)),today=now.toISOString().slice(0,10);
 return collection.results.filter(v=>v.mediaType===type&&!excludedIds.has(v.tmdbId)&&!excluded.has(titleKey(v.title))&&
  v.releaseDate&&v.releaseDate>=`${year-3}-01-01`&&v.releaseDate<=today&&v.finalScore>0&&
  genres.every(g=>v.genres.includes(g))&&v.retrievalSources.length>0&&v.retrievalSources.every(source=>{try{const u=new URL(source);return u.protocol==='https:'&&!u.username&&!u.password;}catch{return false;}}))
  .sort((a,b)=>b.releaseDate!.localeCompare(a.releaseDate!)).slice(0,3);
}
