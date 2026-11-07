import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,expect,it,vi} from 'vitest';
import {TalkSession} from '../src/talk';
import {TmdbClient} from '../src/tmdb';
import * as evidence from '../src/talkEvidence';
import {storySupportingDialogue} from '../src/storyBudget';
import {preparePlayerStoryEvidence,StoryRequestSchema} from '../src/talkStory';

it('retains a later causal exchange instead of clipping it behind the first clue mention',()=>{
 const dialogue=Array.from({length:180},(_,index)=>[index*3,index*3+2,'Unrelated background conversation.'] as [number,number,string]);
 dialogue[3][2]='The morphine user laughs.';
 for(let index=15;index<110;index++)dialogue[index][2]='The medical bag and death, blood, fortune and inheritance are discussed.';
 dialogue[130][2]='The medical bag is missing.';
 dialogue[132][2]='I gave him one hundred milligrams instead of three.';
 dialogue[134][2]='I cannot find the antidote in the bag.';
 const q=StoryRequestSchema.parse({id:10,kind:'movie',title:'Owned fixture',sourceKey:'owned-cut',anchorSeconds:550,question:'Why is she worried about her medical bag?',dialogue,transcriptComplete:true,frames:[]});
 const rows=storySupportingDialogue(q,['death','morphine','blood','fortune','inheritance','antidote']);
 expect(rows.some(row=>row.text.includes('hundred milligrams'))).toBe(true);
 expect(rows.some(row=>row.text.includes('antidote'))).toBe(true);
 expect(rows.reduce((size,row)=>size+row.text.length,0)).toBeLessThanOrEqual(8000);
 expect(rows.map(row=>row.startSeconds)).toEqual(rows.map(row=>row.startSeconds).sort((a,b)=>a-b));
});

it('keeps the actual earlier accident exchange alongside a dense later rehearsal',()=>{
 const dialogue=Array.from({length:400},(_,index)=>[index*3,index*3+2,
  index<315?'Unrelated background conversation.':'PARK: Remember the green wagon, the school, the corner and the plate.'] as [number,number,string]);
 dialogue[70][2]='DANA: Look what you did to my brother!';
 dialogue[71][2]='PARK: It was an accident.';
 dialogue[72][2]='DANA: My brother can barely stand.';
 dialogue[399][2]='PARK: Do what you did to me.';
 const q=StoryRequestSchema.parse({id:11,kind:'movie',title:'Owned callback fixture',sourceKey:'owned-cut',anchorSeconds:1200,
  question:'How did Park know they could make a staged accident look convincing?',dialogue,transcriptComplete:true,frames:[]});
 const rows=storySupportingDialogue(q,[]);
 expect(rows.some(row=>row.text==='PARK: It was an accident.')).toBe(true);
 expect(rows.some(row=>row.text==='DANA: Look what you did to my brother!')).toBe(true);
 expect(rows.at(-1)?.text).toBe('PARK: Do what you did to me.');
 expect(rows.reduce((size,row)=>size+row.text.length,0)).toBeLessThanOrEqual(8000);
 const packet=preparePlayerStoryEvidence(q,{id:q.id,kind:q.kind,title:q.title,characters:[],evidence:[],creditsUrl:''});
 expect(packet.currentCallback).toMatchObject({seconds:1197,utterance:'PARK: Do what you did to me.'});
 expect(packet.earlierEncounter).toContainEqual(q.dialogue[71]);
 expect(packet.callbackSpeakerEvidence).toMatchObject({speakerLabel:'PARK',earlierSpeakerCue:q.dialogue[71]});
 const identity=preparePlayerStoryEvidence({...q,question:'Who was driving when the accident happened earlier?'},
  {id:q.id,kind:q.kind,title:q.title,characters:[],evidence:[],creditsUrl:''});
 expect(identity.callbackSpeakerEvidence?.earlierSpeakerCue).toEqual(q.dialogue[71]);
});

it('surfaces a current target’s earlier offer and refusal within the existing dialogue budget',()=>{
 const q=StoryRequestSchema.parse({id:12,kind:'movie',title:'Owned motive fixture',sourceKey:'owned-cut',anchorSeconds:1000,
  question:'Why does the planner want the others to approach her?',transcriptComplete:true,frames:[],dialogue:[
   [50,52,'MARA: Are you really here about my case?'],[54,56,'I would like to represent you.'],
   [58,60,'MARA: I do not want to hire a lawyer.'],[400,403,'I promised the other customer a discount.'],
   [990,992,'Find Mara outside the station.'],[995,997,'I will pay you for your help.'],
  ]});
 const packet=preparePlayerStoryEvidence(q,{id:q.id,kind:q.kind,title:q.title,characters:[{name:'Mara',performer:'Example'}],evidence:[],creditsUrl:''});
 expect(packet.earlierIntentExchanges).toEqual([{personReference:'Mara',dialogue:[q.dialogue[1],q.dialogue[2]]}]);
});

afterEach(()=>vi.restoreAllMocks());
it('shares an opening source lookup with the actual question without sharing private scene evidence',async()=>{
 await runInDurableObject(env.TALK_SESSIONS.getByName('source-coalescing-'+crypto.randomUUID()),async(_,state)=>{
  const service=new TalkSession(state,{...env,CURSOR_SIGNING_SECRET:'owned-unit-test-secret'});
  let release!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;});
  const identity=vi.spyOn(TmdbClient.prototype,'storyIdentity').mockImplementation(async()=>{
   await gate;return {id:10,title:'Canonical title',credits:{cast:[]}};
  });
  const research=vi.spyOn(evidence,'retrieveKnowledge').mockResolvedValue([]);
  const request={id:10,kind:'movie' as const,title:'Supplied title'};
  const opening=service.storySources(request),question=service.storySources(request);
  expect(identity).toHaveBeenCalledTimes(1);
  release();
  const [first,second]=await Promise.all([opening,question]);
  expect(first).toEqual(second);expect(second.title).toBe('Canonical title');
  expect(research).toHaveBeenCalledTimes(1);
  expect(await service.storySources(request)).toEqual(first);
  expect(identity).toHaveBeenCalledTimes(1);
 });
});
