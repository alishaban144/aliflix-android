import { env, runInDurableObject, runDurableObjectAlarm } from 'cloudflare:test';
import { describe, it, expect, vi } from 'vitest';
import { issueIdentity, verifyIdentity, watchPartyRoute } from '../src/watchParty';
import type { RecommendationEnv } from '../src/types';

const content={id:70523,type:'tv' as const,season:1,episode:1,title:'Dark',poster:'',language:'de',year:'2017',imdbId:'tt5753856',genres:['Drama']};
const hash=async(value:string)=>btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
async function room(){const stub=env.WATCH_PARTIES.getByName(crypto.randomUUID());await stub.create('host',content,'Host',await hash('invite'),10000);return stub;}
async function joinSocket(stub:Awaited<ReturnType<typeof room>>,id:string,digest:string,name:string){return stub.fetch(new Request('https://party/socket',{headers:{'Upgrade':'websocket','x-party-identity':id,'x-party-invite-hash':digest,'x-party-name':name}}));}
async function connect(stub:Awaited<ReturnType<typeof room>>,id:string,invite='invite'){
 const response=await joinSocket(stub,id,await hash(invite),id);expect(response.status).toBe(101);
 const ws=response.webSocket!;ws.accept(); const messages:Record<string,unknown>[]=[];
 ws.addEventListener('message',event=>{messages.push(JSON.parse(event.data as string));});
 return {ws,messages};
}
async function state(stub:Awaited<ReturnType<typeof room>>){return runInDurableObject(stub,(_instance,ctx)=>JSON.parse(ctx.storage.sql.exec<{value:string}>('SELECT value FROM room').one().value));}
async function send(socket:WebSocket,event:unknown,stub:Awaited<ReturnType<typeof room>>){socket.send(JSON.stringify(event));await new Promise(resolve=>setTimeout(resolve,20));return state(stub);}

