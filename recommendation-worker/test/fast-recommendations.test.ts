import {afterEach,expect,it,vi} from 'vitest';
import {env} from 'cloudflare:test';
import {liveRecommendationPicks,tavilyCandidatePicks} from '../src/fastRecommendations';
afterEach(()=>vi.restoreAllMocks());
it('curates a request in one Gemini Live call and accepts binary server messages',async()=>{
 const pair=new WebSocketPair();pair[1].accept();
 const send=(value:unknown)=>pair[1].send(new TextEncoder().encode(JSON.stringify(value)));
 pair[1].addEventListener('message',event=>{
  const message=JSON.parse(String(event.data));
  if(message.setup){expect(message.setup.model).toBe('models/gemini-3.8-live');send({setupComplete:{}});}
  if(message.clientContent)send({toolCall:{functionCalls:[{name:'recommend_titles',args:{candidates:[{title:'The Gentlemen',year:2019},{title:'To Catch a Thief',year:1955}]}}]}});
 });
 const fetch=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(null,{status:101,webSocket:pair[0]}));
 const picks:string[]=[];
 const result=await liveRecommendationPicks({...env,GEMINI_API_KEY:'test',CURSOR_SIGNING_SECRET:'test'},{query:'gentlemen'},new AbortController().signal,p=>picks.push(p.title));
 expect(result.map(p=>p.title)).toEqual(['The Gentlemen','To Catch a Thief']);expect(picks).toEqual(result.map(p=>p.title));expect(fetch).toHaveBeenCalledTimes(1);
});
it('moves a rejected Live turn through all three projects after successful upgrades',async()=>{
 const keys=['primary','secondary','tertiary'].map(slot=>slot+'-'+crypto.randomUUID());
 vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async (_url,init)=>{
  const slot=keys.indexOf(new Headers(init?.headers).get('x-goog-api-key')||'');
  const pair=new WebSocketPair();pair[1].accept();
  pair[1].addEventListener('message',event=>{
   const message=JSON.parse(String(event.data));
   if(message.setup)pair[1].send(JSON.stringify({setupComplete:{}}));
   if(message.clientContent)pair[1].send(JSON.stringify(slot<2
    ? {error:{code:slot===0?429:503}}
    : {toolCall:{functionCalls:[{name:'recommend_titles',args:{candidates:[{title:'The Conversation',year:1974}]}}]}}));
  });
  return new Response(null,{status:101,webSocket:pair[0]});
 });
 const credentials={...env,CURSOR_SIGNING_SECRET:'test-only',GEMINI_API_KEY:keys[0],GEMINI_API_KEY_SECONDARY:keys[1],GEMINI_API_KEY_TERTIARY:keys[2]};
 const input={query:'Suggest three more spy films',excludeTitles:['The Lives of Others']};
 await expect(liveRecommendationPicks(credentials,input,AbortSignal.timeout(3000))).resolves.toEqual([{title:'The Conversation',year:1974}]);
 expect(fetcher.mock.calls.map(([,init])=>new Headers(init?.headers).get('x-goog-api-key'))).toEqual(keys);
});
it('uses actual Tavily titles during capacity failures, including unknown years for TMDB lookup',()=>{
 const picks=tavilyCandidatePicks('1. **Kingsman: The Secret Service** (2014)\n2. To Catch a Thief (1955)\n- The Grand Budapest Hotel — a stylish comedy',[]);
 expect(picks).toContainEqual({title:'Kingsman: The Secret Service',year:2014});
 expect(picks).toContainEqual({title:'To Catch a Thief',year:1955});
 expect(picks).toContainEqual({title:'The Grand Budapest Hotel',year:0});
 expect(tavilyCandidatePicks('No suitable title was found.',[])).toEqual([]);
});
