import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,expect,it,vi} from 'vitest';
import {TalkSession} from '../src/talk';
import {TalkContextSchema} from '../src/talkEvidence';
import type {RecommendationEnv} from '../src/types';
afterEach(()=>vi.restoreAllMocks());
it('rotates late-quota sessions across remaining projects without replacing conversation or budgets',async()=>{
 await runInDurableObject(env.TALK_SESSIONS.getByName('late-quota-'+crypto.randomUUID()),async(_,state)=>{
  const keys=['primary-','secondary-','tertiary-'].map(prefix=>prefix+crypto.randomUUID());
  const service=new TalkSession(state,{...env,CURSOR_SIGNING_SECRET:'test-only-quota-rotation',GEMINI_API_KEY:keys[0],GEMINI_API_KEY_SECONDARY:keys[1],GEMINI_API_KEY_TERTIARY:keys[2]} as RecommendationEnv);
  const id=crypto.randomUUID(),expires=Date.now()+120000;
  state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+id,JSON.stringify({keySlot:'primary',context:TalkContextSchema.parse({voice:'Aoede',responseLanguage:'en'}),expires,calls:7,searches:1,grants:['retained-proof']}),expires);
  vi.spyOn(Math,'random').mockReturnValue(0);
  const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({name:'auth_tokens/owned-ephemeral'}));
  const first=await service.renew(id,'en',true),second=await service.renew(id,'en',true);
  expect(first.session).toBe(id);expect(second.session).toBe(id);expect(second).not.toHaveProperty('keySlot');
  expect(fetcher.mock.calls.map(call=>new Headers(call[1]?.headers).get('x-goog-api-key'))).toEqual([keys[1],keys[2]]);
  await expect(service.renew(id,'en',true)).rejects.toThrow('LIVE_UNAVAILABLE');expect(fetcher).toHaveBeenCalledTimes(2);
  const raw=state.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=?','live:'+id).one().value;
  expect(JSON.parse(raw)).toMatchObject({keySlot:'tertiary',expires,calls:7,searches:1,grants:['retained-proof'],context:{voice:'Aoede',responseLanguage:'en'}});
  for(const key of keys)expect(raw).not.toContain(key);
 });
});
it('keeps the successful failover project private and pinned across voice renewal',async()=>{
 await runInDurableObject(env.TALK_SESSIONS.getByName('renew-key-affinity-'+crypto.randomUUID()),async(_,state)=>{
  const primary='primary-test-'+crypto.randomUUID(),secondary='secondary-test-'+crypto.randomUUID();
  const service=new TalkSession(state,{...env,CURSOR_SIGNING_SECRET:'test-only-signing-secret-not-production',GEMINI_API_KEY:primary,GEMINI_API_KEY_SECONDARY:secondary} as RecommendationEnv);
  vi.spyOn(Math,'random').mockReturnValue(0);
  const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response(null,{status:429}))
   .mockImplementation(async()=>Response.json({name:'auth_tokens/test-ephemeral'}));
  const opened=await service.open(TalkContextSchema.parse({voice:'Aoede',responseLanguage:'en'}));
  expect(opened).not.toHaveProperty('keySlot');await service.renew(opened.session);
  expect(fetcher.mock.calls.map(call=>new Headers(call[1]?.headers).get('x-goog-api-key'))).toEqual([primary,secondary,secondary]);
  const stored=state.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=?','live:'+opened.session).one().value;
  expect(JSON.parse(stored).keySlot).toBe('secondary');expect(stored).not.toContain(primary);expect(stored).not.toContain(secondary);
 });
});
it('renews the same owner session with pinned voice, original expiry and unchanged spoiler/search budgets',async()=>{
 await runInDurableObject(env.TALK_SESSIONS.getByName('renew-identity-test'),async(instance,state)=>{
  const service=instance as unknown as TalkSession,e=(instance as any).env,old=e.GEMINI_API_KEY;e.GEMINI_API_KEY='test-only';
  const session=crypto.randomUUID(),expires=Date.now()+120000;
  state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({voice:'Aoede',responseLanguage:'en'}),expires,calls:9,searches:2,grants:['film-1980']}),expires);
  const spy=vi.spyOn(globalThis,'fetch').mockImplementation(async()=>Response.json({name:'auth_tokens/test-ephemeral'}));
  try{
   expect(await service.renew(session,'de')).toMatchObject({session,expires,voice:'Aoede',model:'gemini-3.8-live'});
   const stored=JSON.parse(state.storage.sql.exec<{value:string}>('SELECT value FROM memory WHERE key=?','live:'+session).one().value);
   expect(stored).toMatchObject({expires,calls:9,searches:2,grants:['film-1980'],renewals:1,context:{voice:'Aoede',responseLanguage:'de'}});
   for(let i=0;i<5;i++)await service.renew(session);
   await expect(service.renew(session)).rejects.toThrow('TOOL_LIMIT');expect(spy).toHaveBeenCalledTimes(6);
   const body=JSON.parse((spy.mock.calls[0][1] as RequestInit).body as string);
   expect(body.bidiGenerateContentSetup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName).toBe('Aoede');
   expect(body.uses).toBe(1);expect(Date.parse(body.expireTime)).toBe(expires);
  }finally{e.GEMINI_API_KEY=old;}
 });
});
it('cannot renew another owner, expired session or a session deleted during token issuance',async()=>{
 await runInDurableObject(env.TALK_SESSIONS.getByName('renew-close-race'),async(instance,state)=>{
  const service=instance as unknown as TalkSession,e=(instance as any).env,old=e.GEMINI_API_KEY;e.GEMINI_API_KEY='test-only';
  const session=crypto.randomUUID(),expires=Date.now()+60000;
  try{
   await expect(service.renew(session)).rejects.toThrow('SESSION_EXPIRED');
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context:TalkContextSchema.parse({}),expires,calls:0,searches:0,grants:[]}),expires);
   vi.spyOn(globalThis,'fetch').mockImplementation(async()=>{state.storage.sql.exec('DELETE FROM memory');return Response.json({name:'auth_tokens/test-ephemeral'});});
   await expect(service.renew(session)).rejects.toThrow('SESSION_EXPIRED');
   expect(state.storage.sql.exec<{n:number}>('SELECT COUNT(*) n FROM memory').one().n).toBe(0);
  }finally{e.GEMINI_API_KEY=old;}
 });
});
