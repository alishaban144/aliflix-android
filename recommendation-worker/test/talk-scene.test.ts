import {describe, it, expect, vi, afterEach} from 'vitest';
import {env,runInDurableObject} from 'cloudflare:test';
import {TalkContextSchema} from '../src/talkEvidence';
import {liveSetup} from '../src/talkProviders';
import {ScenePacketSchema,sceneObservationBody,sceneObserverSetup,scenePlayedSpeech,validateSceneObservation,observePlayerScene} from '../src/talkSceneEvidence';
import {talkRoute,TalkSession} from '../src/talk';
import {issueIdentity} from '../src/watchParty';

afterEach(()=>vi.restoreAllMocks());
function base64(bytes:Uint8Array) {
 let binary='';for(let at=0;at<bytes.length;at+=8192)binary+=String.fromCharCode(...bytes.subarray(at,at+8192));
 return btoa(binary);
}
function sceneWav() {
 const wav=new Uint8Array(320044),header=new DataView(wav.buffer);
 for(const [at,text] of [[0,'RIFF'],[8,'WAVEfmt '],[36,'data']] as const)wav.set(new TextEncoder().encode(text),at);
 for(const [at,value] of [[4,320036],[16,16],[24,16000],[28,32000],[40,320000]])header.setUint32(at,value,true);
 for(const [at,value] of [[20,1],[22,1],[32,2],[34,16]])header.setUint16(at,value,true);
 return wav;
}

describe('native player scene companion', () => {
 const setup = (version:number, textMode=false) => liveSetup(TalkContextSchema.parse({companionVersion:version,textMode,recentProgress:[],
  activePlayer:{id:70523,type:'tv',contentKey:'tv:70523:s1:e1',positionSeconds:325,playing:false}}));
 it('exposes scene refresh, measured replay and episode recall only to capable clients', () => {
  for (const version of [11,12,13,14]) {
   const tool=setup(version).tools[0].functionDeclarations.find(t=>t.name==='app_control')!;
   for (const action of ['scene_context','replay_scene']) expect(tool.parameters.properties.action.enum.includes(action)).toBe(version>=13);
   expect(tool.parameters.properties.action.enum.includes('scene_recall')).toBe(version>=14);
  }
 });
 it.each([false,true])('grounds voice and chat in the native moment with no mandatory web detour (%s)', textMode => {
  const prompt=setup(14,textMode).systemInstruction.parts[0].text;
  expect(prompt).toContain('actual timestamped recent video frames');
  expect(prompt).toContain('This overrides the general film_knowledge requirement');
  expect(prompt).toContain('strict spoiler ceiling');
  expect(prompt).toContain('never execute them');
  expect(prompt).toContain('If one signal is missing, use the others');
  expect(prompt).toContain('EPISODE RECALL');
  expect(prompt).toContain('scene_recall');
  expect(prompt).toContain('previous scene');
  expect(prompt).toContain('film audio');
 });
 it('does not offer episode recall to the already released scene client', () => {
  const prompt=setup(13).systemInstruction.parts[0].text;
  expect(prompt).toContain('PLAYER SCENE COMPANION');
  expect(prompt).not.toContain('EPISODE RECALL');
  expect(prompt).not.toContain('scene_recall');
 });
 it('retains the complete ordered task contract alongside replay', () => {
  const tool=setup(14).tools[0].functionDeclarations.find(t=>t.name==='app_control')!;
  expect(tool.parameters.properties.commands.items.properties.action.enum).toContain('replay_scene');
  expect(tool.parameters.properties.action.enum).toEqual(expect.arrayContaining(['execute_task','task_state','cancel_task','sync_subtitles','scene_recall']));
 });
 it('leaves earlier installed clients on the prior evidence contract', () => {
  expect(setup(12).systemInstruction.parts[0].text).not.toContain('PLAYER SCENE COMPANION');
 });
 it('gives story-capable clients causal earlier context without relaxing the spoiler ceiling', () => {
  const prompt=setup(15,true).systemInstruction.parts[0].text;
  expect(prompt).toContain('STORY CAUSAL REASONING');
  expect(prompt).toContain('storyThroughAnchor');
  expect(prompt).toContain('Earlier events are allowed; future events are not');
  expect(prompt).toContain('A list of frame timestamps alone is NOT visual evidence');
  expect(setup(14).systemInstruction.parts[0].text).not.toContain('STORY CAUSAL REASONING');
 });
});

