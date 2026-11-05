import {afterEach,expect,it,vi} from 'vitest';
import {geminiLiveStructured} from '../src/geminiLiveStructured';
import type {RecommendationEnv} from '../src/types';
afterEach(()=>vi.restoreAllMocks());
it('returns one structured Live tool result, accounts for input and closes the socket',async()=>{
 const pair=new WebSocketPair();pair[1].accept();let closed=false,setup:any,turns=0;
 pair[1].addEventListener('close',()=>{closed=true;});
 pair[1].addEventListener('message',event=>{
  const value=JSON.parse(String(event.data));
  if(value.setup){setup=value.setup;pair[1].send(JSON.stringify({setupComplete:{}}));}
  if(value.clientContent){turns++;pair[1].send(JSON.stringify({toolCall:{functionCalls:[{name:'compile_scene_memory',args:{facts:['Owned evidence']}}]}}));}
 });
 const acquire=vi.fn().mockResolvedValue({allowed:true,lease:'owned'});
 const env={GEMINI_API_KEY:'owned-'+crypto.randomUUID(),GEMINI_FREE_TIER_MODE:'true',TALK_SESSIONS:{getByName:()=>({acquireGeminiBudget:acquire,releaseGeminiBudget:async()=>{}})}} as unknown as RecommendationEnv;
 const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(null,{status:101,webSocket:pair[0]}));
 expect(await geminiLiveStructured(env,'Evidence only.',[{text:'A named clue.'}],{type:'object',properties:{facts:{type:'array',items:{type:'string'}}}})).toEqual({facts:['Owned evidence']});
 expect(turns).toBe(1);expect(fetcher).toHaveBeenCalledTimes(1);
 expect(acquire.mock.calls[0][2]).toBeGreaterThan(0);
 expect(fetcher.mock.calls[0][1]).not.toHaveProperty('geminiInputTokens');
 expect(setup.tools[0].functionDeclarations[0].parameters.type).toBe('OBJECT');
 await new Promise(resolve=>setTimeout(resolve,10));expect(closed).toBe(true);
});
it('rejects plain generation instead of accepting an unvalidated answer or looping',async()=>{
 const pair=new WebSocketPair();pair[1].accept();let turns=0;
 pair[1].addEventListener('message',event=>{
  const value=JSON.parse(String(event.data));
  if(value.setup)pair[1].send(JSON.stringify({setupComplete:{}}));
  if(value.clientContent){turns++;pair[1].send(JSON.stringify({serverContent:{modelTurn:{parts:[{text:'An unsupported answer.'}]},turnComplete:true}}));}
 });
 vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(null,{status:101,webSocket:pair[0]}));
 await expect(geminiLiveStructured({GEMINI_API_KEY:'owned'} as RecommendationEnv,'Evidence only.',[],{type:'object'})).rejects.toThrow('SCENE_UNAVAILABLE');
 expect(turns).toBe(1);
});
it('moves a late quota rejection to another project within the same bounded analysis',async()=>{
 const primary='owned-first-'+crypto.randomUUID(),secondary='owned-next-'+crypto.randomUUID();
 vi.spyOn(Math,'random').mockReturnValue(0);let calls=0;
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{
  const pair=new WebSocketPair();pair[1].accept();const attempt=calls++;
  pair[1].addEventListener('message',event=>{
   const value=JSON.parse(String(event.data));
   if(value.setup)pair[1].send(JSON.stringify(attempt===0?{error:{code:429,status:'RESOURCE_EXHAUSTED'}}:{setupComplete:{}}));
   if(value.clientContent)pair[1].send(JSON.stringify({toolCall:{functionCalls:[{name:'compile_scene_memory',args:{facts:['Same evidence, remaining project']}}]}}));
  });
  return new Response(null,{status:101,webSocket:pair[0]});
 });
 expect(await geminiLiveStructured({GEMINI_API_KEY:primary,GEMINI_API_KEY_SECONDARY:secondary} as RecommendationEnv,'Evidence only.',[],{type:'object'})).toEqual({facts:['Same evidence, remaining project']});
 expect(fetcher.mock.calls.map(call=>new Headers(call[1]?.headers).get('x-goog-api-key'))).toEqual([primary,secondary]);
});
