import { DurableObject } from 'cloudflare:workers';
import { z } from 'zod';
import type { RecommendationEnv } from './types';

const HOUR = 3_600_000;
const ROOM_TTL = 8 * HOUR;
const MAX_MEMBERS = 8;
const identitySchema = z.object({ sub: z.string().min(1).max(128), exp: z.number(), kind: z.enum(['guest', 'account']) });
type Identity = z.infer<typeof identitySchema>;
const contentSchema = z.object({ id: z.number().int().positive().max(999999999), type: z.enum(['movie','tv']),
  season: z.number().int().min(0).max(10000).default(1), episode: z.number().int().min(1).max(10000).default(1),
  title: z.string().min(1).max(200), poster: z.string().max(240).default(''), language: z.string().max(8).default(''), year: z.string().max(10).default('') }).strict();
type Content = z.infer<typeof contentSchema>;
const rights = z.object({ play: z.boolean(), pause: z.boolean(), seek: z.boolean() }).strict();
type Rights = z.infer<typeof rights>;
type Member = { id: string; name: string; rights: Rights; status: string; joined: number; seen: number; sequence: number; connection: string };
type Room = { protocol: 1; content: Content; host: string; everyone: boolean; members: Member[]; version: number; playbackVersion: number;
  position: number; playing: boolean; at: number; created: number; expires: number; inviteHash: string; inviteExpires: number; ended: boolean };
