import type { RecommendationResult } from './types';

export function canRetainTalkResearch(failure:{status:number;code:string}|undefined):boolean {
 return !!failure&&([429,500,502,503,504].includes(failure.status)||(failure.status===422&&failure.code==='NO_RESEARCH'));
}
export function talkRecommendationQuery(question:string|undefined,toolQuery:string):string {
 const current=question?.trim();
 if(!current)return toolQuery;
 // A relative follow-up needs the model's conversation-grounded expansion.
 // Self-contained requests always replace that expansion.
 return /\b(?:those|these|them|same ones|more like|more of|that kind|similar ones)\b/i.test(current)
  ? `${toolQuery}\nLatest user follow-up: ${current}` : current;
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
