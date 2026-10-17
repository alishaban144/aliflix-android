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
  expect(s.tools[0].functionDeclarations[0].behavior).toBe('NON_BLOCKING');expect(s.contextWindowCompression.slidingWindow.targetTokens).toBe('4096');
 });
 it('uses semantic visual themes and on-demand user progress without extra speech providers',()=>{
  const s=liveSetup(TalkContextSchema.parse({recentProgress:[{imdbId:'tt5753856',type:'tv',season:2,episode:1,position:120,duration:3000,completed:false}]}));
  expect(s.tools[0].functionDeclarations.map(t=>t.name)).toEqual(['conversation_topic','viewing_context','film_knowledge']);
  expect(s.systemInstruction.parts[0].text).toContain('120');
  expect(s.systemInstruction.parts[0].text).toContain('never assume a started episode was finished');
  expect(TalkContextSchema.safeParse({recentProgress:Array(9).fill({imdbId:'tt1',type:'tv',season:1,episode:1,position:0,duration:100,completed:false})}).success).toBe(false);
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
