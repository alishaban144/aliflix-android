import {env,runInDurableObject} from 'cloudflare:test';
import {describe,it,expect,vi,afterEach} from 'vitest';
import {TalkContextSchema,explicitlyRequestsSpoilers,permittedEpisode,reusableUrl,stripMarkup,wikipediaEvidence,requestedEpisodeBoundary,avoidsSpoilers,tavilyEvidence,resolveViewingTitle} from '../src/talkEvidence';
import {talkRoute,TalkSession} from '../src/talk';
import {issueLiveToken,liveSetup,liveInstructions,TALK_MODEL,TALK_VOICE} from '../src/talkProviders';
const config={...env,CURSOR_SIGNING_SECRET:'test-only',GEMINI_API_KEY:'test-only'};
afterEach(()=>vi.restoreAllMocks());
describe('Gemini-only live identity and security',()=>{
 it('pins model, voice, automatic interruption and native compression',()=>{
  const s=liveSetup(TalkContextSchema.parse({}));expect(s.model).toBe('models/gemini-3.8-live');
  expect(s.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName).toBe('Aoede');
  expect(s.realtimeInputConfig.automaticActivityDetection.disabled).toBe(false);
  expect(s.tools[0].functionDeclarations.map(t=>t.name)).toEqual(['film_knowledge']);expect(s.tools[0].functionDeclarations[0].behavior).toBe('NON_BLOCKING');expect(s.contextWindowCompression.slidingWindow.targetTokens).toBe('4096');
 });
 it('uses semantic visual themes and on-demand user progress without extra speech providers',()=>{
  const s=liveSetup(TalkContextSchema.parse({recentProgress:[{imdbId:'tt5753856',type:'tv',season:2,episode:1,position:120,duration:3000,completed:false}]}));
  expect(s.tools[0].functionDeclarations.map(t=>t.name)).toEqual(['conversation_topic','viewing_context','film_knowledge']);
  expect(s.systemInstruction.parts[0].text).toContain('120');
  expect(s.systemInstruction.parts[0].text).toContain('never assume a started episode was finished');
  expect(TalkContextSchema.safeParse({recentProgress:Array(9).fill({imdbId:'tt1',type:'tv',season:1,episode:1,position:0,duration:100,completed:false})}).success).toBe(false);
 });
 it('locks conversation language without enabling unknown controls on older clients',()=>{
  const s=liveSetup(TalkContextSchema.parse({recentProgress:[],stickyLanguage:true,responseLanguage:'en'}));
  expect(s.tools[0].functionDeclarations.map(t=>t.name)).toContain('conversation_language');
  expect(s.systemInstruction.parts[0].text).toContain('already locked response language is en');
  expect(s.systemInstruction.parts[0].text).toContain('foreign titles');
  expect(liveSetup(TalkContextSchema.parse({recentProgress:[]})).tools[0].functionDeclarations.map(t=>t.name)).not.toContain('conversation_language');
 });
 it('resolves a current catalogue external ID without sending catalogue prose and caches the history tool',async()=>{
  let duringLookup=()=>{};
  const spy=vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   if(String(u).includes('external_ids')){duringLookup();return Response.json({imdb_id:'tt5753856'});}
   return Response.json({name:'Dark',url:'https://www.tvmaze.com/shows/17861/dark',externals:{imdb:'tt5753856'}});
  });
  const stub=env.TALK_SESSIONS.getByName('viewing-external-identity');
  await runInDurableObject(stub,async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000;
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({id:70523,type:'tv',position:120,recentProgress:[]}),expires,calls:0,searches:0,grants:[]}),expires);
   duringLookup=()=>{
    const current=JSON.parse(state.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=?','live:'+session).one().value);
    current.calls=5;current.grants=['retained'];state.storage.sql.exec('UPDATE memory SET value=? WHERE key=?',JSON.stringify(current),'live:'+session);
   };
   const result=await(instance as unknown as TalkSession).viewing(session);
   const persisted=JSON.parse(state.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=?','live:'+session).one().value);
   expect(persisted.calls).toBe(5);expect(persisted.grants).toEqual(['retained']);
   expect((result as any).context.imdbId).toBe('tt5753856');expect((result as any).titles[0].title).toBe('Dark');
   expect((result as any).context.position).toBe(120);expect((result as any).current).toMatchObject({title:'Dark',position:120});
   const count=spy.mock.calls.length;await(instance as unknown as TalkSession).viewing(session);expect(spy).toHaveBeenCalledTimes(count);
  });
 });
 it('does not restore erased context when an in-flight lookup completes',async()=>{
  const stub=env.TALK_SESSIONS.getByName('viewing-close-race');
  await runInDurableObject(stub,async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000;
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({imdbId:'tt5753856',type:'tv',recentProgress:[]}),expires,calls:0,searches:0,grants:[]}),expires);
   vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{state.storage.sql.exec('DELETE FROM memory');return Response.json({name:'Dark',url:'https://www.tvmaze.com/shows/17861/dark',externals:{imdb:'tt5753856'}});});
   await expect((instance as unknown as TalkSession).viewing(session)).rejects.toThrow('SESSION_EXPIRED');
   expect(state.storage.sql.exec<{n:number}>('SELECT COUNT(*) n FROM memory').one().n).toBe(0);
  });
 });
 it('resolves a large movie entity with bounded identifier-only claims',async()=>{
  const calls:string[]=[];vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   const url=String(u);calls.push(url);
   if(url.includes('list=search'))return Response.json({query:{search:[{title:'Q186341'}]}});
   if(url.includes('props=labels'))return Response.json({entities:{Q186341:{labels:{en:{value:'The Shining'}}}}});
   return Response.json({claims:{P345:[{mainsnak:{datavalue:{value:'tt0081505'}}}]}});
  });
  expect((await resolveViewingTitle('tt0081505','movie',new AbortController().signal))?.title).toBe('The Shining');
  expect(calls.some(u=>u.includes('property=P345'))).toBe(true);
  expect(calls.some(u=>u.includes('props=labels|claims'))).toBe(false);
 });
 it('still identifies a series when TVMaze is unavailable',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   const url=String(u);if(url.includes('api.tvmaze.com'))return new Response('unavailable',{status:503});
   if(url.includes('list=search'))return Response.json({query:{search:[{title:'Q24702309'}]}});
   if(url.includes('props=labels'))return Response.json({entities:{Q24702309:{labels:{en:{value:'Dark'}}}}});
   return Response.json({claims:{P345:[{mainsnak:{datavalue:{value:'tt5753856'}}}]}});
  });
  expect((await resolveViewingTitle('tt5753856','tv',new AbortController().signal))?.title).toBe('Dark');
 });
 it('resolves independent history titles only after matching exact IMDb identity',async()=>{
  vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({name:'Dark',url:'https://www.tvmaze.com/shows/17861/dark',premiered:'2017-12-01',externals:{imdb:'tt5753856'}}));
  expect((await resolveViewingTitle('tt5753856','tv',new AbortController().signal))?.title).toBe('Dark');
  expect(await resolveViewingTitle('not-an-id','tv',new AbortController().signal)).toBeUndefined();
 });
 it('provisions single-use constrained ephemeral tokens and never returns the key',async()=>{
  let sent:any;vi.spyOn(globalThis,'fetch').mockImplementation(async(_,init)=>{sent=JSON.parse(String(init?.body));return Response.json({name:'auth_tokens/test-ephemeral'});});
  const r=await issueLiveToken(config,TalkContextSchema.parse({}),Date.now()+1200000);
  expect(r.token).toBe('auth_tokens/test-ephemeral');expect(JSON.stringify(r)).not.toContain('test-only');expect(sent.uses).toBe(1);expect(sent.bidiGenerateContentSetup.sessionResumption).toBeUndefined();
  expect(sent.fieldMask).toContain('model');expect(sent.fieldMask).toContain('generationConfig');expect(sent.fieldMask).not.toContain('sessionResumption');
  expect(r.model).toBe(TALK_MODEL);expect(r.voice).toBe(TALK_VOICE);
 });
 it('requires authenticated identity for token issuance and tools',async()=>{
  for(const path of ['session','knowledge','close','viewing'])expect((await talkRoute(new Request('https://test/v1/talk/'+path,{method:'POST',body:'{}'}),config))?.status).toBe(401);
 });
 it('keeps new companion tools opt-in and makes favorite-first taste mandatory',()=>{
  const setup=liveSetup(TalkContextSchema.parse({companionVersion:2,recentProgress:[],library:[{id:694,type:'movie',kind:'favorite'}]}));
  expect(setup.tools[0].functionDeclarations.map(t=>t.name)).toContain('aliflix_app');
  expect(setup.tools[0].functionDeclarations.map(t=>t.name)).toContain('remember_recommendations');
  expect(setup.systemInstruction.parts[0].text).toContain('Favorites FIRST');
  expect(setup.systemInstruction.parts[0].text).toContain('Before giving any movie/series recommendation');
  expect(TalkContextSchema.safeParse({library:[{type:'movie',kind:'favorite'}]}).success).toBe(false);
 });
 it('resolves favorite pages without IMDb IDs, separates page caches and preserves counts',async()=>{
  const spy=vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   const url=String(u);
   if(url.includes('external_ids'))return Response.json({imdb_id:'tt5753856'});
   return Response.json({name:'Dark',url:'https://www.tvmaze.com/shows/17861/dark',externals:{imdb:'tt5753856'}});
  });
  await runInDurableObject(env.TALK_SESSIONS.getByName('paged-favorite'),async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000;
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({companionVersion:2,recentProgress:[]}),expires,calls:0,searches:0,grants:[]}),expires);
   const talk=instance as unknown as TalkSession;
   const items=[{id:70523,type:'tv' as const,kind:'favorite' as const}];
   const page={collection:'favorite' as const,offset:0,total:19};
   const result=await talk.viewing(session,items,page) as any;
   expect(result.titles[0]).toMatchObject({title:'Dark',kind:'favorite',id:70523,imdbId:'tt5753856'});
   expect(result.page).toMatchObject({total:19,nextOffset:1,hasMore:true});
   const count=spy.mock.calls.length;await talk.viewing(session,items,page);expect(spy).toHaveBeenCalledTimes(count);
   await talk.viewing(session,items,{...page,offset:8});
   expect(spy.mock.calls.filter(c=>String(c[0]).includes('external_ids'))).toHaveLength(1);
   await talk.close(session);expect(state.storage.sql.exec<{n:number}>("SELECT COUNT(*) n FROM memory WHERE key LIKE 'viewing:%'").one().n).toBe(0);
  });
 });
 it('returns independently identified current viewing alongside a different favorite page',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   const current=String(u).includes('tt0944947');
   return Response.json({name:current?'Game of Thrones':'Dark',url:current?'https://www.tvmaze.com/shows/82/game-of-thrones':'https://www.tvmaze.com/shows/17861/dark',externals:{imdb:current?'tt0944947':'tt5753856'}});
  });
  await runInDurableObject(env.TALK_SESSIONS.getByName('current-outside-taste-page'),async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000;
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({imdbId:'tt0944947',type:'tv',season:2,episode:4,position:100,recentProgress:[]}),expires,calls:0,searches:0,grants:[]}),expires);
   const result=await(instance as unknown as TalkSession).viewing(session,[{imdbId:'tt5753856',type:'tv',kind:'favorite'}]) as any;
   expect(result.titles[0].title).toBe('Dark');expect(result.current).toMatchObject({title:'Game of Thrones',season:2,episode:4,position:100,completed:false});
  });
 });
 it('does not recreate knowledge cache after owner deletion during source retrieval',async()=>{
  await runInDurableObject(env.TALK_SESSIONS.getByName('knowledge-delete-race'),async(instance,state)=>{
   const session=crypto.randomUUID(),expires=Date.now()+60000;
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({}),expires,calls:0,searches:0,grants:[]}),expires);
   vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{state.storage.sql.exec('DELETE FROM memory');return new Response('',{status:503});});
   await expect((instance as unknown as TalkSession).knowledge(session,{title:'Some Film',kind:'movie',query:'genre'},'What genre is it?')).rejects.toThrow('SESSION_EXPIRED');
   expect(state.storage.sql.exec<{n:number}>('SELECT COUNT(*) n FROM memory').one().n).toBe(0);
  });
 });
 it('bounds daily quotas and removes expired state',async()=>{const stub=env.TALK_SESSIONS.getByName('test-live-quota');await runInDurableObject(stub,async instance=>{const talk=instance as unknown as TalkSession;expect(await talk.reserve(2,2)).toBe(true);expect(await talk.reserve(1,2)).toBe(false);});});
 it('excludes catalogue IDs from model prompts and deletes owner memory',async()=>{
  expect(liveInstructions(TalkContextSchema.parse({id:987654321}))).not.toContain('987654321');
  const stub=env.TALK_SESSIONS.getByName('delete-live-owner');
  await runInDurableObject(stub,async(instance,state)=>{state.storage.sql.exec('INSERT OR REPLACE INTO memory VALUES(?,?,?)','evidence:test','[]',Date.now()+60000);await(instance as unknown as TalkSession).close(crypto.randomUUID(),true);expect(state.storage.sql.exec<{n:number}>('SELECT COUNT(*) n FROM memory').one().n).toBe(0);});
 });
 it('rejects playback URLs, TMDB text and credentials',()=>{for(const k of ['title','overview','subtitles','cookie','streamUrl','apiKey'])expect(TalkContextSchema.safeParse({[k]:'private'}).success).toBe(false);});
 it('does not require spoilers or mandatory evidence for familiar answers',()=>{const p=liveInstructions(TalkContextSchema.parse({}));expect(p).toContain('never ask permission');expect(p).toContain('reliable');expect(p).toContain('NEVER future episodes');expect(p).not.toContain('Groq');});
});
describe('Evidence and spoiler intent',()=>{
 it.each(['Explain the ending of The Shining (1980)','What does the final photograph mean?','Erkläre das Ende von Shining','اشرح نهاية الفيلم'])('authorizes an explicitly requested ending: %s',q=>expect(explicitlyRequestsSpoilers(q)).toBe(true));
 it.each(['No spoilers: is Dark good?','ohne Ende bitte, keine Spoiler','بدون حرق النهاية'])('honours explicit spoiler limits: %s',q=>expect(explicitlyRequestsSpoilers(q)).toBe(false));
 it('keeps exact episode numbers when tools request a different future episode',()=>{expect(requestedEpisodeBoundary('Explain Dark season 1 episode 1')).toEqual({season:1,episode:1});expect(requestedEpisodeBoundary('Dark S2E3')).toEqual({season:2,episode:3});expect(avoidsSpoilers('Is episode 5 good? No spoilers.')).toBe(true);});
 it('does not assume skipped episodes were watched',()=>{const c=TalkContextSchema.parse({season:2,episode:3,viewedRanges:[{season:2,first:1,last:1}]});expect(permittedEpisode(c,2,1)).toBe(true);expect(permittedEpisode(c,2,2)).toBe(false);expect(permittedEpisode(c,2,3)).toBe(false);});
 it('restricts reusable source URLs',()=>{expect(reusableUrl('https://en.wikipedia.org/wiki/The_Shining_(film)')).toBe(true);for(const u of ['https://themoviedb.org/film/1','https://en.wikipedia.org.evil.test/wiki/a','http://en.wikipedia.org/wiki/a','https://user@en.wikipedia.org/wiki/a'])expect(reusableUrl(u)).toBe(false);});
 it('uses attributed reusable search evidence during a live Wikipedia outage',async()=>{
  const calls:string[]=[];vi.spyOn(globalThis,'fetch').mockImplementation(async(u)=>{calls.push(String(u));
   if(String(u)==='https://api.tavily.com/search')return Response.json({results:[{title:'The Shining (film)',url:'https://en.wikipedia.org/wiki/The_Shining_(film)',content:'The Shining is a 1980 film. Jack freezes in the maze; Wendy and Danny escape in the snowcat.'},{title:'Wrong source',url:'https://themoviedb.org/movie/694',content:'Do not use'}]});
   return new Response('outage',{status:503});});
  const result=await tavilyEvidence({...config,TAVILY_API_KEY:'test-only'},{title:'The Shining',year:1980,kind:'movie',query:'ending'},new AbortController().signal);
  expect(result).toHaveLength(1);expect(result[0].license).toContain('CC BY-SA');expect(result[0].text).toContain('snowcat');expect(calls.filter(v=>v==='https://api.tavily.com/search')).toHaveLength(1);
 });
 it('strips executable source markup',()=>expect(stripMarkup('<script>bad()</script><p>Jack &amp; Wendy</p>')).toBe('Jack & Wendy'));
 it('uses revision IDs from revisions and retrieves a frozen plot section',async()=>{
  const urls:string[]=[];vi.spyOn(globalThis,'fetch').mockImplementation(async(u)=>{const v=String(u);urls.push(v);
   if(v.includes('action=query'))return Response.json({query:{pages:{1:{pageid:1,title:'The Shining (film)',revisions:[{revid:42}]}}}});
   if(v.includes('prop=sections'))return Response.json({parse:{sections:[{line:'Plot',index:'2'}]}});
   return Response.json({parse:{text:{'*':'<p>The photograph shows Jack in 1921.</p>'}}});});
  const e=await wikipediaEvidence('The Shining (film)',new AbortController().signal);expect(e[0].url).toContain('oldid=42');expect(e[0].text).toContain('1921');expect(urls.some(u=>u.includes('prop=sections|revid'))).toBe(false);
 });
});
