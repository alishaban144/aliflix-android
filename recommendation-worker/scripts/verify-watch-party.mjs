// Exercises the public deployment using ephemeral guests; no provider/media URLs enter the protocol.
import assert from 'node:assert/strict';
const base=process.env.ALIFLIX_PARTY_BASE||'https://aliflix-recommendations.equable-equipment.workers.dev';
const request=async(path,body,token)=>{const r=await fetch(base+path,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});assert.ok(r.ok,`${path}: ${r.status}`);return r.json();};
const a=await request('/v1/party/identity',{});const b=await request('/v1/party/identity',{});
const room=await request('/v1/party/rooms',{content:{id:70523,type:'tv',season:1,episode:1,title:'Dark',language:'de',year:'2017'},name:'Release verification'},a.token);
// Node's native WebSocket cannot supply Authorization headers. Use the already-installed ws implementation.
const {default:WebSocket}=await import('ws');
const sockets=[];
async function connect(token){const ws=new WebSocket(base.replace('https','wss')+'/v1/party/rooms/'+room.room+'/socket',{headers:{authorization:'Bearer '+token,'x-party-invite':room.invite,'x-party-name':'Verification'}});sockets.push(ws);let state;const messages=[];ws.on('message',data=>{const m=JSON.parse(data);messages.push(m);if(m.type==='state')state=m;});await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);setTimeout(()=>reject(Error('WebSocket timeout')),15000).unref();});await wait(()=>state);return{ws,messages,state:()=>state};}
async function wait(predicate){for(let i=0;i<100;i++){const value=predicate();if(value)return value;await new Promise(r=>setTimeout(r,50));}throw Error('State timeout');}
try{
 const host=await connect(a.token),guest=await connect(b.token);await wait(()=>host.state()?.members.length===2);
 assert.equal(host.state().content.id,70523);assert.equal(host.state().content.episode,1);assert.ok(!('inviteHash' in host.state()));
 guest.ws.send(JSON.stringify({type:'control',action:'seek',position:12345,sequence:1,version:guest.state().playbackVersion}));await wait(()=>guest.messages.find(m=>m.error==='PERMISSION_DENIED'));
 host.ws.send(JSON.stringify({type:'control',action:'seek',position:30000,sequence:1,version:host.state().playbackVersion}));await wait(()=>guest.state()?.position===30000);
 host.ws.send(JSON.stringify({type:'transfer',member:guest.state().members.find(m=>m.id!==host.state().host).id}));await wait(()=>guest.state()?.host!==host.state().members[0].id);
 guest.ws.send(JSON.stringify({type:'end'}));await wait(()=>host.state()?.ended);
 const assetlinks=await (await fetch(base+'/.well-known/assetlinks.json')).json();assert.equal(assetlinks[0].target.package_name,'com.aliflix.app');
 const landing=await fetch(room.link.split('#')[0]);assert.equal(landing.status,200);assert.equal(landing.headers.get('referrer-policy'),'no-referrer');
 console.log('Live Watch Party: guests, private room, exact episode, two sockets, permissions, authoritative seek, host transfer, end, App Links and HTTPS fallback passed.');
}finally{for(const ws of sockets)ws.close();}