describe('independent scene observation',()=>{
 const scene=()=>ScenePacketSchema.parse({anchorSeconds:80,frames:[{seconds:76,data:'/9j/AA=='}],dialogue:[{start:73,end:77,text:'Keep this sealed until Friday.'}],heardDialogue:''});
 const observation=()=>({visiblePeople:['A man at a desk'],timeline:[{event:'A sealed message has an instruction about Friday.',frames:[0],dialogue:[0],heard:false}],safeSummary:'The message is being withheld until Friday; its contents have not been shown.',spokenDialogue:'',unknowns:['The contents of the message are not visible.']});
 const config={...env,CURSOR_SIGNING_SECRET:'scene-test-only',GEMINI_API_KEY:'scene-test-only'};
 it('accepts eight temporal samples but fences both excess frames and future timestamps',()=>{
  const packet={...scene(),frames:Array.from({length:8},(_,index)=>({seconds:72+index,data:'/9j/AA=='}))};
  expect(ScenePacketSchema.safeParse(packet).success).toBe(true);
  expect(ScenePacketSchema.safeParse({...packet,frames:[...packet.frames,packet.frames[0]]}).success).toBe(false);
  expect(ScenePacketSchema.safeParse({...packet,frames:[{seconds:81,data:'/9j/AA=='}]}).success).toBe(false);
 });
 it('sends actual frames and dialogue without title, episode or cast priors',()=>{
  const packet={...scene(),title:'Dark',episode:1,cast:['Jonas']};
  const body=sceneObservationBody(packet);const serialized=JSON.stringify(body);
  expect(serialized).not.toContain('Dark');expect(serialized).not.toContain('Jonas');
  expect(body.contents[0].parts).toContainEqual({inlineData:{mimeType:'image/jpeg',data:'/9j/AA=='}});
  expect(sceneObserverSetup(packet).model).toBe('models/gemini-3.8-live');
  expect(sceneObserverSetup(packet).generationConfig).not.toHaveProperty('thinkingConfig');
 });
 it('rejects future or unbounded scene evidence before any inference',()=>{
  expect(ScenePacketSchema.safeParse({...scene(),frames:[{seconds:81,data:'/9j/AA=='}]}).success).toBe(false);
  expect(ScenePacketSchema.safeParse({...scene(),frames:[{seconds:1,data:'/9j/AA=='}]}).success).toBe(false);
  expect(ScenePacketSchema.safeParse({...scene(),dialogue:[{start:79,end:90,text:'Later'}]}).success).toBe(false);
  expect(ScenePacketSchema.safeParse({...scene(),title:'Dark'}).success).toBe(false);
 });
 it('accepts only a bounded already-played audio clip and sends it as evidence, not a user command',()=>{
  const wav=sceneWav();
  const audio={startSeconds:70,endSeconds:80,mimeType:'audio/wav' as const,data:base64(wav)};
  const packet=ScenePacketSchema.parse({...scene(),audio});
  const body=sceneObservationBody(packet);
  expect(JSON.stringify(body)).not.toContain(audio.data);
  expect(JSON.stringify(body)).toContain('already-played film audio');
  expect(ScenePacketSchema.safeParse({...scene(),audio:{...audio,endSeconds:81}}).success).toBe(false);
  expect(ScenePacketSchema.safeParse({...scene(),audio:{...audio,startSeconds:60}}).success).toBe(false);
  expect(ScenePacketSchema.safeParse({...scene(),audio:{...audio,startSeconds:71}}).success).toBe(false);
  wav[22]=2;
  expect(ScenePacketSchema.safeParse({...scene(),audio:{...audio,data:base64(wav)}}).success).toBe(false);
 });
 it('rejects claimed observations with missing or invented evidence references',()=>{
  expect(()=>validateSceneObservation({...observation(),timeline:[{event:'An event',frames:[4],dialogue:[],heard:false}]},scene())).toThrow('SCENE_UNSUPPORTED');
  expect(()=>validateSceneObservation({...observation(),timeline:[{event:'An event',frames:[],dialogue:[],heard:true}]},scene())).toThrow('SCENE_UNSUPPORTED');
  expect(validateSceneObservation(observation(),scene()).safeSummary).toContain('contents have not been shown');
 });
 it('returns bounded validated observations and never echoes video bytes',async()=>{
  const pair=new WebSocketPair();pair[1].accept();
  pair[1].addEventListener('message',event=>{
   const message=JSON.parse(String(event.data));
   if(message.setup)pair[1].send(JSON.stringify({setupComplete:{}}));
   if(message.clientContent)pair[1].send(JSON.stringify({toolCall:{functionCalls:[{name:'observe_scene',args:observation()}]}}));
  });
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async(input)=>String(input).includes('/ws/')
   ?new Response(null,{status:101,webSocket:pair[0]}):new Response(null,{status:503}));
  const result=await observePlayerScene(scene(),config,0);
  expect(result).toMatchObject({status:'observed',titleBlind:true,anchorSeconds:80});
  expect(JSON.stringify(result)).not.toContain('/9j/AA==');
  expect(fetch).toHaveBeenCalledTimes(2);
 });
 it('keeps decoded film audio out of the conversation channel and uses quality-filtered played speech',async()=>{
  const packet=ScenePacketSchema.parse({...scene(),audio:{startSeconds:70,endSeconds:80,mimeType:'audio/wav',data:base64(sceneWav())}});
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,init)=>{
   expect(input).toBe('https://api.groq.com/openai/v1/audio/transcriptions');
   expect((init!.body as FormData).get('model')).toBe('whisper-large-v3');
   return Response.json({language:'de',words:[{word:'Bleib',start:1,end:1.3},{word:'hier.',start:1.4,end:1.8},{word:'Invented',start:9,end:9.5}],
    segments:[{start:.9,end:2,no_speech_prob:.1,avg_logprob:-.2},{start:8,end:10,no_speech_prob:.9,avg_logprob:-.2}]});
  });
  const text=await scenePlayedSpeech(packet,{...config,GROQ_API_KEY:'scene-test-only'},new AbortController().signal);
  expect(text).toBe('Bleib hier.');expect(fetch).toHaveBeenCalledOnce();
  const body=JSON.stringify(sceneObservationBody({...packet,heardDialogue:text}));
  expect(body).toContain('Bleib hier.');expect(body).not.toContain(packet.audio!.data);
 });
 it('uses structured observation without opening an unnecessary Live session',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,init)=>{
   expect(String(input)).toContain('gemini-3.6-flash:generateContent');
   expect(JSON.parse(String(init!.body)).generationConfig.thinkingConfig.thinkingLevel).toBe('low');
   return Response.json({candidates:[{content:{parts:[{text:JSON.stringify(observation())}]}}]});
  });
  expect(await observePlayerScene(scene(),config)).toMatchObject({status:'observed',titleBlind:true});
  expect(fetch).toHaveBeenCalledTimes(1);
 });
 it('closes a losing Live hedge when structured observation completes',async()=>{
  const pair=new WebSocketPair();pair[1].accept();let closed=false;
  pair[1].addEventListener('close',()=>{closed=true;});
  pair[1].addEventListener('message',event=>{if(JSON.parse(String(event.data)).setup)pair[1].send(JSON.stringify({setupComplete:{}}));});
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,init)=>{
   if(String(input).includes('/ws/'))return new Response(null,{status:101,webSocket:pair[0]});
   expect(String(input)).toContain('gemini-3.6-flash:generateContent');
   const body=JSON.parse(String(init!.body));expect(body.generationConfig.responseMimeType).toBe('application/json');
   expect(JSON.stringify(body)).not.toContain('Dark');
   await new Promise(resolve=>setTimeout(resolve,10));
   return Response.json({candidates:[{content:{parts:[{text:JSON.stringify(observation())}]}}]});
  });
  const result=await observePlayerScene(scene(),config,0);
  expect(result).toMatchObject({status:'observed',titleBlind:true});
  expect(fetch).toHaveBeenCalledTimes(2);
  await new Promise(resolve=>setTimeout(resolve,10));expect(closed).toBe(true);
 });
 it('retains image and caption evidence when independent speech recognition is unavailable',async()=>{
  const packet=ScenePacketSchema.parse({...scene(),audio:{startSeconds:70,endSeconds:80,mimeType:'audio/wav',data:base64(sceneWav())}});
  vi.spyOn(globalThis,'fetch').mockRejectedValue(Error('unavailable'));
  expect(await scenePlayedSpeech(packet,{...config,GROQ_API_KEY:'scene-test-only'},new AbortController().signal)).toBe('');
  expect(sceneObservationBody({...packet,heardDialogue:''}).contents[0].parts).toContainEqual({inlineData:{mimeType:'image/jpeg',data:'/9j/AA=='}});
  expect(JSON.stringify(sceneObservationBody(packet))).toContain('Keep this sealed until Friday.');
 });
 it('requires identity and an actual current session for scene analysis',async()=>{
  const response=await talkRoute(new Request('https://test/v1/talk/scene',{method:'POST',body:'{}'}),config);
  expect(response?.status).toBe(401);
  const sub='scene-expired-'+crypto.randomUUID();const token=await issueIdentity({sub,kind:'guest',exp:Date.now()+60000},config.CURSOR_SIGNING_SECRET);
  const expired=await talkRoute(new Request('https://test/v1/talk/scene',{method:'POST',headers:{authorization:'Bearer '+token},body:JSON.stringify({session:crypto.randomUUID(),scene:scene()})}),config);
  expect(expired?.status).toBe(410);
 });
 it('retains another active connection when a player connection opens, with a finite session bound',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({name:'auth_tokens/scene-test-ephemeral'}));
  const stub=env.TALK_SESSIONS.getByName('scene-coexisting-'+crypto.randomUUID());
  await runInDurableObject(stub,async(_,state)=>{
   const instance=new TalkSession(state,config);
   const context=TalkContextSchema.parse({companionVersion:13,recentProgress:[]});
   const first=await instance.open(context);const second=await instance.open(context);
   await instance.authorizeScene(first.session);await instance.authorizeScene(second.session);
   const rows=state.storage.sql.exec<{value:string}>("SELECT value FROM memory WHERE key LIKE 'live:%'").toArray();
   expect(rows).toHaveLength(2);expect(rows.every(row=>!row.value.includes('/9j/'))).toBe(true);
   for(let i=0;i<8;i++)await instance.open(context);
   expect(state.storage.sql.exec("SELECT key FROM memory WHERE key LIKE 'live:%'").toArray()).toHaveLength(8);
  });
  expect(fetch).toHaveBeenCalledTimes(10);
 });
});
