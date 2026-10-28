import {describe,it,expect} from 'vitest';
import {liveSetup} from '../src/talkProviders';
import {TalkContextSchema} from '../src/talkEvidence';
import {talkRecommendationCount} from '../src/talkIntent';

describe('version eleven conversation stage',()=>{
 it('requires a model-authored bridge in the actual lookup, not another optional call',()=>{
  const setup=liveSetup(TalkContextSchema.parse({recentProgress:[],companionVersion:11}));
  for(const name of ['recommendations','film_knowledge','web_research']){
   const tool=setup.tools[0].functionDeclarations.find(t=>t.name===name)!;
   expect(tool.behavior).toBe('NON_BLOCKING');expect(tool.parameters.required).toContain('bridge');
   expect(tool.parameters.properties).toHaveProperty('bridge');
  }
  expect(setup.systemInstruction.parts[0].text).toContain('app-owned lookup');
 });
 it('supports eight actual choices while preserving installed-client and chat contracts',()=>{
  const voice=liveSetup(TalkContextSchema.parse({recentProgress:[],companionVersion:11}));
  const old=liveSetup(TalkContextSchema.parse({recentProgress:[],companionVersion:10}));
  const chat=liveSetup(TalkContextSchema.parse({recentProgress:[],companionVersion:11,textMode:true}));
  const tool=(setup:typeof voice,name:string)=>setup.tools[0].functionDeclarations.find(t=>t.name===name)!;
  expect(tool(voice,'recommendations').parameters.properties.count.maximum).toBe(8);
  expect(tool(voice,'present_recommendations').parameters.properties.picks.maxItems).toBe(8);
  expect(tool(voice,'show_title_cards').parameters.properties.titles.maxItems).toBe(8);
  expect(tool(old,'recommendations').parameters.properties.count.maximum).toBe(3);
  expect(tool(old,'recommendations').parameters.properties).not.toHaveProperty('bridge');
  expect(tool(chat,'recommendations').parameters.properties).not.toHaveProperty('bridge');
 });
 it('recognizes larger spoken counts without counting a year as a quantity',()=>{
  expect(talkRecommendationCount('Five films built around strange social rules')).toBe(5);
  expect(talkRecommendationCount('Give me eight films from 2008')).toBe(8);
  expect(talkRecommendationCount('Movies from 2005')).toBe(3);
 });
});
