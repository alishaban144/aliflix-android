import { boundedJson } from './talkProviders';
import type { WebFinding } from './askResearch';

export type ResearchIdentity={title:string;year:number;imdbId?:string};
/** Batch, independently identity-check and attribute CC BY-SA Wikipedia facts.
 * TMDB contributes external IDs only; no catalogue prose enters model context. */
export async function candidateEvidence(items:ResearchIdentity[],signal:AbortSignal):Promise<Map<string,WebFinding>> {
 const result=new Map<string,WebFinding>();const wanted=items.filter(v=>/^tt\d+$/.test(v.imdbId||''));if(!wanted.length)return result;
 const titles=[...new Set(wanted.flatMap(v=>[`${v.title} (${v.year} film)`,`${v.title} (film)`,`${v.title} (TV series)`,v.title]))];
 const pages:Array<{title:string;extract?:string;pageprops?:{wikibase_item?:string};pageid:number}>=[];
 async function json(url:URL,limit=400000){const response=await fetch(url,{signal:AbortSignal.any([signal,AbortSignal.timeout(4500)]),headers:{'user-agent':'AliflixResearch/1.0 (+https://github.com/alishaban144/aliflix-android)'},cf:{cacheTtl:86400}});if(!response.ok)console.warn('ask_wikimedia_status',url.hostname,response.status);return boundedJson(response,limit) as Promise<any>;}
 // At most four batched requests instead of one search per title.
 const batches:Array<string[]>=[];for(let i=0;i<titles.length;i+=32)batches.push(titles.slice(i,i+32));
 const responses=await Promise.allSettled(batches.map(async batch=>{const url=new URL('https://en.wikipedia.org/w/api.php');url.search=new URLSearchParams({action:'query',format:'json',redirects:'1',prop:'extracts|pageprops',explaintext:'1',exchars:'1200',exintro:'1',exlimit:'max',exsectionformat:'plain',titles:batch.join('|')}).toString();return json(url);}));
 responses.forEach(r=>{if(r.status==='fulfilled')pages.push(...Object.values(r.value.query?.pages||{}) as typeof pages);});
 const ids=[...new Set(pages.map(p=>p.pageprops?.wikibase_item).filter((v):v is string=>!!v&&/^Q\d+$/.test(v)))];
 if(!ids.length){console.warn('ask_wikimedia_empty',wanted.length,pages.length);return result;}
 const entities:Record<string,any>={};for(let i=0;i<ids.length;i+=25){try{const url=new URL('https://www.wikidata.org/w/api.php');url.search=new URLSearchParams({action:'wbgetentities',format:'json',props:'claims',ids:ids.slice(i,i+25).join('|')}).toString();Object.assign(entities,(await json(url,2000000)).entities);}catch(error){console.warn('ask_wikidata_unavailable',error instanceof Error?error.message:'error');}}
 const byImdb=new Map(wanted.map(v=>[v.imdbId!,v]));
 for(const page of pages){const entity=entities[page.pageprops?.wikibase_item||''];const imdb=(entity?.claims?.P345||[]).map((c:any)=>c.mainsnak?.datavalue?.value).find((v:unknown)=>typeof v==='string'&&byImdb.has(v));if(!imdb||!page.extract||page.pageid<=0)continue;
  const item=byImdb.get(imdb)!;result.set(item.title,{title:page.title,url:'https://en.wikipedia.org/?curid='+page.pageid,text:page.extract.slice(0,1900),attribution:'Wikipedia contributors · CC BY-SA 4.0 · adapted. Identity verified through Wikidata IMDb ID.'});
 }
 return result;
}
