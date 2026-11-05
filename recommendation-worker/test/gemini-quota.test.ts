import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,expect,it,vi} from 'vitest';
import {reserveGeminiBudget,nextPacificMidnight,geminiCooldown,estimateGeminiInput,type GeminiBudgetState} from '../src/geminiQuota';
import {StoryRequestSchema,understandPlayerStory,type StorySources} from '../src/talkStory';
import {budgetStoryEvidence} from '../src/storyBudget';
import {TalkSession} from '../src/talk';
import {TalkContextSchema} from '../src/talkEvidence';
import type {RecommendationEnv} from '../src/types';
import {issueLiveToken} from '../src/talkProviders';
afterEach(()=>vi.restoreAllMocks());
const q=()=>StoryRequestSchema.parse({id:10,kind:'movie',title:'Owned story',sourceKey:'cut-a:revision-2',anchorSeconds:1200,
 question:'Why does Jane keep this note?',dialogue:[[20,22,'Jane Avery keeps a written record.'],[80,82,'The note records the promise.'],[1195,1198,'Keep that note.']],
 transcriptComplete:true,frames:[{seconds:1198,data:'/9j/AA=='}]});
const sources:StorySources={id:10,kind:'movie',title:'Owned story',creditsUrl:'https://example.org/cast',
 characters:[{name:'Jane Avery',performer:'Owned performer'},{name:'Future Identity',performer:'Owned performer'}],
 evidence:[{id:'owned-source',title:'Owned source',text:'Jane keeps a written record. A quarantined future secret is not established.',url:'https://example.org/story',license:'Owned fixture'}]};
const answer=()=>({characters:[{name:'Jane Avery',sceneRole:'The person keeping the note',cueIds:[0,1,2],frameIds:[0],sourceIds:['credits','owned-source'],basis:'story_and_scene',firstEstablishedSeconds:1198}],
 facts:[{text:'The note records an earlier promise.',kind:'established',cueIds:[1,2],frameIds:[0],sourceIds:['owned-source'],revealSeconds:1198}],explanation:'Jane keeps the note to retain the earlier promise.',unknowns:[]});

it('requires question-directed scene understanding even when player context arrives after voice connection',async()=>{
 const namespace={getByName:()=>({acquireGeminiBudget:async()=>({allowed:true,lease:'owned'}),releaseGeminiBudget:async()=>{}})};
 const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({name:'auth_tokens/owned-ephemeral'}));
 await issueLiveToken({GEMINI_API_KEY:'owned-key-'+crypto.randomUUID(),GEMINI_FREE_TIER_MODE:'true',TALK_SESSIONS:namespace} as unknown as RecommendationEnv,
  TalkContextSchema.parse({companionVersion:16}),Date.now()+120000);
 const body=JSON.parse(String(fetcher.mock.calls[0][1]?.body));
 expect(body.bidiGenerateContentSetup.systemInstruction.parts[0].text).toContain('public-source readiness is NOT compiled');
 expect(body.bidiGenerateContentSetup.systemInstruction.parts[0].text).toContain('MUST call app_control(scene_story) once');
});

