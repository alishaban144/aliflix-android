import {env,runInDurableObject} from 'cloudflare:test';
import {describe,it,expect,vi} from 'vitest';
import {MissionPlan,agentProgramme,agentCalendar,agentFestival,TalkAgent} from '../src/talkAgent';
import {RecommendationFiltersSchema} from '../src/schemas';
import {discoveryConstraints} from '../src/discoveryConstraints';
import type {RecommendationResult} from '../src/types';
const film=(id:number,runtimeMinutes?:number):RecommendationResult=>({tmdbId:id,mediaType:'movie',title:'Film '+id,genres:[],originCountries:[],runtimeMinutes,matchLevel:'Relevant',finalScore:0,matchReasons:[],retrievalSources:[]});

describe('durable app work',()=>{
 it('rejects cyclic and forward dependencies before doing work',()=>{
  expect(MissionPlan.safeParse({name:'Night',goal:'Build a night',steps:[{id:'first',kind:'collection',dependsOn:['later']},{id:'later',kind:'discover'}]}).success).toBe(false);
  expect(MissionPlan.safeParse({name:'Night',goal:'Build a night',steps:[{id:'first',kind:'discover'},{id:'first',kind:'collection'}]}).success).toBe(false);
 });
 it('packs exact runtimes with breaks and never substitutes guessed durations',()=>{
  const rows=[film(1,103),film(2,94),film(3,80),film(4)];
  const plan=agentProgramme(rows,200,2,10);
  expect(plan.results.map(v=>v.tmdbId)).toEqual([1,3]);expect(plan.minutes).toBe(193);
  expect(agentProgramme(rows,100,2).results).toEqual([]);
  expect(plan.unknownRuntime).toEqual([4]);
 });
 it('preserves episode order across dates and reports an impossible catch-up honestly',()=>{
  const episodes=[1,2,3,4].map(episode=>({id:12,type:'tv' as const,season:1,episode,airDate:'2025-01-01',runtimeMinutes:45}));
  const calendar=agentCalendar(episodes,'2026-10-26','2026-10-28',90,10);
  expect(calendar.calendar.map(v=>v.episodes.map(e=>e.episode))).toEqual([[1],[2],[3]]);expect(calendar.unscheduled).toBe(1);
  expect(agentCalendar([{...episodes[0],runtimeMinutes:undefined}],'2026-10-26','2026-10-28',180).unscheduled).toBe(1);
 });
 it('builds a dated festival without repeats and refuses an impossible night',()=>{
  const result=agentFestival([film(1,80),film(2,90),film(3,95),film(4,100)],'2026-10-30','2026-10-31',210,2,10);
  expect(result.festival.map(v=>v.date)).toEqual(['2026-10-30','2026-10-31']);
  expect(new Set(result.results.map(v=>v.tmdbId)).size).toBe(4);
  expect(result.festival.every(v=>v.minutes<=210&&v.results.length===2)).toBe(true);
  expect(agentFestival([film(1,200)],'2026-10-30','2026-10-31',100,1).unscheduled).toBe(2);
  expect(MissionPlan.safeParse({name:'Dates',goal:'Invalid dates must fail',steps:[{id:'days',kind:'calendar',startDate:'2026-02-31',deadline:'2026-03-02',budgetMinutes:90}]}).success).toBe(false);
 });
 it('rejects a date span that would silently drop requested evenings',()=>{
  for(const kind of ['calendar','festival'])expect(MissionPlan.safeParse({name:'Long plan',goal:'Cover every requested day',steps:[{id:'days',kind,startDate:'2026-01-01',deadline:'2026-12-31',budgetMinutes:90}]}).success).toBe(false);
 });
 it('hydrates direct canonical titles before scheduling without dependency stages',async()=>{
  const stub=env.TALK_AGENTS.getByName(crypto.randomUUID());
  await runInDurableObject(stub,async(instance,state)=>{
   const original=(instance as any).step;
   const seen:string[]=[];
   (instance as any).step=async function(m:unknown,step:any,deps:unknown[],signal:AbortSignal){
    seen.push(step.kind);
    if(step.kind==='catalogue')return {results:[film(14337,77),film(565743,91)]};
    return original.call(this,m,step,deps,signal);
   };
   const mission=await (instance as TalkAgent).command({operation:'create',plan:{name:'Direct identities',goal:'Schedule these two titles',steps:[{id:'programme',kind:'schedule',titles:[{id:14337,type:'movie'},{id:565743,type:'movie'}],budgetMinutes:180,breakMinutes:5}]}});
   if(!('id' in mission))throw Error('Missing mission');await state.storage.deleteAlarm();
   await (instance as TalkAgent).alarm();
   const result=await (instance as TalkAgent).command({operation:'read',id:mission.id});
   expect(result).toMatchObject({status:'ready',outputs:{programme:{minutes:173}}});
   expect(seen).toEqual(['schedule','catalogue']);(instance as any).step=original;
  });
 });
 it('persists missions, fences revisions and cancels without silently restarting',async()=>{
  const stub=env.TALK_AGENTS.getByName(crypto.randomUUID());
  const mission=await stub.command({operation:'create',plan:{name:'Three evenings',goal:'Find three thoughtful films',steps:[{id:'find',kind:'discover',query:'thoughtful films'}]}});
  expect('status' in mission&&mission.status).toBe('queued');
  if(!('id' in mission))throw Error('Missing mission');
  expect(await stub.command({operation:'pause',id:mission.id,expectedRevision:99})).toMatchObject({status:'changed'});
  const paused=await stub.command({operation:'pause',id:mission.id,expectedRevision:1});expect('revision' in paused&&paused.revision).toBe(2);
  const cancelled=await stub.command({operation:'cancel',id:mission.id,expectedRevision:2});expect('status' in cancelled&&cancelled.status).toBe('cancelled');
  await runInDurableObject(stub,async instance=>{await (instance as unknown as TalkAgent).alarm();});
  const read=await stub.command({operation:'read',id:mission.id});expect('status' in read&&read.status).toBe('cancelled');
 });
 it('isolates requested memory between account workspaces and forgets exact keys',async()=>{
  const one=env.TALK_AGENTS.getByName(crypto.randomUUID()),two=env.TALK_AGENTS.getByName(crypto.randomUUID());
  await one.command({operation:'remember',memory:{key:'friday',value:'No horror on Fridays'}});
  expect(await one.command({operation:'memories'})).toEqual({memories:[{key:'friday',value:'No horror on Fridays'}]});
  expect(await two.command({operation:'memories'})).toEqual({memories:[]});
  await one.command({operation:'forget',memory:{key:'friday',value:'unused'}});
  expect(await one.command({operation:'memories'})).toEqual({memories:[]});
 });
 it('finds true common ground and reports an empty intersection rather than using one branch',async()=>{
  const stub=env.TALK_AGENTS.getByName(crypto.randomUUID());
  await runInDurableObject(stub,async(instance,state)=>{
   const plan=MissionPlan.parse({name:'Common ground',goal:'Find films both people accept',steps:[{id:'a',kind:'discover'},{id:'b',kind:'discover'},{id:'together',kind:'collection',dependsOn:['a','b'],mergeMode:'all'}]});
   const id=crypto.randomUUID(),now=Date.now();
   state.storage.sql.exec('INSERT INTO missions VALUES (?,?)',id,JSON.stringify({id,revision:1,plan,status:'queued',snapshot:{library:[],completed:[]},outputs:{a:{results:[film(1,90),film(2,100)]},b:{results:[film(2,100),film(3,80)]}},updatedAt:now,change:0}));
   await (instance as unknown as TalkAgent).alarm();
   const result=await (instance as unknown as TalkAgent).command({operation:'read',id});
   expect('outputs' in result&&result.outputs.together.results?.map(v=>v.tmdbId)).toEqual([2]);
  });
 });
 it('does not overwrite a revised goal with a late older stage',async()=>{
  const stub=env.TALK_AGENTS.getByName(crypto.randomUUID());
  await runInDurableObject(stub,async(instance,state)=>{
   const mission=await (instance as unknown as TalkAgent).command({operation:'create',plan:{name:'Original',goal:'Original film goal',steps:[{id:'first',kind:'discover'}]}});
   if(!('id' in mission))throw Error('Missing mission');
   await state.storage.deleteAlarm();
   let resolve!:(value:unknown)=>void;
   const pending=new Promise(r=>resolve=r);
   const original=(instance as any).step;(instance as any).step=()=>pending;
   const running=(instance as unknown as TalkAgent).alarm();
   await vi.waitFor(()=>expect((instance as any).controllers.size).toBe(1));
   await (instance as unknown as TalkAgent).command({operation:'update',id:mission.id,expectedRevision:1,plan:{name:'Revised',goal:'A revised goal',steps:[{id:'second',kind:'discover'}]}});
   resolve({results:[film(1,90)]});await running;(instance as any).step=original;
   const current=await (instance as unknown as TalkAgent).command({operation:'read',id:mission.id});
   expect(current).toMatchObject({revision:2,status:'queued',outputs:{},plan:{name:'Revised'}});
  });
 });
});
describe('verified discovery constraints',()=>{
 for(const refinement of ['more recent','neuere Filme','أفلام حديثة'])it('replaces an old era for '+refinement,()=>{
  const result=discoveryConstraints(RecommendationFiltersSchema.parse({}),`films before 2005\n${refinement}`,2026);
  expect(result.minimumYear).toBe(2023);expect(result.maximumYear).toBe(2026);
 });
 it('keeps combined year, language and runtime restrictions',()=>{
  const result=discoveryConstraints(RecommendationFiltersSchema.parse({originalLanguage:'ko'}),'2019-2024, under 100 minutes',2026);
  expect(result).toMatchObject({minimumYear:2019,maximumYear:2024,maximumRuntimeMinutes:99,originalLanguage:'ko'});
 });
 it('does not treat a numeric film title as a release-year instruction',()=>{
  const result=discoveryConstraints(RecommendationFiltersSchema.parse({}),'like Blade Runner 2049, less bleak',2026);
  expect(result.minimumYear).toBeUndefined();expect(result.maximumYear).toBeUndefined();
 });
 it('handles negated year bounds and hours without dropping the original language',()=>{
  expect(discoveryConstraints(RecommendationFiltersSchema.parse({originalLanguage:'de'}),'not before 2020, at most 1h45',2026)).toMatchObject({minimumYear:2020,maximumRuntimeMinutes:105,originalLanguage:'de'});
  expect(discoveryConstraints(RecommendationFiltersSchema.parse({}),'more recent than 2012, under two hours',2026)).toMatchObject({minimumYear:2013,maximumRuntimeMinutes:119});
 });
});
