import {afterEach,expect,it,vi} from 'vitest';
import {fetchGemini,geminiProjectRequest,hasGeminiCredentials,raceGeminiGeneration,redactGeminiKeys} from '../src/geminiKeys';
const url='https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent';
const credentials=()=>({GEMINI_API_KEY:'test-primary-'+crypto.randomUUID(),GEMINI_API_KEY_SECONDARY:'test-secondary-'+crypto.randomUUID()});
const used=(init?:RequestInit)=>new Headers(init?.headers).get('x-goog-api-key');
afterEach(()=>vi.restoreAllMocks());

it('immediately fails over to the independent project and avoids the cooling project on the next request',async()=>{
 const env=credentials();vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(Response.json({error:{code:429}},{status:429,headers:{'retry-after':'60'}}))
  .mockImplementation(async()=>Response.json({ok:true}));
 expect((await fetchGemini(env,url,{method:'POST',body:'{}'})).ok).toBe(true);
 expect((await fetchGemini(env,url,{method:'POST',body:'{}'})).ok).toBe(true);
 expect(fetcher.mock.calls.map(call=>used(call[1]))).toEqual([env.GEMINI_API_KEY,env.GEMINI_API_KEY_SECONDARY,env.GEMINI_API_KEY_SECONDARY]);
 expect(fetcher.mock.calls.every(call=>String(call[0])===url)).toBe(true);
});
it('balances successful requests without duplicating them',async()=>{
 const env=credentials();vi.spyOn(Math,'random').mockReturnValueOnce(0).mockReturnValueOnce(.9);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({ok:true}));
 await fetchGemini(env,url,{});await fetchGemini(env,url,{});
 expect(fetcher.mock.calls.map(call=>used(call[1]))).toEqual([env.GEMINI_API_KEY,env.GEMINI_API_KEY_SECONDARY]);
});
it('balances all three projects and uses the third after both other projects refuse quota',async()=>{
 const env={...credentials(),GEMINI_API_KEY_TERTIARY:'test-tertiary-'+crypto.randomUUID()};
 const random=vi.spyOn(Math,'random').mockReturnValueOnce(0).mockReturnValueOnce(.4).mockReturnValueOnce(.9);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({ok:true}));
 for(let n=0;n<3;n++)await fetchGemini(env,url,{});
 expect(fetcher.mock.calls.map(call=>used(call[1]))).toEqual([env.GEMINI_API_KEY,env.GEMINI_API_KEY_SECONDARY,env.GEMINI_API_KEY_TERTIARY]);
 random.mockReturnValue(0);fetcher.mockClear();
 fetcher.mockResolvedValueOnce(new Response(null,{status:429})).mockResolvedValueOnce(new Response(null,{status:429})).mockResolvedValue(Response.json({ok:true}));
 expect((await fetchGemini(env,url,{})).ok).toBe(true);
 expect(fetcher.mock.calls.map(call=>used(call[1]))).toEqual([env.GEMINI_API_KEY,env.GEMINI_API_KEY_SECONDARY,env.GEMINI_API_KEY_TERTIARY]);
});
it('pins the tertiary project and redacts its key without requiring either older key',async()=>{
 const env={GEMINI_API_KEY_TERTIARY:'owned-third-'+crypto.randomUUID()};
 const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({ok:true}));
 expect(hasGeminiCredentials(env)).toBe(true);
 const result=await geminiProjectRequest(env,url,{},'tertiary');expect(result.keySlot).toBe('tertiary');
 expect(used(fetcher.mock.calls[0][1])).toBe(env.GEMINI_API_KEY_TERTIARY);
 expect(redactGeminiKeys(env,env.GEMINI_API_KEY_TERTIARY)).toBe('[key]');
 expect(String(fetcher.mock.calls[0][0])).not.toContain(env.GEMINI_API_KEY_TERTIARY);
});
it('does not rotate credentials for invalid model/request parameters',async()=>{
 vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({error:{code:400}},{status:400}));
 expect((await fetchGemini(credentials(),url,{})).status).toBe(400);expect(fetcher).toHaveBeenCalledTimes(1);
});
it('isolates model quotas and never puts a key in a provider URL',async()=>{
 const env=credentials();vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response(null,{status:429})).mockImplementation(async()=>Response.json({ok:true}));
 await fetchGemini(env,url,{});
 await fetchGemini(env,url.replace('gemini-4-hydrogen:generateContent','gemini-embedding-2:embedContent'),{});
 expect(used(fetcher.mock.calls[2][1])).toBe(env.GEMINI_API_KEY);
 expect(fetcher.mock.calls.every(call=>!String(call[0]).includes(env.GEMINI_API_KEY))).toBe(true);
});
it('pins an existing voice session to its original project instead of invalidating its resumption handle',async()=>{
 const env=credentials();const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(null,{status:503}));
 const result=await geminiProjectRequest(env,url,{},'secondary');
 expect(result.response.status).toBe(503);expect(result.keySlot).toBe('secondary');
 expect(used(fetcher.mock.calls[0][1])).toBe(env.GEMINI_API_KEY_SECONDARY);expect(fetcher).toHaveBeenCalledTimes(1);
});
it('shares the caller deadline across failover and does not retry an aborted request',async()=>{
 const controller=new AbortController();vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{controller.abort();return new Response(null,{status:429});});
 await expect(fetchGemini(credentials(),url,{signal:controller.signal})).rejects.toThrow();expect(fetcher).toHaveBeenCalledTimes(1);
});
it.each(['headers','body'])('starts both projects together when one stalls before completing %s',async stage=>{
 const env=credentials();
 const arrivals = new Map<string, AbortSignal>();
 let markBothStarted!: () => void;
 const bothStarted = new Promise<void>(resolve=>{markBothStarted=resolve;});
 vi.spyOn(globalThis,'fetch').mockImplementation(async (_url,init)=>{
  const key=used(init)!;
  const signal=init!.signal!;
  arrivals.set(key,signal);
  if(arrivals.size===2) markBothStarted();
  if(key===env.GEMINI_API_KEY_SECONDARY) {
   await bothStarted;
   return Response.json({project:'secondary'});
  }
  if(stage==='headers') return new Promise((_,reject)=>{
   signal.addEventListener('abort',()=>reject(signal.reason),{once:true});
  });
  return new Response(new ReadableStream({
   start(controller){
    controller.enqueue(new TextEncoder().encode('{"project":'));
    signal.addEventListener('abort',()=>controller.error(signal.reason),{once:true});
   },
  }));
 });
 const result=await raceGeminiGeneration(env,url,{body:'{}',signal:AbortSignal.timeout(1500)},response=>response.json());
 expect(result).toEqual({data:{project:'secondary'},keySlot:'secondary'});
 expect(arrivals.size).toBe(2);
 expect(arrivals.get(env.GEMINI_API_KEY)?.aborted).toBe(true);
});
it('keeps the caller deadline for the entire race and cancels both pending projects',async()=>{
 const env={...credentials(),GEMINI_API_KEY_TERTIARY:'owned-third-'+crypto.randomUUID()};
 const caller=new AbortController();
 const signals:AbortSignal[]=[];
 vi.spyOn(globalThis,'fetch').mockImplementation(async (_url,init)=>new Promise((_,reject)=>{
  const signal=init!.signal!;
  signals.push(signal);
  signal.addEventListener('abort',()=>reject(signal.reason),{once:true});
  if(signals.length===2)caller.abort(new DOMException('Deadline','TimeoutError'));
 }));
 await expect(raceGeminiGeneration(env,url,{body:'{}',signal:caller.signal},response=>response.json())).rejects.toMatchObject({name:'TimeoutError'});
 expect(signals).toHaveLength(2);
 expect(signals.every(signal=>signal.aborted)).toBe(true);
});
it('takes an available third project when either initial project fails without waiting for the other',async()=>{
 const env={...credentials(),GEMINI_API_KEY_TERTIARY:'owned-third-'+crypto.randomUUID()};
 vi.spyOn(Math,'random').mockReturnValue(0);
 const attempted=new Set<string>();
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async (_url,init)=>{
  const key=used(init)!;
  attempted.add(key);
  if(key===env.GEMINI_API_KEY) return Response.json({error:{code:429}},{status:429});
  if(key===env.GEMINI_API_KEY_TERTIARY) return Response.json({project:'tertiary'});
  const signal=init!.signal!;
  return new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));
 });
 await expect(raceGeminiGeneration(env,url,{body:'{}',signal:AbortSignal.timeout(1500)},response=>response.json())).resolves.toEqual({data:{project:'tertiary'},keySlot:'tertiary'});
 expect(attempted).toEqual(new Set([env.GEMINI_API_KEY,env.GEMINI_API_KEY_SECONDARY,env.GEMINI_API_KEY_TERTIARY]));
 expect(fetcher).toHaveBeenCalledTimes(3);
});
it('honors free-tier admission and releases the cancelled request lease',async()=>{
 const credentialsForTest={...credentials(),GEMINI_API_KEY_TERTIARY:'owned-third-'+crypto.randomUUID()};
 const owners = await Promise.all(Object.values(credentialsForTest).map(async key=>{
  const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(key));
  return Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join('');
 }));
 const releaseGeminiBudget=vi.fn(async()=>{});
 const quota={
  acquireGeminiBudget:vi.fn(async (owner:string)=>owner===owners[0]
   ? {allowed:false,status:429,retryAt:Date.now()+60000}
   : {allowed:true,lease:owner}),
  releaseGeminiBudget,
 };
 const env={...credentialsForTest,GEMINI_FREE_TIER_MODE:'true',
  TALK_SESSIONS:{getByName:()=>quota} as unknown as NonNullable<Parameters<typeof raceGeminiGeneration>[0]['TALK_SESSIONS']>};
 vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async (_url,init)=>{
  if(used(init)===env.GEMINI_API_KEY_TERTIARY) return Response.json({project:'tertiary'});
  const signal=init!.signal!;
  return new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));
 });
 await expect(raceGeminiGeneration(env,url,{body:'{}',signal:AbortSignal.timeout(1500)},response=>response.json())).resolves.toEqual({data:{project:'tertiary'},keySlot:'tertiary'});
 expect(fetcher.mock.calls.map(call=>used(call[1])).sort()).toEqual([env.GEMINI_API_KEY_SECONDARY,env.GEMINI_API_KEY_TERTIARY].sort());
 expect(quota.acquireGeminiBudget).toHaveBeenCalledTimes(3);
 await vi.waitFor(()=>expect(releaseGeminiBudget).toHaveBeenCalledWith(owners[1],'gemini-4-hydrogen',owners[1]));
});
it('uses a definitive error without retrying the third project or cancelling the error body',async()=>{
 const env={...credentials(),GEMINI_API_KEY_TERTIARY:'owned-third-'+crypto.randomUUID()};
 vi.spyOn(Math,'random').mockReturnValue(0);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async (_url,init)=>{
  if(used(init)===env.GEMINI_API_KEY) return Response.json({error:{code:400}},{status:400});
  const signal=init!.signal!;
  return new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));
 });
 const result=await raceGeminiGeneration(env,url,{body:'{}',signal:AbortSignal.timeout(1500)},response=>response.json());
 expect('response' in result && result.response.status).toBe(400);
 if('response' in result) await expect(result.response.json()).resolves.toEqual({error:{code:400}});
 expect(fetcher.mock.calls.map(call=>used(call[1]))).not.toContain(env.GEMINI_API_KEY_TERTIARY);
});
it('uses a secondary-only configuration and deduplicates accidental identical keys',async()=>{
 const env=credentials();expect(hasGeminiCredentials({GEMINI_API_KEY_SECONDARY:env.GEMINI_API_KEY_SECONDARY})).toBe(true);
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({ok:true}));
 await fetchGemini({GEMINI_API_KEY_SECONDARY:env.GEMINI_API_KEY_SECONDARY},url,{});
 expect(used(fetcher.mock.calls[0][1])).toBe(env.GEMINI_API_KEY_SECONDARY);
 fetcher.mockClear();fetcher.mockImplementation(async()=>new Response(null,{status:429}));
 await fetchGemini({GEMINI_API_KEY:env.GEMINI_API_KEY,GEMINI_API_KEY_SECONDARY:env.GEMINI_API_KEY},url,{});
 expect(fetcher).toHaveBeenCalledTimes(1);
 expect(redactGeminiKeys(env,env.GEMINI_API_KEY+' '+env.GEMINI_API_KEY_SECONDARY)).toBe('[key] [key]');
});