describe('Watch Party protocol and security',()=>{
 it('authenticates signed temporary identities and rejects tampering, expiry and wrong keys',async()=>{
  const token=await issueIdentity({sub:'guest:one',kind:'guest',exp:Date.now()+10000},'test-key');
  expect((await verifyIdentity(token,'test-key')).sub).toBe('guest:one');
  await expect(verifyIdentity(token+'x','test-key')).rejects.toThrow();
  await expect(verifyIdentity(token,'other-key')).rejects.toThrow();
  const expired=await issueIdentity({sub:'guest:one',kind:'guest',exp:Date.now()-1},'test-key');
  await expect(verifyIdentity(expired,'test-key')).rejects.toThrow();
 });
 it('keeps private invite hashes out of snapshots and rejects unknown or invalid rooms',async()=>{
  const stub=await room();expect((await joinSocket(stub,'intruder',await hash('wrong'),'No')).status).toBe(403);
  const host=await connect(stub,'host');const guest=await connect(stub,'guest');
  host.ws.send(JSON.stringify({type:'ping',sent:12}));await new Promise(r=>setTimeout(r,20));
  expect(JSON.stringify(host.messages)).not.toContain('inviteHash');
  expect((await state(stub)).members).toHaveLength(2);host.ws.close();guest.ws.close();
 });
 it('enforces independent play/pause/seek permissions, rejects duplicates and stale playback events',async()=>{
  const stub=await room();const host=await connect(stub,'host');const guest=await connect(stub,'guest');
  let r=await state(stub);
  r=await send(guest.ws,{type:'control',action:'seek',position:200000,sequence:1,version:r.playbackVersion},stub);expect(r.position).toBe(10000);
  r=await send(host.ws,{type:'permission',member:'guest',rights:{play:false,pause:false,seek:true}},stub);
  r=await send(guest.ws,{type:'control',action:'seek',position:200000,sequence:2,version:r.playbackVersion},stub);expect(r.position).toBe(200000);
  r=await send(guest.ws,{type:'control',action:'seek',position:400000,sequence:2,version:r.playbackVersion},stub);expect(r.position).toBe(200000);
  r=await send(host.ws,{type:'control',action:'play',position:0,sequence:1,version:r.playbackVersion},stub);expect(r.playing).toBe(true);expect(r.position).toBeGreaterThanOrEqual(200000);
  r=await send(guest.ws,{type:'control',action:'pause',position:0,sequence:3,version:r.playbackVersion},stub);expect(r.playing).toBe(true);
  r=await send(host.ws,{type:'control',action:'seek',position:500000,sequence:2,version:1},stub);expect(r.position).toBeLessThan(500000);
  host.ws.close();guest.ws.close();
 });
 it('preserves persisted admission and reconnects an admitted guest after invitation expiry',async()=>{
  const stub=await room();const host=await connect(stub,'host');const guest=await connect(stub,'guest');
  await runInDurableObject(stub,(_instance,ctx)=>{const r=JSON.parse(ctx.storage.sql.exec<{value:string}>('SELECT value FROM room').one().value);r.inviteExpires=0;ctx.storage.sql.exec('UPDATE room SET value=?',JSON.stringify(r));});
  expect((await joinSocket(stub,'new',await hash('invite'),'New')).status).toBe(403);
  const reconnect=await connect(stub,'guest','wrong');expect((await state(stub)).members).toHaveLength(2);
  host.ws.close();guest.ws.close();reconnect.ws.close();
 });
 it('transfers host ownership, restricts episode identity and ends rooms authoritatively',async()=>{
  const stub=await room();const host=await connect(stub,'host');const guest=await connect(stub,'guest');
  let r=await send(host.ws,{type:'transfer',member:'guest'},stub);expect(r.host).toBe('guest');
  r=await send(host.ws,{type:'end'},stub);expect(r.ended).toBe(false);
  r=await send(guest.ws,{type:'episode',content:{...content,id:999},sequence:1,version:r.playbackVersion},stub);expect(r.content.id).toBe(content.id);
  r=await send(guest.ws,{type:'episode',content:{...content,episode:2},sequence:2,version:r.playbackVersion},stub);expect(r.content.episode).toBe(2);expect(r.position).toBe(0);expect(r.playing).toBe(false);
  r=await send(guest.ws,{type:'end'},stub);expect(r.ended).toBe(true);expect((await joinSocket(stub,'host',await hash('invite'),'Host')).status).toBe(410);
  host.ws.close();guest.ws.close();
 });
 it('bounds rooms and expires persistent state through cleanup alarms',async()=>{
  const stub=await room();const clients=[];for(let i=0;i<7;i++)clients.push(await connect(stub,'guest'+i));
  expect((await joinSocket(stub,'overflow',await hash('invite'),'Overflow')).status).toBe(409);
  await runInDurableObject(stub,(_instance,ctx)=>{const r=JSON.parse(ctx.storage.sql.exec<{value:string}>('SELECT value FROM room').one().value);r.expires=0;ctx.storage.sql.exec('UPDATE room SET value=?',JSON.stringify(r));});
  await runDurableObjectAlarm(stub);
  expect((await joinSocket(stub,'host',await hash('invite'),'Host')).status).toBe(410);for(const c of clients)c.ws.close();
 });
 it('rejects provider URLs, cookies and credentials from the room content protocol',async()=>{
  const partyEnv:RecommendationEnv={...env,CURSOR_SIGNING_SECRET:'test',RECOMMENDATION_RATE_LIMITER:{limit:async()=>({success:true})}};
  const token=await issueIdentity({sub:'guest:test',kind:'guest',exp:Date.now()+10000},'test');
  const response=await watchPartyRoute(new Request('https://test/v1/party/rooms',{method:'POST',headers:{authorization:'Bearer '+token},body:JSON.stringify({content:{...content,url:'https://provider/video',cookie:'session'},name:'Host'})}),partyEnv);
  expect(response?.status).toBe(400);
 });
 it('allows individual restrictions after everyone controls is enabled',async()=>{
  const stub=await room();const host=await connect(stub,'host');const guest=await connect(stub,'guest');
  let r=await send(host.ws,{type:'policy',everyone:true},stub);
  r=await send(guest.ws,{type:'control',action:'play',position:0,sequence:1,version:r.playbackVersion},stub);expect(r.playing).toBe(true);
  r=await send(host.ws,{type:'permission',member:'guest',rights:{play:false,pause:false,seek:true}},stub);
  r=await send(guest.ws,{type:'control',action:'pause',position:0,sequence:2,version:r.playbackVersion},stub);expect(r.playing).toBe(true);
  r=await send(guest.ws,{type:'control',action:'seek',position:123456,sequence:3,version:r.playbackVersion},stub);expect(r.position).toBe(123456);
  host.ws.close();guest.ws.close();
 });
 it('transfers a disconnected host after grace and retains their secure reconnect admission',async()=>{
  const stub=await room();const host=await connect(stub,'host');const guest=await connect(stub,'guest');host.ws.close();
  await vi.waitFor(async()=>expect((await state(stub)).members.find((m:{id:string})=>m.id==='host').status).toBe('reconnecting'));
  await runInDurableObject(stub,(_instance,ctx)=>{const r=JSON.parse(ctx.storage.sql.exec<{value:string}>('SELECT value FROM room').one().value);r.members.find((m:{id:string})=>m.id==='host').seen=Date.now()-40000;ctx.storage.sql.exec('UPDATE room SET value=?',JSON.stringify(r));});
  await runDurableObjectAlarm(stub);const r=await state(stub);expect(r.host).toBe('guest');expect(r.members).toHaveLength(2);expect(r.members.find((m:{id:string})=>m.id==='host').rights.play).toBe(false);
  const restored=await connect(stub,'host','wrong');expect((await state(stub)).members).toHaveLength(2);restored.ws.close();guest.ws.close();
 });
 it('retains an owner when the host explicitly leaves while every guest is offline',async()=>{
  const stub=await room();const host=await connect(stub,'host');const guest=await connect(stub,'guest');guest.ws.close();
  await vi.waitFor(async()=>expect((await state(stub)).members.find((m:{id:string})=>m.id==='guest').status).toBe('reconnecting'));
  const r=await send(host.ws,{type:'leave'},stub);expect(r.host).toBe('guest');expect(r.members).toHaveLength(1);expect(r.playing).toBe(false);expect(r.ended).toBe(false);
  const restored=await connect(stub,'guest','wrong');const ended=await send(restored.ws,{type:'end'},stub);expect(ended.ended).toBe(true);restored.ws.close();
 });
 it('keeps an empty disconnected room paused during a five-minute recovery window',async()=>{
  const stub=await room();const host=await connect(stub,'host');host.ws.close();await vi.waitFor(async()=>expect((await state(stub)).members[0].status).toBe('reconnecting'));
  await runInDurableObject(stub,(_instance,ctx)=>{const r=JSON.parse(ctx.storage.sql.exec<{value:string}>('SELECT value FROM room').one().value);r.members[0].seen=Date.now()-40000;r.playing=true;ctx.storage.sql.exec('UPDATE room SET value=?',JSON.stringify(r));});
  await runDurableObjectAlarm(stub);const r=await state(stub);expect(r.ended).toBe(false);expect(r.playing).toBe(false);expect(r.members).toHaveLength(1);
  const restored=await connect(stub,'host','wrong');restored.ws.close();
 });

 it('renews a device-held guest credential without changing ownership after access expiry',async()=>{
  const config:RecommendationEnv={...env,CURSOR_SIGNING_SECRET:'persistent-guest-test',RECOMMENDATION_RATE_LIMITER:{limit:async()=>({success:true})}};
  const issue=async(guestKey:string)=>{const response=await watchPartyRoute(new Request('https://test/v1/party/identity',{method:'POST',body:JSON.stringify({guestKey})}),config);expect(response?.status).toBe(200);return response!.json<{token:string}>();};
  const first=await issue('A'.repeat(43));const owner=await verifyIdentity(first.token,config.CURSOR_SIGNING_SECRET);
  const clock=vi.spyOn(Date,'now').mockReturnValue(Date.now()+48*3600000);
  try{
   await expect(verifyIdentity(first.token,config.CURSOR_SIGNING_SECRET)).rejects.toThrow();
   const renewed=await issue('A'.repeat(43));expect((await verifyIdentity(renewed.token,config.CURSOR_SIGNING_SECRET)).sub).toBe(owner.sub);
   expect((await verifyIdentity((await issue('B'.repeat(43))).token,config.CURSOR_SIGNING_SECRET)).sub).not.toBe(owner.sub);
   const malformed=await watchPartyRoute(new Request('https://test/v1/party/identity',{method:'POST',body:JSON.stringify({guestKey:owner.sub})}),config);expect(malformed?.status).toBe(400);
  }finally{clock.mockRestore();}
 });

 it('verifies Firebase account signatures, audience and issuer before issuing a session identity',async()=>{
  const keys=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
  if(!('publicKey' in keys))throw Error('Expected RSA key pair');
  const jwk={...await crypto.subtle.exportKey('jwk',keys.publicKey),kid:'party-test'};
  const encode=(value:unknown)=>btoa(JSON.stringify(value)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
  const header=encode({alg:'RS256',kid:'party-test'});
  const claims={aud:'aliflix-e6332',iss:'https://securetoken.google.com/aliflix-e6332',sub:'account-user',exp:Math.floor(Date.now()/1000)+3600,auth_time:Math.floor(Date.now()/1000)-10};
  const signed=async(value:unknown)=>{const payload=header+'.'+encode(value);const sig=new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',keys.privateKey,new TextEncoder().encode(payload)));return payload+'.'+btoa(String.fromCharCode(...sig)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');};
  const fetch=vi.spyOn(globalThis,'fetch').mockImplementation(async(input)=>{expect(String(input)).toBe('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com');return Response.json({keys:[jwk]});});
  try{
   const partyEnv:RecommendationEnv={...env,CURSOR_SIGNING_SECRET:'account-test',RECOMMENDATION_RATE_LIMITER:{limit:async()=>({success:true})}};
   const request=async(token:string)=>watchPartyRoute(new Request('https://test/v1/party/identity',{method:'POST',body:JSON.stringify({idToken:token})}),partyEnv);
   const response=await request(await signed(claims));expect(response?.status).toBe(200);const body=await response!.json<{token:string}>();const identity=await verifyIdentity(body.token,'account-test');expect(identity.kind).toBe('account');expect(identity.sub.startsWith('account:'+await hash('account-user')+':')).toBe(true);
   expect((await request(await signed({...claims,aud:'other-app'})))?.status).toBe(400);
   expect((await request(await signed({...claims,iss:'https://attacker.invalid'})))?.status).toBe(400);
   expect((await request(await signed({...claims,exp:0})))?.status).toBe(400);
   const token=await signed(claims);expect((await request(token.slice(0,40)+'x'+token.slice(41)))?.status).toBe(400);
  }finally{fetch.mockRestore();}
 });

});
