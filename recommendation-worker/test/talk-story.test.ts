import {describe,it,expect,vi,afterEach} from 'vitest';
import {StoryRequestSchema,validateStoryUnderstanding,limitedStoryContext,understandPlayerStory,playerStoryResearch,STORY_ANALYSIS_INSTRUCTIONS,type StorySources} from '../src/talkStory';
import {liveSetup} from '../src/talkProviders';
import {TalkContextSchema} from '../src/talkEvidence';
import type {RecommendationEnv} from '../src/types';
const request=()=>StoryRequestSchema.parse({id:10,kind:'movie',title:'Owned scene fixture',sourceKey:'cut-a:revision-2',anchorSeconds:1200,
 question:'Why are they escaping, and who is reassuring Jane?',dialogue:[[20,22,'Daniel took the evidence from the company.'],[75,78,'Jane is in danger because they want it back.'],[1195,1198,'Look, Jane, it was not you.']],
 transcriptComplete:true,frames:[{seconds:1198,data:'/9j/AA=='}],captionAlignment:'edition_selected_alignment_unverified'});
const sources:StorySources={id:10,kind:'movie',title:'Owned scene fixture',creditsUrl:'https://www.themoviedb.org/movie/10/cast',
 characters:[{name:'Daniel Kellner',performer:'Credited performer'},{name:'Jane Blankenship',performer:'Another performer'},{name:'Future Identity',performer:'Future performer'}],
 evidence:[{id:'wiki-rev-1',title:'Owned plot source',text:'Daniel and Jane flee the company after he takes its evidence. A future event is quarantined background.',url:'https://en.wikipedia.org/w/index.php?oldid=1',license:'CC BY-SA'}]};
const answer=()=>({characters:[{name:'Daniel Kellner',sceneRole:'The driver comforting Jane',cueIds:[0,1,2],frameIds:[0],sourceIds:['credits','wiki-rev-1'],basis:'story_and_scene',firstEstablishedSeconds:1198}],
 facts:[{text:'They are pursued over evidence Daniel took; his reassurance separates Jane from responsibility.',kind:'established',cueIds:[0,1,2],frameIds:[0],sourceIds:['wiki-rev-1'],revealSeconds:1198}],
 explanation:'Daniel and Jane are fleeing the company pursuing its stolen evidence, not merely reacting to noise.',unknowns:[]});
