/** Read-only publication gate. Never log temporary authentication material. */
import assert from 'node:assert/strict';
const base=(process.env.ALIFLIX_WORKER_URL||'https://aliflix-recommendations.equable-equipment.workers.dev').replace(/\/$/,'');
async function json(path,options={}){
 const response=await fetch(base+path,{...options,signal:AbortSignal.timeout(15000)});
 assert(response.ok,`${path}: HTTP ${response.status}`);return response.json();
}
try{
 const identity=await json('/v1/party/identity',{method:'POST',headers:{'content-type':'application/json'},body:'{}'});
 assert.equal(typeof identity.token,'string');
 const headers={Authorization:'Bearer '+identity.token};
 const collections=await Promise.all(['movie','tv'].map(async type=>{
  const collection=await json('/v4/ask/daily?type='+type,{headers});
  const items=collection.results||[];console.log(JSON.stringify({type,count:items.length,publishedAt:collection.publishedAt}));
  assert(items.length>=20,`Daily ${type}: ${items.length} verified titles; 20 required before publication`);
  assert.equal(new Set(items.map(v=>v.tmdbId)).size,items.length,'Duplicate daily identities');
  assert(items.every(v=>Number.isInteger(v.tmdbId)&&v.tmdbId>0&&v.mediaType===type&&typeof v.title==='string'&&v.title.trim()&&v.retrievalSources?.length),'Missing verified daily metadata/evidence');
  assert(collection.publishedAt>Date.now()-3*86400000,'Daily publication is stale');
  return collection;
 }));
 assert.equal(collections[0].publishedAt,collections[1].publishedAt,'Movie/series publication must be atomic');
 console.log('Ask Aliflix publication readiness passed');
}catch(error){console.error(error.message);process.exitCode=1;}
