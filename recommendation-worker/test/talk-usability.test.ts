import { describe,it,expect } from 'vitest';
import { env,runInDurableObject } from 'cloudflare:test';
import { TalkSession, talkRoute } from '../src/talk';
import { issueIdentity } from '../src/watchParty';
import { talkRecommendationPolicy, cachedTalkDiscovery } from '../src/talkIntent';
import type { RecommendationResult } from '../src/types';
import { TalkChatsRequestSchema } from '../src/talkChats';
import { liveInstructions } from '../src/talkProviders';
import { TalkContextSchema } from '../src/talkEvidence';

describe('Talk recent discovery and task continuity',()=>{
 const now=new Date('2026-10-20T12:00:00Z');
 it('bounds generic sci-fi discovery to released recent years',()=>{
  expect(talkRecommendationPolicy('Suggest a sci-fi movie',now).filters).toEqual({minimumYear:2023,maximumYear:2026});
  expect(talkRecommendationPolicy('Something like Arrival (2016)',now).filters.minimumYear).toBe(2023);
  expect(talkRecommendationPolicy('a funny series',new Date('2027-01-02Z')).filters.maximumYear).toBe(2027);
 });
 it('honors an expressly requested older era',()=>{
  for(const query of ['classic sci fi','movies from 2006','1990s thrillers','any era','افلام قديمة','ältere Filme'])
   expect(talkRecommendationPolicy(query,now).filters).toEqual({});
 });
 it('never authorizes immediate unresearched recommendations or a history-driven subject change',()=>{
  const text=liveInstructions(TalkContextSchema.parse({companionVersion:3,recentProgress:[]}));
  expect(text).toContain('Answer the newest user request');
  expect(text).toContain('never permission to recap the last watched episode');
  expect(text).not.toContain('Reliable general recommendations and non-plot facts can be answered immediately');
  expect(text).toContain('wait for its researched results');
 });
 it('reuses only recent evaluated research for simple discovery during quotas',()=>{
  const pick=(id:number,date:string,genres=['Science Fiction'])=>({tmdbId:id,mediaType:'movie',title:'Film '+id,releaseDate:date,genres,finalScore:80,retrievalSources:['https://example.org/research']} as RecommendationResult);
  const collection={publishedAt:now.getTime()-86400000,results:[pick(1,'2006-01-01'),pick(2,'2025-05-01'),pick(3,'2026-06-01'),pick(4,'2026-12-01'),pick(5,'2026-05-01',['Horror'])]};
  const lookup=(q:string,ids=new Set<number>(),titles:string[]=[])=>cachedTalkDiscovery(q,collection,'movie',ids,titles,now);
  expect(lookup('Instead, suggest two recent sci-fi movies. Include their release years.').map(v=>v.tmdbId)).toEqual([3,2]);
  expect(lookup('a sci-fi movie',new Set([3]),['Film 2'])).toEqual([]);
  for(const q of ['classic sci-fi from 2006','sci-fi like Arrival','no horror movies','mind-bending sci-fi','sci-fi under two hours','horror or comedy'])expect(lookup(q)).toEqual([]);
  expect(cachedTalkDiscovery('sci-fi movies',{...collection,publishedAt:now.getTime()-8*86400000},'movie',new Set(),[],now)).toEqual([]);
  expect(cachedTalkDiscovery('sci-fi movies',{...collection,results:[{...pick(8,'2026-01-01'),finalScore:0} ]},'movie',new Set(),[],now)).toEqual([]);
 });
});
describe('bounded account chat archive',()=>{
 const chat={id:crypto.randomUUID(),title:'Recent science fiction',updatedAt:Date.now(),lines:[{role:'user' as const,text:'Suggest a sci-fi movie',sources:[]}]};
 it('shares an archive across device tokens without consuming live tool capacity or leaking another account',async()=>{
  const keys:string[]=[];
  const config={...env,CURSOR_SIGNING_SECRET:'archive-test-only',RECOMMENDATION_RATE_LIMITER:{limit:async({key}:{key:string})=>{keys.push(key);return {success:!key.startsWith('talk-live:')};}}};
  const token=async(hash:string)=>issueIdentity({sub:'account:'+hash+':'+crypto.randomUUID(),kind:'account',exp:Date.now()+60000},config.CURSOR_SIGNING_SECRET);
  const a=await token('a'.repeat(43)),same=await token('a'.repeat(43)),other=await token('b'.repeat(43));
  async function request(auth:string,body:unknown){const response=await talkRoute(new Request('https://worker.test/v1/talk/chats',{method:'POST',headers:{Authorization:'Bearer '+auth,'content-type':'application/json'},body:JSON.stringify(body)}),config);expect(response?.status).toBe(200);return response!.json<any>();}
  await request(a,{action:'save',chat});
  expect(await request(same,{action:'read',id:chat.id})).toEqual({chat});
  expect(await request(other,{action:'read',id:chat.id})).toEqual({chat:null});
  await request(same,{action:'delete',id:chat.id});
  expect(await request(a,{action:'read',id:chat.id})).toEqual({chat:null});
  expect(keys.every(key=>key.startsWith('talk-chats:account:'))).toBe(true);
 });
 it('lists summaries, restores sources, and prevents queued saves resurrecting deleted chats',async()=>{
  await runInDurableObject(env.TALK_SESSIONS.getByName('chat-archive-unit'),async instance=>{
   const store=instance as unknown as TalkSession;
   await store.chats({action:'save',chat});
   expect(await store.chats({action:'list'})).toEqual({chats:[{id:chat.id,title:chat.title,updatedAt:chat.updatedAt}]});
   expect(await store.chats({action:'read',id:chat.id})).toEqual({chat});
   await store.chats({action:'delete',id:chat.id});
   expect(await store.chats({action:'save',chat})).toEqual({deleted:true});
   expect(await store.chats({action:'read',id:chat.id})).toEqual({chat:null});
  });
 });
 it('isolates owners and retains only 30 conversations',async()=>{
  const a=env.TALK_SESSIONS.getByName('archive-owner-a'),b=env.TALK_SESSIONS.getByName('archive-owner-b');
  await runInDurableObject(a,async instance=>{
   const store=instance as unknown as TalkSession;
   for(let i=0;i<32;i++)await store.chats({action:'save',chat:{...chat,id:crypto.randomUUID(),updatedAt:Date.now()-i}});
   expect((await store.chats({action:'list'}) as {chats:unknown[]}).chats).toHaveLength(30);
   await store.chats({action:'clear'});expect(await store.chats({action:'list'})).toEqual({chats:[]});
  });
  await runInDurableObject(b,async instance=>expect(await(instance as unknown as TalkSession).chats({action:'read',id:chat.id})).toEqual({chat:null}));
 });
 it('rejects oversized or unsafe archive payloads',()=>{
  expect(TalkChatsRequestSchema.safeParse({action:'save',chat:{...chat,lines:Array(13).fill(chat.lines[0])}}).success).toBe(false);
  expect(TalkChatsRequestSchema.safeParse({action:'read',id:'../other-user'}).success).toBe(false);
  expect(TalkChatsRequestSchema.safeParse({action:'save',chat:{...chat,lines:[{...chat.lines[0],sources:[{title:'Unsafe',url:'javascript:alert(1)',license:''}]}]}}).success).toBe(false);
 });
});
