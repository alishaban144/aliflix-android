/** Publication gate. Empty collections may request the bounded server bootstrap. */
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
function verifyCollection(collection,type,now){
 const items=collection.results||[];
 assert(items.length>=20,`Daily ${type}: ${items.length} verified titles; 20 required before publication`);
 assert.equal(new Set(items.map(v=>v.tmdbId)).size,items.length,'Duplicate daily identities');
 assert(items.every(v=>Number.isInteger(v.tmdbId)&&v.tmdbId>0&&v.mediaType===type&&typeof v.title==='string'&&v.title.trim()&&Array.isArray(v.retrievalSources)&&v.retrievalSources.length&&v.retrievalSources.every(link=>{try{const u=new URL(link);return u.protocol==='https:'&&!u.username&&!u.password;}catch{return false;}})),'Missing verified daily metadata/evidence');
 if(collection.source==='catalogue')assert(items.every(v=>v.finalScore===0&&Array.isArray(v.matchReasons)&&v.matchReasons.length===0&&v.retrievalSources.every(url=>url===`https://www.themoviedb.org/${type}/${v.tmdbId}`)),'Catalogue fallback must use canonical identities without invented AI assessments');
 assert(collection.publishedAt>now-3*86400000&&collection.publishedAt<=now+60000,'Daily publication is stale or future-dated');
}
export function verifyAskCollections(collections,now=Date.now()){
 assert.equal(collections.length,2);
 collections.forEach((c,i)=>verifyCollection(c,i?'tv':'movie',now));
 assert.equal(collections[0].publishedAt,collections[1].publishedAt,'Movie/series publication must be atomic');
}
export async function awaitAskReadiness({read,maxWaitMs=0,now=Date.now,wait=ms=>new Promise(r=>setTimeout(r,ms)),progress=()=>{}}){
 assert(Number.isFinite(maxWaitMs)&&maxWaitMs>=0&&maxWaitMs<=7200000,'Invalid bootstrap wait bound');
 const deadline=now()+maxWaitMs;let readFailures=0;
 for(;;){
  let collections;
  try{collections=await read();readFailures=0;}catch(error){
   if(now()>=deadline||++readFailures>3)throw error;
   await wait(Math.min(1000*readFailures,deadline-now()));continue;
  }
  const empty=collections.filter(c=>!c.results?.length);
  if(!empty.length){verifyAskCollections(collections,now());return collections;}
  // Malformed non-empty data must fail immediately, never be hidden by waiting.
  collections.forEach((c,i)=>{if(c.results?.length)verifyCollection(c,i?'tv':'movie',now());});
  if(now()>=deadline||!empty.every(c=>['warming','limited'].includes(c.state)))verifyAskCollections(collections,now());
  progress(collections);
  await wait(Math.min(30000,deadline-now()));
 }
}
async function main(){
 const base=(process.env.ALIFLIX_WORKER_URL||'https://aliflix-recommendations.equable-equipment.workers.dev').replace(/\/$/,'');
 async function json(path,options={}){const r=await fetch(base+path,{...options,signal:AbortSignal.timeout(15000)});assert(r.ok,`${path}: HTTP ${r.status}`);return r.json();}
 const identity=await json('/v1/party/identity',{method:'POST',headers:{'content-type':'application/json'},body:'{}'});
 assert.equal(typeof identity.token,'string');const headers={Authorization:'Bearer '+identity.token};let last='',reported=0;
 const collections=await awaitAskReadiness({maxWaitMs:Number(process.env.ALIFLIX_ASK_WAIT_MS||0),read:()=>Promise.all(['movie','tv'].map(type=>json('/v4/ask/daily?type='+type,{headers}))),progress:values=>{
  const state=JSON.stringify(values.map((c,i)=>({type:i?'tv':'movie',count:c.results?.length||0,state:c.state,retryAt:c.retryAt})));
  if(state!==last||Date.now()-reported>=300000){console.log(state);last=state;reported=Date.now();}
 }});
 console.log(JSON.stringify(collections.map((c,i)=>({type:i?'tv':'movie',count:c.results.length,publishedAt:c.publishedAt}))));
 console.log('Ask Aliflix publication readiness passed');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(e=>{console.error(e.message);process.exitCode=1;});