afterEach(()=>vi.restoreAllMocks());
describe('named source-bound player story',()=>{
 it('retrieves references for a single Live pass without calling another inference model or certifying untimed research',()=>{
  const fetcher=vi.spyOn(globalThis,'fetch');const result=playerStoryResearch(request(),sources);
  expect(result.status).toBe('research_ready');expect(fetcher).not.toHaveBeenCalled();
  expect(result.independentResearch[0].id).toBe('wiki-rev-1');expect(result.scope).toContain('FUTURE'.toLowerCase());
  expect(result.mentionedCharacters).not.toContain('Future Identity');
  expect(result.dialogueCount).toBe(3);expect(result.transcriptComplete).toBe(true);
 });
 it('supports a name established by earlier story even when the current caption has no speaker label',()=>{
  const result=validateStoryUnderstanding(answer(),request(),sources);
  expect(result.characters[0].name).toBe('Daniel Kellner');expect(result.facts[0].cueIds).toContain(0);
 });
 it('canonicalizes a unique first-name answer without inventing another credited person',()=>{
  const value=answer();value.characters[0].name='Daniel';expect(validateStoryUnderstanding(value,request(),sources).characters[0].name).toBe('Daniel Kellner');
  value.characters[0].name='Daniel Kellner';expect(()=>validateStoryUnderstanding(value,request(),{...sources,characters:[...sources.characters,{name:'Daniel Other',performer:'Other'}]})).not.toThrow();
  value.characters[0].name='Daniel';expect(()=>validateStoryUnderstanding(value,request(),{...sources,characters:[...sources.characters,{name:'Daniel Other',performer:'Other'}]})).toThrow();
  expect(validateStoryUnderstanding(value,request(),{...sources,characters:[{name:'Dr. Daniel Kellner',performer:'Credited'}]}).characters[0].name).toBe('Dr. Daniel Kellner');
 });
 it('rejects an invented name rather than making a confident character guess',()=>{const value=answer();value.characters[0].name='Invented Name';expect(()=>validateStoryUnderstanding(value,request(),sources)).toThrow();});
 it('rejects a cast-only binding without actual scene and earlier dialogue support',()=>{
  const value=answer();value.characters[0].frameIds=[];expect(()=>validateStoryUnderstanding(value,request(),sources)).toThrow();
  value.characters[0].frameIds=[0];value.characters[0].cueIds=[];expect(()=>validateStoryUnderstanding(value,request(),sources)).toThrow();
 });
 it('rejects invalid references, invented research IDs and later revelation times',()=>{
  for(const change of [(a:ReturnType<typeof answer>)=>{a.facts[0].cueIds=[99]},(a:ReturnType<typeof answer>)=>{a.facts[0].sourceIds=['fake-source']},(a:ReturnType<typeof answer>)=>{a.facts[0].revealSeconds=1201}]){
   const value=answer();change(value);expect(()=>validateStoryUnderstanding(value,request(),sources)).toThrow();
  }
 });
 it('rejects future frame/caption input before analysis and demands the exact TV episode',()=>{
  expect(StoryRequestSchema.safeParse({...request(),frames:[{seconds:1201,data:'/9j/AA=='}]}).success).toBe(false);
  expect(StoryRequestSchema.safeParse({...request(),dialogue:[[1201,1202,'Later secret']]}).success).toBe(false);
  expect(StoryRequestSchema.safeParse({...request(),kind:'tv'}).success).toBe(false);
 });
 it('keeps the scene-specific input budget bounded',()=>{
  expect(StoryRequestSchema.safeParse({...request(),dialogue:Array.from({length:400},()=>[1,2,'x'.repeat(320)])}).success).toBe(false);
 });
 it('exposes only already-mentioned canonical names when analysis is unavailable, without certifying a face',()=>{
  const result=limitedStoryContext(request(),sources);expect(result.characters).toEqual([]);
  expect(result.mentionedCharacters.map(c=>c.name)).toEqual(['Daniel Kellner','Jane Blankenship']);
  expect(JSON.stringify(result)).not.toContain('Future Identity');
 });
 it('sends independent story, credits and much more than the Live transcript page, returning only bounded analysed facts',async()=>{
  const q=request();q.dialogue=Array.from({length:200},(_,i)=>[i*4,i*4+2,`Earlier exchange ${i}: `+'evidence '.repeat(12)]);
  const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({candidates:[{content:{parts:[{text:JSON.stringify(answer())}]}}]}));
  const result=await understandPlayerStory(q,sources,{GEMINI_API_KEY:'test-only',GEMINI_SCENE_MODEL:'gemini-3.8-flash'} as RecommendationEnv);
  expect(result.status).toBe('grounded');
  const body=JSON.parse(String(fetcher.mock.calls[0][1]?.body));expect(body.generationConfig.thinkingConfig.thinkingLevel).toBe('low');const data=JSON.parse(body.contents[0].parts[0].text);
  expect(data.dialogue).toHaveLength(200);expect(JSON.stringify(data.dialogue).length).toBeGreaterThan(12000);
  expect(data.independentResearch[0].id).toBe('wiki-rev-1');expect(body.contents[0].parts[2].inlineData.mimeType).toBe('image/jpeg');
  expect(JSON.stringify(result)).not.toContain('quarantined background');expect(body.systemInstruction.parts[0].text).toContain('past event revealed later');
 });
 it('uses the fast multimodal scene model without changing the general generation model',async()=>{
  const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({candidates:[{content:{parts:[{text:JSON.stringify(answer())}]}}]}));
  expect((await understandPlayerStory(request(),sources,{GEMINI_API_KEY:'test-only',GEMINI_GENERATION_MODEL:'gemini-3.8-flash'} as RecommendationEnv)).status).toBe('grounded');
  expect(String(fetcher.mock.calls[0][0])).toContain('gemini-3.6-flash:generateContent');
  expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body)).generationConfig.thinkingConfig.thinkingLevel).toBe('minimal');
 });
 it('treats a real provider quota refusal as limited evidence, never a fabricated named answer',async()=>{
  vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response('quota',{status:429}));
  const result=await understandPlayerStory(request(),sources,{GEMINI_API_KEY:'test-only'} as RecommendationEnv);
  expect(result.status).toBe('limited');expect(result.characters).toEqual([]);
 });
 it('reports a bounded provider failure without exposing future source text as scene knowledge',async()=>{
  vi.spyOn(globalThis,'fetch').mockRejectedValue(new DOMException('deadline','TimeoutError'));
  const result=await understandPlayerStory(request(),sources,{GEMINI_API_KEY:'test-only'} as RecommendationEnv);
  expect(result).toMatchObject({status:'limited',preferredFailure:'timeout'});
  expect(JSON.stringify(result)).not.toContain('quarantined background');
 });
 it('uses current Cloudflare vision with the same evidence gates and bounded temporal images',async()=>{
  const run=vi.fn().mockResolvedValue({choices:[{message:{content:JSON.stringify(answer())}}]});
  const q=request();q.frames=Array.from({length:4},(_,i)=>({seconds:1190+i,data:'/9j/AA=='}));
  const result=await understandPlayerStory(q,sources,{AI:{run}} as unknown as RecommendationEnv);
  expect(result.status).toBe('grounded');const body=run.mock.calls[0][1];
  expect(run.mock.calls[0][0]).toBe('@cf/qwen/qwen3.8-27b');expect(body.messages[1].content.filter((p:{type:string})=>p.type==='image_url')).toHaveLength(3);
  expect(body.reasoning_effort).toBe('low');expect(body.store).toBe(false);
  const invalid=answer();invalid.characters[0].name='Invented Person';run.mockResolvedValue({choices:[{message:{content:JSON.stringify(invalid)}}]});
  expect((await understandPlayerStory(request(),sources,{AI:{run}} as unknown as RecommendationEnv)).status).toBe('limited');
 });
 it('versions the new read-only capability without breaking the installed client contract',()=>{
  const setup=(version:number)=>liveSetup(TalkContextSchema.parse({companionVersion:version,recentProgress:[],stickyLanguage:true}));
  const action=(version:number)=>setup(version).tools[0].functionDeclarations.find(t=>t.name==='app_control')!.parameters.properties.action.enum;
  expect(action(16)).toContain('scene_story');expect(action(15)).not.toContain('scene_story');
  expect(setup(16).systemInstruction.parts[0].text).toContain('NAMED STORY UNDERSTANDING');
  expect(STORY_ANALYSIS_INSTRUCTIONS).toContain('what the pursuer wants');
 });
});
