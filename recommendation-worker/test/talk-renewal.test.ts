import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,expect,it,vi} from 'vitest';
import {TalkSession} from '../src/talk';
import {TalkContextSchema} from '../src/talkEvidence';
afterEach(()=>vi.restoreAllMocks());
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
