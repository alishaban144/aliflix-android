export type GeminiBudgetState={recent:Array<{at:number;tokens:number}>;active?:{id:string;until:number};blockedUntil?:number;status?:number};
export type GeminiBudgetGrant={allowed:boolean;lease?:string;retryAt?:number;status?:number};
export function reserveGeminiBudget(state:GeminiBudgetState,now:number,tokens:number,rpm:number,tpm:number,lease:string):GeminiBudgetGrant {
 state.recent=state.recent.filter(item=>item.at>now-60000);
 if((state.blockedUntil||0)>now)return {allowed:false,retryAt:state.blockedUntil,status:state.status||429};
 if(state.active&&state.active.until>now)return {allowed:false,retryAt:now+1000,status:429};
 if(state.recent.length>=rpm||state.recent.length>0&&state.recent.reduce((sum,item)=>sum+item.tokens,0)+tokens>tpm)
  return {allowed:false,retryAt:state.recent[0].at+60000,status:429};
 // One valid large request may use a window; subsequent requests wait rather
 // than silently dropping the scene evidence or retrying it indefinitely.
 state.recent.push({at:now,tokens});state.active={id:lease,until:now+30000};return {allowed:true,lease};
}
export function nextPacificMidnight(now:number){
 const format=new Intl.DateTimeFormat('en-US',{timeZone:'America/Los_Angeles',year:'numeric',month:'numeric',day:'numeric',hour:'numeric',minute:'numeric',second:'numeric',hourCycle:'h23'});
 const parts=(at:number)=>Object.fromEntries(format.formatToParts(new Date(at)).filter(p=>p.type!=='literal').map(p=>[p.type,Number(p.value)]));
 const day=parts(now),target=Date.UTC(day.year,day.month-1,day.day+1);let at=target+8*3600000;
 for(let n=0;n<3;n++){const p=parts(at);at+=target-Date.UTC(p.year,p.month-1,p.day,p.hour,p.minute,p.second);}
 return at;
}
export function estimateGeminiInput(body:BodyInit|null|undefined):number {
 if(typeof body!=='string')return 0;
 try {
  const count=(value:unknown):number=>{
   if(typeof value==='string')return Math.ceil(value.length/2);
   if(Array.isArray(value))return value.reduce((sum,item)=>sum+count(item),0);
   if(value&&typeof value==='object'){
    const data=value as Record<string,unknown>;
    if(data.inlineData)return 1024;
    return Object.values(data).reduce<number>((sum,item)=>sum+count(item),0);
   }
   return 0;
  };
  return count(JSON.parse(body));
 }catch{return Math.ceil(body.length/2);}
}
export async function geminiCooldown(response:Response,now=Date.now()){
 let detail:unknown={};const reader=response.clone().body?.getReader();
 if(reader)try{const chunks:Uint8Array[]=[];let bytes=0;
  while(true){const part=await reader.read();if(part.done)break;bytes+=part.value.length;if(bytes>65536){await reader.cancel();break;}chunks.push(part.value);}
  if(bytes<=65536){const all=new Uint8Array(bytes);let at=0;for(const chunk of chunks){all.set(chunk,at);at+=chunk.length;}detail=JSON.parse(new TextDecoder().decode(all));}
 }catch{}finally{reader.releaseLock();}
 const error=(detail as {error?:{message?:string;details?:Array<{retryDelay?:string;violations?:Array<{quotaId?:string;quotaMetric?:string;description?:string}>}>}})?.error;
 const daily=response.status===429&&/per.?day|daily/i.test([error?.message,...(error?.details||[]).flatMap(d=>(d.violations||[]).map(v=>[v.quotaId,v.quotaMetric,v.description].join(' ')))].join(' '));
 const retry=response.headers.get('retry-after');
 const header=retry&&/^\d+(?:\.\d+)?$/.test(retry)?Number(retry):retry?(Date.parse(retry)-now)/1000:0;
 const info=Math.max(0,...(error?.details||[]).map(d=>Number(/^(\d+(?:\.\d+)?)s$/.exec(d.retryDelay||'')?.[1])||0));
 const fallback=response.status===429?60:[401,403].includes(response.status)?300:2;
 return {until:daily?nextPacificMidnight(now):now+Math.min(3600,Math.max(1,header||0,info||0,header||info?0:fallback))*1000,status:response.status};
}
