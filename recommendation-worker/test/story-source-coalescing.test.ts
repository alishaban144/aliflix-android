import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,expect,it,vi} from 'vitest';
import {TalkSession} from '../src/talk';
import {TmdbClient} from '../src/tmdb';
import * as evidence from '../src/talkEvidence';
import {storySupportingDialogue} from '../src/storyBudget';
import {StoryRequestSchema} from '../src/talkStory';

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
