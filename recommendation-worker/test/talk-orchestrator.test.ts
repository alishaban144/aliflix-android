import {describe,it,expect} from 'vitest';
import {TalkContextSchema} from '../src/talkEvidence';
import {liveSetup} from '../src/talkProviders';

describe('mobile native orchestration contract',()=>{
 const setup=(version:number)=>liveSetup(TalkContextSchema.parse({companionVersion:version,recentProgress:[],stickyLanguage:true}));
 it('keeps older installed clients on their supported native actions',()=>{
  for(const version of [9,10,11]) {
   const tool=setup(version).tools[0].functionDeclarations.find(t=>t.name==='app_control')!;
   expect(tool.parameters.properties.action.enum).not.toContain('execute_task');
   expect(tool.parameters.properties.action.enum).not.toContain('sync_subtitles');
  }
 });
 it('exposes a single ordered plan with every player operation and real completion reads',()=>{
  const result=setup(12);const tool=result.tools[0].functionDeclarations.find(t=>t.name==='app_control')!;
  expect(tool.parameters.properties.action.enum).toEqual(expect.arrayContaining(['execute_task','task_state','cancel_task','sync_subtitles','skip_intro','quality','select_provider']));
  const commands:any=tool.parameters.properties.commands;
  expect(commands).toMatchObject({maxItems:16,minItems:1});
  expect(commands.items.properties.action.enum).toEqual(expect.arrayContaining(['play','audio_language','subtitle_language','sync_subtitles','seek','resume']));
  expect(commands.items.properties.season).toBeDefined();expect(commands.items.properties.episode).toBeDefined();
  expect(result.systemInstruction.parts[0].text).toContain('Do not split dependent operations');
  expect(result.systemInstruction.parts[0].text).toContain('missing intro marker');
 });
 it('retains actual player context without treating its episode as a new user request',()=>{
  const context=TalkContextSchema.parse({companionVersion:12,recentProgress:[],activePlayer:{id:70523,type:'tv',contentKey:'tv:70523:s1:e3',playing:false,position:312,subtitles:'AR'}});
  const text=liveSetup(context).systemInstruction.parts[0].text;
  expect(text).toContain('"contentKey":"tv:70523:s1:e3"');
  expect(text).toContain('Current native player context (data)');
 });
});