const eventSchema = z.discriminatedUnion('type', [
  z.object({type:z.literal('ping'), sent:z.number().finite()}),
  z.object({type:z.literal('control'), action:z.enum(['play','pause','seek']), position:z.number().finite().min(0).max(604800000), sequence:z.number().int().positive(), version:z.number().int().nonnegative()}),
  z.object({type:z.literal('status'), status:z.enum(['waiting','ready','playing','paused','buffering','source-unavailable','desynchronized']), sequence:z.number().int().positive()}),
  z.object({type:z.literal('policy'), everyone:z.boolean()}),
  z.object({type:z.literal('permission'), member:z.string().max(128), rights}),
  z.object({type:z.literal('transfer'), member:z.string().max(128)}),
  z.object({type:z.literal('episode'), content:contentSchema, sequence:z.number().int().positive(), version:z.number().int().nonnegative()}),
  z.object({type:z.literal('invite')}), z.object({type:z.literal('leave')}), z.object({type:z.literal('end')})
]);
const headers = { 'cache-control':'no-store', 'referrer-policy':'no-referrer', 'x-content-type-options':'nosniff' };
const json = (body: unknown, status = 200) => Response.json(body, {status, headers});
const bytes = (s:string) => new TextEncoder().encode(s);
const b64 = (v: Uint8Array) => btoa(String.fromCharCode(...v)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
const unb64 = (v:string) => Uint8Array.from(atob(v.replaceAll('-','+').replaceAll('_','/')), c=>c.charCodeAt(0));
const random = () => b64(crypto.getRandomValues(new Uint8Array(32)));
const hash = async (s:string) => b64(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes(s))));
async function key(secret:string) {
  if (!secret) throw Error('UNAVAILABLE');
  return crypto.subtle.importKey('raw', bytes(secret), {name:'HMAC',hash:'SHA-256'}, false, ['sign','verify']);
}
export async function issueIdentity(identity:Identity, secret:string) {
  const payload = b64(bytes(JSON.stringify(identity)));
  return payload+'.'+b64(new Uint8Array(await crypto.subtle.sign('HMAC',await key(secret),bytes('party-v1.'+payload))));
}
export async function verifyIdentity(token:string, secret:string): Promise<Identity> {
  const [payload, signature, extra] = token.split('.');
  if (!payload || !signature || extra || token.length>2048 || !await crypto.subtle.verify('HMAC',await key(secret),unb64(signature),bytes('party-v1.'+payload))) throw Error('UNAUTHORIZED');
  const value = identitySchema.parse(JSON.parse(new TextDecoder().decode(unb64(payload))));
  if(value.exp <= Date.now()) throw Error('UNAUTHORIZED');
  return value;
}
async function accountIdentity(token:string):Promise<string> {
  if (token.length>8192) throw Error('UNAUTHORIZED');
  const parts=token.split('.'); if(parts.length!==3) throw Error('UNAUTHORIZED');
  const h=JSON.parse(new TextDecoder().decode(unb64(parts[0]))) as {alg:string;kid:string};
  const p=JSON.parse(new TextDecoder().decode(unb64(parts[1]))) as {aud:string;iss:string;sub:string;exp:number;auth_time:number};
  if(h.alg!=='RS256'||p.aud!=='aliflix-e6332'||p.iss!=='https://securetoken.google.com/aliflix-e6332'||!p.sub||p.sub.length>128||p.exp*1000<=Date.now()||p.auth_time*1000>Date.now()+60000) throw Error('UNAUTHORIZED');
  const response=await fetch('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com', {cf:{cacheTtl:3600}});
  if(!response.ok) throw Error('UNAVAILABLE');
  const keys=await response.json<{keys:(JsonWebKey & {kid:string})[]}>();
  const jwk=keys.keys.find(k=>k.kid===h.kid); if(!jwk) throw Error('UNAUTHORIZED');
  const publicKey=await crypto.subtle.importKey('jwk',jwk,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
  if(!await crypto.subtle.verify('RSASSA-PKCS1-v1_5',publicKey,unb64(parts[2]),bytes(parts[0]+'.'+parts[1]))) throw Error('UNAUTHORIZED');
  return 'account:'+p.sub.slice(0,80)+':'+crypto.randomUUID();
}
async function body(request:Request):Promise<unknown> {
  if(Number(request.headers.get('content-length')||0)>4096) throw Error('TOO_LARGE');
  if(!request.body) throw Error('INVALID');
  const reader=request.body.getReader(); let value=''; let size=0; const decoder=new TextDecoder();
  try { while(true) {const r=await reader.read(); if(r.done)break; size+=r.value.length;if(size>4096)throw Error('TOO_LARGE');value+=decoder.decode(r.value,{stream:true});} }
  finally {await reader.cancel();}
  return JSON.parse(value+decoder.decode());
}
export function partyLanding():Response {
  // Fragment stays in the browser/app. No invite credential reaches access logs or the download destination.
  return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Watch Together · Aliflix</title><style>body{background:#08090e;color:#faf7ff;font:18px system-ui;display:grid;place-items:center;min-height:94vh}main{text-align:center;max-width:28rem;padding:24px}a{display:block;background:#b9a2ff;color:#170f28;border-radius:18px;padding:18px;margin:20px 0;text-decoration:none}h1{letter-spacing:-.04em}</style><main><small>ALIFLIX</small><h1>Watch Together</h1><a id="open">Open Aliflix</a><a href="https://github.com/alishaban144/aliflix-android/releases/latest">Get Aliflix</a></main><script>const u=new URL(location.href);document.querySelector('#open').href='intent://'+u.host+u.pathname+'#Intent;scheme=https;package=com.aliflix.app;S.partyInvite='+u.hash.slice(1)+';S.browser_fallback_url='+encodeURIComponent('https://github.com/alishaban144/aliflix-android/releases/latest')+';end';</script></html>`,{headers:{...headers,'content-type':'text/html; charset=utf-8','content-security-policy':"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"}});
}
export async function watchPartyRoute(request:Request, env:RecommendationEnv):Promise<Response|null> {
  const url=new URL(request.url);
  if(url.pathname==='/.well-known/assetlinks.json') return json([{relation:['delegate_permission/common.handle_all_urls'],target:{namespace:'android_app',package_name:'com.aliflix.app',sha256_cert_fingerprints:['A7:1E:F1:74:C3:13:85:C1:9F:26:00:27:16:1E:C2:55:AD:27:2D:DB:D7:DF:12:41:A2:70:29:E8:67:6F:3A:47']}}]);
  if(/^\/party\/[a-f0-9]{32}$/.test(url.pathname)&&request.method==='GET')return partyLanding();
  if(!url.pathname.startsWith('/v1/party/'))return null;
  try {
    const rate=await env.RECOMMENDATION_RATE_LIMITER.limit({key:'party:'+ (request.headers.get('cf-connecting-ip')||'unknown')});
    if(!rate.success)return json({error:'RATE_LIMITED'},429);
    if(url.pathname==='/v1/party/identity'&&request.method==='POST') {
      const input=z.object({idToken:z.string().max(8192).optional()}).strict().parse(await body(request));
      const identity:Identity={sub:input.idToken?await accountIdentity(input.idToken):'guest:'+crypto.randomUUID(),kind:input.idToken?'account':'guest',exp:Date.now()+24*HOUR};
      return json({token:await issueIdentity(identity,env.CURSOR_SIGNING_SECRET),expires:identity.exp});
    }
    const identity=await verifyIdentity(request.headers.get('authorization')?.replace(/^Bearer /,'')||'',env.CURSOR_SIGNING_SECRET);
    if(url.pathname==='/v1/party/rooms'&&request.method==='POST') {
      const input=z.object({content:contentSchema,name:z.string().trim().min(1).max(32),position:z.number().min(0).max(604800000).default(0)}).strict().parse(await body(request));
      const room=crypto.randomUUID().replaceAll('-',''); const invite=random();
      await env.WATCH_PARTIES.getByName(room).create(identity.sub,input.content,input.name,await hash(invite),input.position);
      return json({room,invite,link:url.origin+'/party/'+room+'#'+invite},201);
    }
    const match=/^\/v1\/party\/rooms\/([a-f0-9]{32})\/socket$/.exec(url.pathname);
    if(match && request.method==='GET') {
      if(request.headers.get('upgrade')?.toLowerCase()!=='websocket')return json({error:'UPGRADE_REQUIRED'},426);
      const invite=request.headers.get('x-party-invite')||'';
      if(invite.length>128)return json({error:'INVALID'},400);
      return env.WATCH_PARTIES.getByName(match[1]).fetch(new Request('https://party/socket', {headers:{'Upgrade':'websocket','x-party-identity':identity.sub,'x-party-invite-hash':await hash(invite),'x-party-name':request.headers.get('x-party-name')||'Guest'}}));
    }
    return json({error:'NOT_FOUND'},404);
  }catch(e){return json({error:e instanceof Error&&e.message==='UNAVAILABLE'?'UNAVAILABLE':'INVALID_OR_EXPIRED'}, e instanceof Error&&e.message==='UNAVAILABLE'?503:400);}
}

export class WatchParty extends DurableObject<Env> {
  private lastTime=0;
  constructor(ctx:DurableObjectState,env:Env) {
    super(ctx,env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS room (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)');
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS clock (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)');
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('keepalive','alive'));
  }
  private now(){const saved=this.ctx.storage.sql.exec<{value:number}>('SELECT value FROM clock WHERE id=1').toArray()[0]?.value||0;this.lastTime=Math.max(Date.now(),saved+1,this.lastTime+1);this.ctx.storage.sql.exec('INSERT OR REPLACE INTO clock(id,value) VALUES(1,?)',this.lastTime);return this.lastTime;}
  private read():Room|null {return this.ctx.storage.sql.exec<{value:string}>('SELECT value FROM room WHERE id=1').toArray().map(r=>JSON.parse(r.value) as Room)[0]||null;}
  private save(r:Room){this.ctx.storage.sql.exec('INSERT OR REPLACE INTO room(id,value) VALUES(1,?)',JSON.stringify(r));}
  private snapshot(r:Room){const {inviteHash,inviteExpires,...state}=r;return {type:'state',...state,serverTime:this.now()};}
  private broadcast(r:Room){const payload=JSON.stringify(this.snapshot(r));for(const ws of this.ctx.getWebSockets()){try{ws.send(payload);}catch{}}}
  async create(host:string,content:Content,name:string,inviteHash:string,position:number) {
    if(this.read())throw Error('EXISTS'); const now=this.now();
    const r:Room={protocol:1,content,host,everyone:false,members:[{id:host,name,rights:{play:true,pause:true,seek:true},status:'waiting',joined:now,seen:now,sequence:0,connection:''}],version:1,playbackVersion:1,position,playing:false,at:now,created:now,expires:now+ROOM_TTL,inviteHash,inviteExpires:now+2*HOUR,ended:false};
    this.save(r); await this.ctx.storage.setAlarm(now+30000);
  }
  async fetch(request:Request):Promise<Response> {
    const id=request.headers.get('x-party-identity')||'';
    const inviteHash=request.headers.get('x-party-invite-hash')||'';
    const name=decodeURIComponent(request.headers.get('x-party-name')||'Guest').slice(0,32);
    if(!id||id.length>128)return json({error:'UNAUTHORIZED'},403);
    const r=this.read(); const now=this.now();
    if(!r||r.ended||r.expires<=now)return json({error:'ROOM_ENDED'},410);
    let member=r.members.find(m=>m.id===id);
    if(!member) {
      if(r.inviteExpires<=now||r.inviteHash!==inviteHash)return json({error:'INVITE_EXPIRED'},403);
      if(r.members.length>=MAX_MEMBERS)return json({error:'ROOM_FULL'},409);
      member={id,name:name.trim()||'Guest',rights:{play:false,pause:false,seek:false},status:'waiting',joined:now,seen:now,sequence:0,connection:''};r.members.push(member);
    }
    for(const old of this.ctx.getWebSockets(id)){try{old.close(4001,'Reconnected');}catch{}}
    const pair=new WebSocketPair(); const client=pair[0],server=pair[1];
    member.connection=crypto.randomUUID(); member.seen=now;
    server.serializeAttachment({id,connection:member.connection,bucket:now,count:0});
    this.ctx.acceptWebSocket(server,[id]); r.version++;this.save(r);this.broadcast(r);
    return new Response(null,{status:101,webSocket:client});
  }
  async webSocketMessage(ws:WebSocket,message:string|ArrayBuffer) {
    try {
      if(typeof message!=='string'||message.length>4096){ws.close(1009,'Too large');return;}
      const attachment=ws.deserializeAttachment() as {id:string;connection:string;bucket:number;count:number};
      const now=this.now();if(now-attachment.bucket>=10000){attachment.bucket=now;attachment.count=0;}
      if(++attachment.count>70){ws.close(1008,'Rate limited');return;}ws.serializeAttachment(attachment);
      let r=this.read();if(!r||r.ended||r.expires<=now){ws.close(4004,'Room ended');return;}
      const m=r.members.find(m=>m.id===attachment.id&&m.connection===attachment.connection);if(!m){ws.close(4003,'Not a member');return;}
      const event=eventSchema.parse(JSON.parse(message));m.seen=now;
      if(event.type==='ping'){this.save(r);ws.send(JSON.stringify({type:'pong',sent:event.sent,serverTime:now}));return;}
      const host=m.id===r.host;
      const deny=()=>{ws.send(JSON.stringify({type:'error',error:'PERMISSION_DENIED'}));};
      if(event.type==='control') {
        if(!host&&!r.everyone&&!m.rights[event.action]){deny();return;}
        if(event.sequence<=m.sequence||event.version!==r.playbackVersion){ws.send(JSON.stringify(this.snapshot(r)));return;}
        m.sequence=event.sequence;r.playbackVersion++;
        // play/pause use the authoritative projected clock; only seek supplies a position.
        r.position=event.action==='seek'?event.position:this.position(r,now);r.at=now;
        if(event.action!=='seek')r.playing=event.action==='play';
      } else if(event.type==='status') {
        if(event.sequence<=m.sequence)return;m.sequence=event.sequence;m.status=event.status;
      } else if(event.type==='policy'){if(!host){deny();return;}r.everyone=event.everyone;
      } else if(event.type==='permission'){if(!host){deny();return;}const target=r.members.find(p=>p.id===event.member);if(target)target.rights=event.rights;
      } else if(event.type==='transfer'){if(!host){deny();return;}const target=r.members.find(p=>p.id===event.member&&this.connected(p));if(!target){deny();return;}r.host=target.id;
      } else if(event.type==='episode') {
        if(!host){deny();return;}if(event.sequence<=m.sequence||event.version!==r.playbackVersion)return;
        if(r.content.type!=='tv'||event.content.type!=='tv'||r.content.id!==event.content.id){deny();return;}
        m.sequence=event.sequence;r.playbackVersion++;r.content=event.content;r.position=0;r.playing=false;r.at=now;r.members.forEach(p=>p.status='waiting');
      } else if(event.type==='invite') {
        if(!host){deny();return;}const invite=random();const digest=await hash(invite);r=this.read();if(!r||r.ended||r.host!==m.id){deny();return;}r.inviteHash=digest;r.inviteExpires=now+2*HOUR;ws.send(JSON.stringify({type:'invite',invite}));
      } else if(event.type==='leave'){r.members=r.members.filter(p=>p.id!==m.id);this.transferOrEnd(r,m.id,now);ws.close(1000,'Left');
      } else if(event.type==='end'){if(!host){deny();return;}r.ended=true;r.playing=false;}
      r.version++;this.save(r);this.broadcast(r);
      if(r.ended)for(const s of this.ctx.getWebSockets()){try{s.close(4004,'Room ended');}catch{}}
    }catch{try{ws.send(JSON.stringify({type:'error',error:'INVALID_EVENT'}));}catch{}}
  }
  private connected(m:Member){return this.ctx.getWebSockets(m.id).some(s=>(s.deserializeAttachment() as {connection:string}).connection===m.connection);}
  private position(r:Room,now:number){return Math.min(604800000,r.position+(r.playing?Math.max(0,now-r.at):0));}
  private transferOrEnd(r:Room,departing:string,now:number){
    if(r.host===departing){const next=r.members.filter(m=>m.id!==departing&&this.connected(m)).sort((a,b)=>a.joined-b.joined)[0];if(next)r.host=next.id;else{r.position=this.position(r,now);r.at=now;r.playing=false;}}
    if(!r.members.length)r.ended=true;
  }
  async webSocketClose(ws:WebSocket){await this.disconnected(ws);}
  async webSocketError(ws:WebSocket){try{ws.close(1011,'Disconnected');}catch{}await this.disconnected(ws);}
  private async disconnected(ws:WebSocket){const r=this.read();if(!r)return;const a=ws.deserializeAttachment() as {id:string;connection:string};const m=r.members.find(m=>m.id===a.id&&m.connection===a.connection);if(m){m.status='reconnecting';m.seen=this.now();r.version++;this.save(r);this.broadcast(r);}}
  async alarm(){const r=this.read();if(!r)return;const now=this.now();
    for(const m of r.members){if(!this.connected(m)&&now-m.seen>30000){r.members=r.members.filter(p=>p.id!==m.id);this.transferOrEnd(r,m.id,now);}}
    if(r.expires<=now)r.ended=true;
    if(r.ended){this.broadcast(r);for(const ws of this.ctx.getWebSockets()){try{ws.close(4004,'Room ended');}catch{}}this.ctx.storage.sql.exec('DELETE FROM room');return;}
    r.version++;this.save(r);this.broadcast(r);await this.ctx.storage.setAlarm(now+15000);
  }
}

