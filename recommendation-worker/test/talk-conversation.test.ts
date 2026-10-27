import {describe,it,expect} from 'vitest';
import {TalkContextSchema} from '../src/talkEvidence';
import {liveSetup} from '../src/talkProviders';

describe('contextual conversation protocol',()=>{
 it('gives version nine one explicit microphone owner without legacy VAD knobs',()=>{
  const setup=liveSetup(TalkContextSchema.parse({companionVersion:9,recentProgress:[],locale:'de-DE'}));
  expect(setup.realtimeInputConfig.automaticActivityDetection).toEqual({disabled:true});
  expect(setup.realtimeInputConfig.activityHandling).toBe('START_OF_ACTIVITY_INTERRUPTS');
  expect(setup.systemInstruction.parts[0].text).not.toContain('one approved contextual bridge');
  expect(setup.systemInstruction.parts[0].text).toContain('never narrate waiting');
  const action=setup.tools[0].functionDeclarations.find(t=>t.name==='app_control')!;
  expect(action.parameters.properties).toHaveProperty('quality');
  expect(action.parameters.properties).toHaveProperty('subtitleLanguage');
  expect(action.parameters.properties).toHaveProperty('action.enum',expect.arrayContaining(['cancel_download_work']));
 });
 it('preserves soft leading words and natural clause pauses without changing legacy endpoints',()=>{
  const legacy=liveSetup(TalkContextSchema.parse({companionVersion:7})).realtimeInputConfig.automaticActivityDetection;
  const current=liveSetup(TalkContextSchema.parse({companionVersion:8})).realtimeInputConfig.automaticActivityDetection;
  expect(legacy).toMatchObject({prefixPaddingMs:180,silenceDurationMs:650});
  expect(current).toMatchObject({prefixPaddingMs:500,silenceDurationMs:1500,startOfSpeechSensitivity:'START_SENSITIVITY_LOW'});
 });
 it('exposes factual decisions and quiet yielding only to capable clients',()=>{
  const previous=liveSetup(TalkContextSchema.parse({companionVersion:6,recentProgress:[]}));
  const current=liveSetup(TalkContextSchema.parse({companionVersion:7,recentProgress:[],stickyLanguage:true}));
  const names=(setup:ReturnType<typeof liveSetup>)=>setup.tools[0].functionDeclarations.map(t=>t.name);
  expect(names(previous)).not.toContain('title_facts');expect(names(previous)).not.toContain('wait_for_user');
  expect(names(current)).toContain('title_facts');expect(names(current)).toContain('wait_for_user');
  const prompt=current.systemInstruction.parts[0].text;
  expect(prompt).toContain('CLOSED decisions');expect(prompt).toContain('technical limitations');
  expect(prompt).toContain('neither fits a hard constraint');expect(prompt).toContain('compound request');
  expect(prompt).toContain('Playback control is available through app_control');
  expect(prompt).toContain('Never list these supported actions as limitations');
  expect(current.realtimeInputConfig.activityHandling).toBe('START_OF_ACTIVITY_INTERRUPTS');
 });
});
