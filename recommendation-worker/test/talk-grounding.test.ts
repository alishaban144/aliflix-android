import {env,runInDurableObject} from 'cloudflare:test';
import {describe,it,expect,vi} from 'vitest';
import {TalkContextSchema} from '../src/talkEvidence';
import {liveSetup} from '../src/talkProviders';
import {TalkSession} from '../src/talk';

describe('one canonical Talk selection',()=>{
 it('curates dated Tavily findings rather than filtering stale private proposals down to one choice',async()=>{
  await runInDurableObject(env.TALK_SESSIONS.getByName('dated-recommendation-pool'),async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000,context=TalkContextSchema.parse({companionVersion:6});
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context,expires,calls:0,searches:0,grants:[]}),expires);
   const binding=(instance as any).env,original=binding.ASK_RESEARCH;
   const recommend=vi.fn(async(_session:string,_request:{candidateHints?:unknown;filters:{minimumYear?:number}})=>({results:[{tmdbId:1,mediaType:'movie',title:'Recent choice',releaseDate:'2025-01-01',posterPath:'/poster.jpg',genres:['Science Fiction'],matchReasons:['A recent thoughtful choice'],retrievalSources:['https://example.org/research']}]}));
   binding.ASK_RESEARCH={getByName:()=>({recommend})};
   try {
    const result=await (instance as unknown as TalkSession).research(session,crypto.randomUUID(),'recommendations',{query:'Recent sci-fi',mediaType:'movie',count:3,candidates:[{title:'Arrival',year:2016}]},'Find two recent science fiction films about identity.');
    expect(recommend.mock.calls[0][1].candidateHints).toBeUndefined();
    expect(recommend.mock.calls[0][1].filters.minimumYear).toBe(new Date().getUTCFullYear()-3);
    expect(result).toMatchObject({requestedCount:2,requiresPresentation:true,results:[{key:'movie:1',tmdbId:1}]});
   } finally {binding.ASK_RESEARCH=original;}
  });
 });
 it('requires a joint identity-based speech and poster commit on capable clients',()=>{
  const current=liveSetup(TalkContextSchema.parse({companionVersion:6,recentProgress:[],stickyLanguage:true}));
  const tools=current.tools[0].functionDeclarations;
  const recommendation=tools.find(t=>t.name==='recommendations')!;
  expect(recommendation.parameters.required).toEqual(['query','mediaType','candidates']);
  expect(tools.find(t=>t.name==='present_recommendations')!.parameters.required).toEqual(['picks']);
  expect(tools.find(t=>t.name==='film_knowledge')!.parameters.properties).toHaveProperty('tmdbId');
  expect(tools.find(t=>t.name==='show_title_cards')!.parameters.properties).toHaveProperty('restore');
  expect(current.systemInstruction.parts[0].text).toContain('Annihilation (2018)');
  expect(current.systemInstruction.parts[0].text).toContain('Read EXACTLY speechText');
  expect(current.systemInstruction.parts[0].text).toContain('NEVER say that a card is now shown');
  expect(TalkContextSchema.safeParse({companionVersion:6,recentSuggestions:['The Assassination of Jesse James by the Coward Robert Ford']}).success).toBe(true);
  const legacy=liveSetup(TalkContextSchema.parse({companionVersion:5,recentProgress:[]}));
  expect(legacy.tools[0].functionDeclarations.find(t=>t.name==='recommendations')!.parameters.properties).toHaveProperty('candidates');
  expect(legacy.tools[0].functionDeclarations.map(t=>t.name)).not.toContain('present_recommendations');
 });
 it('grounds a same-name follow-up in the recommended edition even if the model guesses a different year',async()=>{
  await runInDurableObject(env.TALK_SESSIONS.getByName('canonical-annihilation-followup'),async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000,context=TalkContextSchema.parse({companionVersion:6});
   const selection=[{tmdbId:300668,mediaType:'movie',title:'Annihilation',year:'2018',posterPath:'/poster.jpg'}];
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context,expires,calls:0,searches:0,grants:[],selection}),expires);
   const key=JSON.stringify([300668,'annihilation',2018,'movie',undefined,undefined]);
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','evidence:v4:'+JSON.stringify([key,false,context.imdbId,context.viewedRanges,context.completed]),JSON.stringify([{id:'verified',title:'Annihilation (2018)',text:'A science fiction film directed by Alex Garland.',url:'https://example.org/source',license:'Source'}]),expires);
   const result=await (instance as unknown as TalkSession).knowledge(session,{title:'Annihilation',kind:'movie',year:2011,query:'director'},'Tell me more about Annihilation without spoilers');
   expect(result.cached).toBe(true);expect(result.titles?.[0]).toMatchObject({tmdbId:300668,year:'2018'});
  });
 });
});
