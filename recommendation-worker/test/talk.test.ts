import {env,runInDurableObject} from 'cloudflare:test';
import {describe,it,expect,vi,afterEach} from 'vitest';
import {TalkContextSchema,explicitlyRequestsSpoilers,permittedEpisode,reusableUrl,stripMarkup,wikipediaEvidence} from '../src/talkEvidence';
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
 it('provisions single-use constrained ephemeral tokens and never returns the key',async()=>{
  let sent:any;vi.spyOn(globalThis,'fetch').mockImplementation(async(_,init)=>{sent=JSON.parse(String(init?.body));return Response.json({name:'auth_tokens/test-ephemeral'});});
  const r=await issueLiveToken(config,TalkContextSchema.parse({}),Date.now()+1200000);
  expect(r.token).toBe('auth_tokens/test-ephemeral');expect(JSON.stringify(r)).not.toContain('test-only');expect(sent.uses).toBe(1);
  expect(sent.fieldMask).toContain('model');expect(sent.fieldMask).toContain('generationConfig');expect(sent.fieldMask).not.toContain('sessionResumption');
  expect(r.model).toBe(TALK_MODEL);expect(r.voice).toBe(TALK_VOICE);
 });
 it('requires authenticated identity for token issuance and tools',async()=>{
  for(const path of ['session','knowledge','close'])expect((await talkRoute(new Request('https://test/v1/talk/'+path,{method:'POST',body:'{}'}),config))?.status).toBe(401);
 });
 it('bounds daily quotas and removes expired state',async()=>{const stub=env.TALK_SESSIONS.getByName('test-live-quota');await runInDurableObject(stub,async instance=>{const talk=instance as unknown as TalkSession;expect(await talk.reserve(2,2)).toBe(true);expect(await talk.reserve(1,2)).toBe(false);});});
 it('rejects playback URLs, TMDB text and credentials',()=>{for(const k of ['title','overview','subtitles','cookie','streamUrl','apiKey'])expect(TalkContextSchema.safeParse({[k]:'private'}).success).toBe(false);});
 it('does not require spoilers or mandatory evidence for familiar answers',()=>{const p=liveInstructions(TalkContextSchema.parse({}));expect(p).toContain('never ask permission');expect(p).toContain('reliable');expect(p).toContain('NEVER future episodes');expect(p).not.toContain('Groq');});
});
describe('Evidence and spoiler intent',()=>{
 it.each(['Explain the ending of The Shining (1980)','What does the final photograph mean?','Erkläre das Ende von Shining','اشرح نهاية الفيلم'])('authorizes an explicitly requested ending: %s',q=>expect(explicitlyRequestsSpoilers(q)).toBe(true));
 it.each(['No spoilers: is Dark good?','ohne Ende bitte, keine Spoiler','بدون حرق النهاية'])('honours explicit spoiler limits: %s',q=>expect(explicitlyRequestsSpoilers(q)).toBe(false));
 it('does not assume skipped episodes were watched',()=>{const c=TalkContextSchema.parse({season:2,episode:3,viewedRanges:[{season:2,first:1,last:1}]});expect(permittedEpisode(c,2,1)).toBe(true);expect(permittedEpisode(c,2,2)).toBe(false);expect(permittedEpisode(c,2,3)).toBe(false);});
 it('restricts reusable source URLs',()=>{expect(reusableUrl('https://en.wikipedia.org/wiki/The_Shining_(film)')).toBe(true);for(const u of ['https://themoviedb.org/film/1','https://en.wikipedia.org.evil.test/wiki/a','http://en.wikipedia.org/wiki/a','https://user@en.wikipedia.org/wiki/a'])expect(reusableUrl(u)).toBe(false);});
 it('strips executable source markup',()=>expect(stripMarkup('<script>bad()</script><p>Jack &amp; Wendy</p>')).toBe('Jack & Wendy'));
 it('uses revision IDs from revisions and retrieves a frozen plot section',async()=>{
  const urls:string[]=[];vi.spyOn(globalThis,'fetch').mockImplementation(async(u)=>{const v=String(u);urls.push(v);
   if(v.includes('action=query'))return Response.json({query:{pages:{1:{pageid:1,title:'The Shining (film)',revisions:[{revid:42}]}}}});
   if(v.includes('prop=sections'))return Response.json({parse:{sections:[{line:'Plot',index:'2'}]}});
   return Response.json({parse:{text:{'*':'<p>The photograph shows Jack in 1921.</p>'}}});});
  const e=await wikipediaEvidence('The Shining (film)',new AbortController().signal);expect(e[0].url).toContain('oldid=42');expect(e[0].text).toContain('1921');expect(urls.some(u=>u.includes('prop=sections|revid'))).toBe(false);
 });
});