it('limits concurrent work and rolling request/token use without dropping the first valid large request',()=>{
 const state:GeminiBudgetState={recent:[]};const now=100000;
 expect(reserveGeminiBudget(state,now,20000,3,32000,'one').allowed).toBe(true);
 expect(reserveGeminiBudget(state,now+1,1000,3,32000,'two').allowed).toBe(false);
 delete state.active;
 expect(reserveGeminiBudget(state,now+2,20000,3,32000,'two')).toMatchObject({allowed:false,retryAt:now+60000});
 expect(reserveGeminiBudget(state,now+60001,50000,3,32000,'three').allowed).toBe(true);
});
it('shares admission across Worker callers while keeping independent projects separate',async()=>{
 const stub=env.TALK_SESSIONS.getByName('free-budget-'+crypto.randomUUID()),a='a'.repeat(64),b='b'.repeat(64);
 const grants=await Promise.all([stub.acquireGeminiBudget(a,'gemini-3.6-flash',5000,3,32000),stub.acquireGeminiBudget(a,'gemini-3.6-flash',5000,3,32000)]);
 expect(grants.filter(g=>g.allowed)).toHaveLength(1);
 expect((await stub.acquireGeminiBudget(b,'gemini-3.6-flash',5000,3,32000)).allowed).toBe(true);
 await stub.releaseGeminiBudget(a,'gemini-3.6-flash',grants.find(g=>g.allowed)!.lease!);
 expect((await stub.acquireGeminiBudget(a,'gemini-3.6-flash',5000,3,32000)).allowed).toBe(true);
});
it('uses the real Pacific daily reset across winter, summer and the autumn clock change',()=>{
 for(const [now,expected] of [['2026-11-05T12:00:00Z','2026-11-06T08:00:00Z'],['2026-07-01T12:00:00Z','2026-07-02T07:00:00Z'],['2026-11-01T00:00:00Z','2026-11-01T07:00:00Z']])
  expect(nextPacificMidnight(Date.parse(now))).toBe(Date.parse(expected));
});
it('distinguishes daily exhaustion from a short RetryInfo delay',async()=>{
 const now=Date.parse('2026-11-05T12:00:00Z');
 const daily=await geminiCooldown(Response.json({error:{details:[{violations:[{quotaId:'GenerateRequestsPerDayPerProjectPerModel-FreeTier'}]},{retryDelay:'2s'}]}},{status:429}),now);
 expect(daily.until).toBe(Date.parse('2026-11-06T08:00:00Z'));
 expect((await geminiCooldown(Response.json({error:{details:[{retryDelay:'53s'}]}},{status:429}),now)).until).toBe(now+53000);
});
it('counts image tokens without treating base64 bytes as transcript tokens',()=>{
 expect(estimateGeminiInput(JSON.stringify({contents:[{parts:[{text:'A'.repeat(100)},{inlineData:{mimeType:'image/jpeg',data:'A'.repeat(100000)}}]}]}))).toBe(1074);
});
it('cools an overloaded project instead of repeatedly spending free requests on server errors',async()=>{
 const now=Date.parse('2026-11-05T12:00:00Z');
 expect((await geminiCooldown(new Response('overloaded',{status:503}),now)).until).toBe(now+30000);
 expect((await geminiCooldown(new Response('overloaded',{status:503,headers:{'retry-after':'45'}}),now)).until).toBe(now+45000);
});
it('spends no inference on opening and never sends a future plot paragraph in the deferred packet',async()=>{
 const fetcher=vi.spyOn(globalThis,'fetch');
 const result=await understandPlayerStory({...q(),question:''},sources,{GEMINI_FREE_TIER_MODE:'true'} as RecommendationEnv);
 await understandPlayerStory({...q(),question:'Hi!'},sources,{GEMINI_FREE_TIER_MODE:'true'} as RecommendationEnv);
 expect(result).toMatchObject({status:'research_ready',deferred:true});expect(fetcher).not.toHaveBeenCalled();
 expect(JSON.stringify(result)).not.toContain('quarantined');expect(JSON.stringify(result)).not.toContain('Future Identity');
});
it('retains relevant early setup and the current exchange inside a bounded chronological compiler input',()=>{
 const request=q();request.dialogue=Array.from({length:1000},(_,i)=>[i,i+.5,('Unrelated dialogue '+i+' ').repeat(4)]);
 request.dialogue[50]=[50,50.5,'Jane Avery said the note records the promise.'];request.dialogue[999]=[999,999.5,'Keep that note.'];
 const result=budgetStoryEvidence(request,sources);
 expect(result.request.dialogue.map(row=>row[2]).join(' ')).toContain('Jane Avery said the note');
 expect(result.request.dialogue.at(-1)?.[2]).toBe('Keep that note.');
 expect(result.request.dialogue.reduce((sum,row)=>sum+row[2].length,0)).toBeLessThanOrEqual(16000);
 expect(result.request.transcriptComplete).toBe(false);
});
it('reuses compiled same-cut memory across follow-ups without replaying an old answer or crossing a source revision',async()=>{
 await runInDurableObject(env.TALK_SESSIONS.getByName('free-story-cache-'+crypto.randomUUID()),async(_,state)=>{
  const namespace={getByName:()=>({storySources:async()=>sources,acquireGeminiBudget:async()=>({allowed:true,lease:'owned'}),releaseGeminiBudget:async()=>{},blockGeminiBudget:async()=>{}})};
  const service=new TalkSession(state,{...env,GEMINI_API_KEY:'owned-key-'+crypto.randomUUID(),GEMINI_FREE_TIER_MODE:'true',TALK_SESSIONS:namespace} as unknown as RecommendationEnv);
  const session=crypto.randomUUID(),expires=Date.now()+60000;
  state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({companionVersion:16,id:10,type:'movie'}),expires,calls:0,searches:0,grants:[]}),expires);
  const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({candidates:[{content:{parts:[{text:JSON.stringify(answer())}]}}]}));
  expect((await service.story(session,q())).status).toBe('grounded');
  const follow=await service.story(session,{...q(),question:'Who is keeping it?'});
  expect(follow).toMatchObject({status:'grounded',reusedMemory:true,explanation:''});expect(fetcher).toHaveBeenCalledTimes(1);
  await service.story(session,{...q(),sourceKey:'cut-b:revision-3'});expect(fetcher).toHaveBeenCalledTimes(2);
 });
});
