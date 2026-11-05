import {afterEach,expect,it,vi} from 'vitest';
import {fetchGemini,geminiProjectRequest,hasGeminiCredentials,redactGeminiKeys} from '../src/geminiKeys';
const url='https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent';
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
 await fetchGemini(env,url.replace('gemini-3.8-flash:generateContent','gemini-embedding-2:embedContent'),{});
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
