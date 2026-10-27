import {afterEach,describe,expect,it,vi} from 'vitest';
import {boundedJson,issueLiveToken,liveSetup,liveInstructions} from '../src/talkProviders';
import {TalkContextSchema} from '../src/talkEvidence';
import {env} from 'cloudflare:test';
afterEach(()=>vi.restoreAllMocks());
describe('Live bounded provisioning',()=>{
 it('returns manual activity metadata while keeping its actual config locked in the token',async()=>{
  const spy=vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({name:'auth_tokens/test-manual-activity'}));
  const token=await issueLiveToken({...env,GEMINI_API_KEY:'test',CURSOR_SIGNING_SECRET:'test'},TalkContextSchema.parse({companionVersion:9}),Date.now()+60000);
  const mint=JSON.parse(String(spy.mock.calls[0][1]?.body));
  expect(mint.bidiGenerateContentSetup.realtimeInputConfig.automaticActivityDetection).toEqual({disabled:true});
  expect(mint.fieldMask.split(',')).toContain('realtimeInputConfig');
  expect(token.inputActivity).toBe('manual');
  expect(token.setup).not.toHaveProperty('systemInstruction');
 });
 it('exposes real card control only to capable clients and welcomes general conversation',()=>{
  const context=TalkContextSchema.parse({companionVersion:4,recentProgress:[]});
  expect(liveSetup(context).tools[0].functionDeclarations.map(t=>t.name)).toContain('show_title_cards');
  expect(liveSetup(TalkContextSchema.parse({companionVersion:3,recentProgress:[]})).tools[0].functionDeclarations.map(t=>t.name)).not.toContain('show_title_cards');
  expect(liveInstructions(context)).toContain('Respond naturally to EVERY user utterance');
  const capable=liveSetup(TalkContextSchema.parse({companionVersion:5,recentProgress:[]}));
  expect(capable.tools[0].functionDeclarations.map(t=>t.name)).toContain('app_control');
  expect(liveSetup(context).tools[0].functionDeclarations.map(t=>t.name)).not.toContain('app_control');
  expect(capable.contextWindowCompression.slidingWindow.targetTokens).toBe('8192');
  expect(liveInstructions(TalkContextSchema.parse({companionVersion:5,recentProgress:[]}))).not.toContain('Do not claim you can control playback');
  expect(liveInstructions(context)).toContain('Do not claim you can control playback');
 });
 it('rejects oversized and failing responses',async()=>{await expect(boundedJson(Response.json({value:'x'.repeat(100)}),20)).rejects.toThrow('RESPONSE_LIMIT');await expect(boundedJson(new Response('unavailable',{status:503}))).rejects.toThrow('SOURCE_UNAVAILABLE');});
 it('has no automatic voice or model fallback on failure',async()=>{const spy=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response('',{status:503}));await expect(issueLiveToken({...env,GEMINI_API_KEY:'test',CURSOR_SIGNING_SECRET:'test'},TalkContextSchema.parse({}),Date.now()+60000)).rejects.toThrow();expect(spy).toHaveBeenCalledTimes(1);expect(String(spy.mock.calls[0][0])).toContain('generativelanguage.googleapis.com');});
});
