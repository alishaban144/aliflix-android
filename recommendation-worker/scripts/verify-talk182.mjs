import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const base='https://aliflix-recommendations.equable-equipment.workers.dev';
const identity=await fetch(base+'/v1/party/identity',{method:'POST',headers:{'content-type':'application/json'},body:'{}'}).then(r=>r.json());
assert(identity.token);
const headers={'content-type':'application/json',Authorization:'Bearer '+identity.token};
const report={at:new Date().toISOString(),research:[],missions:[]};
const path=new URL('../../.validation/talk182-live-research.json',import.meta.url);
const save=()=>fs.writeFile(path,JSON.stringify(report,null,2));
async function post(route,body){const response=await fetch(base+route,{method:'POST',headers,body:JSON.stringify(body),signal:AbortSignal.timeout(50000)});const value=await response.json();assert(response.ok,JSON.stringify({status:response.status,error:value.error}));return value;}
const keys=rows=>rows.map(v=>v.mediaType+':'+v.tmdbId);
const taskIds=[];
try{
 const request={requestId:crypto.randomUUID(),mode:'describe',mediaType:'movie',pageSize:24,
  query:'Dry deadpan and absurdist comedy about bureaucracy, paperwork and institutional absurdity. Low-key international discoveries, not superhero or franchise blockbusters. Films released since 2015. Avoid Brazil, Office Space and The Death of Stalin.',
  filters:{minimumYear:2015,includedGenres:['Comedy'],excludedTitles:['Brazil','Office Space','The Death of Stalin']},library:[]};
 const response=await fetch(base+'/v4/ask/recommendations?stream=1',{method:'POST',headers,body:JSON.stringify(request),signal:AbortSignal.timeout(50000)});
 assert(response.ok,`stream ${response.status}`);
 let prefix=[],final;let snapshots=0,buffer='';const decoder=new TextDecoder();
 for await(const bytes of response.body){buffer+=decoder.decode(bytes,{stream:true});let end;
  while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;const event=JSON.parse(line);
   if(event.type==='error')throw Error(event.error.code);
   if(event.type!=='results')continue;
   const current=keys(event.results);assert.deepEqual(current.slice(0,prefix.length),prefix,'Published result prefix changed');prefix=current;snapshots++;
   if(!event.inProgress)final=event;
  }
 }
 assert(final?.results.length>0,'No real verified matches');
 assert(final.results.every(v=>Number(v.releaseDate?.slice(0,4))>=2015));
 const page=final.nextCursor?await post('/v4/ask/recommendations',{...request,cursor:final.nextCursor}):null;
 if(page){assert(keys(page.results).every(key=>!prefix.includes(key)),'Continuation repeated a published title');
  const retry=await post('/v4/ask/recommendations',{...request,cursor:final.nextCursor});assert.deepEqual(retry.results,page.results,'Cursor retry must return an immutable page');}
 report.research.push({query:request.query,snapshots,prefixStable:true,titles:final.results.map(v=>({id:v.tmdbId,title:v.title,year:v.releaseDate?.slice(0,4)})),continuation:page?.results.map(v=>({id:v.tmdbId,title:v.title,year:v.releaseDate?.slice(0,4)}))});await save();
 const refined=await post('/v4/ask/recommendations',{...request,requestId:crypto.randomUUID(),previousQuery:'Absurdist comedies before 2010',query:'Absurdist comedies before 2010, more recent',refinementQuery:'more recent',filters:{minimumYear:1980,maximumYear:2010,includedGenres:['Comedy']}});
 assert(refined.results.length>0,'Refinement returned no matches');assert(refined.results.every(v=>Number(v.releaseDate?.slice(0,4))>=2023),'Recent refinement leaked an old film');
 report.research.push({refinement:'more recent',years:refined.results.map(v=>v.releaseDate?.slice(0,4)),titles:refined.results.map(v=>v.title)});await save();
 const plan={name:'Validation · exact double bill',goal:'Programme Primer and The Vast of Night together using real runtimes. Never substitute other titles.',steps:[{id:'facts',kind:'catalogue',titles:[{id:14337,type:'movie',title:'Primer'},{id:565743,type:'movie',title:'The Vast of Night'}]},{id:'programme',kind:'schedule',dependsOn:['facts'],budgetMinutes:90,count:2}]};
 const created=await post('/v4/ask/agent',{operation:'create',plan});taskIds.push(created.id);
 async function settled(id){for(let n=0;n<18;n++){const value=await post('/v4/ask/agent',{operation:'read',id});if(!['queued','running'].includes(value.status))return value;await new Promise(r=>setTimeout(r,3000));}throw Error('Task did not settle');}
 const impossible=await settled(created.id);assert.equal(impossible.status,'needs_input');
 const changed=await post('/v4/ask/agent',{operation:'update',id:created.id,expectedRevision:impossible.revision,plan:{...plan,steps:[plan.steps[0],{...plan.steps[1],budgetMinutes:180}]}});
 assert(changed.outputs.facts?.results.length===2,'Unaffected catalogue work was discarded');
 const ready=await settled(created.id);assert.equal(ready.status,'ready');assert.equal(ready.outputs.programme.results.length,2);assert(ready.outputs.programme.minutes<=180);
 const stale=await post('/v4/ask/agent',{operation:'pause',id:created.id,expectedRevision:1});assert.equal(stale.status,'changed');
 report.missions.push({id:created.id,infeasible:impossible.status,replanned:ready.status,preservedFacts:true,minutes:ready.outputs.programme.minutes,titles:ready.outputs.programme.results.map(v=>v.title),staleRevisionRejected:true});await save();
 console.log(JSON.stringify({research:report.research.map(v=>({count:v.titles?.length,snapshots:v.snapshots,prefixStable:v.prefixStable})),missions:report.missions},null,2));
}finally{
 for(const id of taskIds){const state=await post('/v4/ask/agent',{operation:'read',id}).catch(()=>null);if(state)await post('/v4/ask/agent',{operation:'cancel',id,expectedRevision:state.revision}).catch(()=>{});}
 await save();
}
