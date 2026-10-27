import { describe,it,expect } from 'vitest';
import { TalkContextSchema } from '../src/talkEvidence';
import { talkChatRecommendationCount,talkRecommendationCount,talkRecommendationQuery,talkSeriesStatus } from '../src/talkIntent';
import { TalkChatSchema } from '../src/talkChats';
import { liveSetup } from '../src/talkProviders';

describe('one conversation with mode-appropriate discovery',()=>{
 it('keeps voice small while chat defaults to a substantial bounded collection',()=>{
  expect(talkRecommendationCount('Find thoughtful science fiction')).toBe(3);
  expect(talkChatRecommendationCount('Find puzzle films about a broken memory')).toBe(12);
  expect(talkChatRecommendationCount('Give me 10+ unusual films')).toBe(10);
  expect(talkChatRecommendationCount('Give me twenty international movies')).toBe(20);
  expect(talkChatRecommendationCount('Give me 99 comedy films')).toBe(24);
  expect(talkChatRecommendationCount('Find one movie for a train ride')).toBe(1);
 });
 it('keeps an earlier premise through status and recency refinements',()=>{
  const previous='International mystery series without graphic violence';
  const refined=talkRecommendationQuery('Only ended series. Keep the other constraints.', 'wrong expansion',previous);
  expect(refined).toContain(previous);expect(talkSeriesStatus(refined)).toBe('ended');
  const running=talkRecommendationQuery('Only running series. Keep the other constraints.', '',refined);
  expect(talkSeriesStatus(running)).toBe('returning');
  expect(talkSeriesStatus(talkRecommendationQuery('More different titles with the same constraints.','','Only ended series'))).toBe('ended');
  expect(talkRecommendationQuery('Now find silent comedies','',previous)).toBe('Now find silent comedies');
 });
 it('exposes research notes only to capable voice clients and keeps presentation out of chat',()=>{
  const names=(value:ReturnType<typeof liveSetup>)=>value.tools[0].functionDeclarations.map(tool=>tool.name);
  expect(names(liveSetup(TalkContextSchema.parse({companionVersion:10,textMode:false,recentProgress:[]})))).toContain('research_note');
  expect(names(liveSetup(TalkContextSchema.parse({companionVersion:10,textMode:true,recentProgress:[]})))).not.toContain('present_recommendations');
  expect(names(liveSetup(TalkContextSchema.parse({companionVersion:9,recentProgress:[]})))).not.toContain('research_note');
 });
 it('allows catalogue data to arrive before prose while rejecting empty non-results',()=>{
  const chat={id:'10000000-0000-4000-8000-000000000001',title:'A search',updatedAt:1,lines:[{role:'assistant',text:'',titles:[{id:1,type:'movie',title:'A catalogue title',year:'2024'}]}]};
  expect(TalkChatSchema.safeParse(chat).success).toBe(true);
  expect(TalkChatSchema.safeParse({...chat,lines:[{role:'assistant',text:''}]}).success).toBe(false);
  expect(TalkChatSchema.safeParse({...chat,lines:[{role:'user',text:''}]}).success).toBe(false);
 });
 it('accepts the new context without requiring older installed clients to send it',()=>{
  expect(TalkContextSchema.parse({companionVersion:10,textMode:true}).textMode).toBe(true);
  expect(TalkContextSchema.parse({companionVersion:9}).textMode).toBeUndefined();
 });
});
