import {fetchGemini} from './geminiKeys';
import {estimateGeminiInput} from './geminiQuota';
import {TALK_MODEL} from './talkProviders';
import type {RecommendationEnv} from './types';

/** A single bounded tool return, not an audible second conversation. Free-tier
 * scene analysis uses the working Live API instead of retrying overloaded REST
 * generation. No model-generated speech is forwarded to the viewer. */
export async function geminiLiveStructured(env:RecommendationEnv,instruction:string,parts:unknown[],schema:unknown):Promise<unknown>{
 const deadline=AbortSignal.timeout(12000);
 const response=await fetchGemini(env,'https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent',{
  headers:{Upgrade:'websocket'},signal:deadline,
  geminiInputTokens:estimateGeminiInput(JSON.stringify({instruction,parts,schema})),
 });
 const socket=response.webSocket;
 if(!socket){await response.body?.cancel();throw Error('SCENE_HTTP_'+response.status);}
 // Live tool schemas use the OpenAPI type names, not responseJsonSchema.
 const toolSchema=(value:unknown):unknown=>{
  if(Array.isArray(value))return value.map(toolSchema);
  if(!value||typeof value!=='object')return value;
  return Object.fromEntries(Object.entries(value).map(([key,item])=>[key,key==='type'&&typeof item==='string'?item.toUpperCase():toolSchema(item)]));
 };
 return new Promise((resolve,reject)=>{
  let settled=false,bytes=0;
  const finish=(value?:unknown,error?:unknown)=>{
   if(settled)return;settled=true;deadline.removeEventListener('abort',aborted);
   socket.removeEventListener('message',message);socket.removeEventListener('close',closed);socket.removeEventListener('error',closed);
   try{socket.close(1000,'Scene complete');}catch{}
   if(error)reject(error);else resolve(value);
  };
  const aborted=()=>finish(undefined,deadline.reason);
  const closed=()=>finish(undefined,Error('SCENE_UNAVAILABLE'));
  const message=async(event:MessageEvent)=>{try{
   const raw=typeof event.data==='string'?event.data:event.data instanceof Blob?await event.data.text():new TextDecoder().decode(event.data as ArrayBuffer);
   if(settled)return;bytes+=raw.length;if(bytes>128000)throw Error('RESPONSE_LIMIT');
   const packet=JSON.parse(raw);if(packet.error)throw Error('SCENE_HTTP_'+(packet.error.code||503));
   if(packet.setupComplete)socket.send(JSON.stringify({clientContent:{turns:[{role:'user',parts}],turnComplete:true}}));
   for(const call of packet.toolCall?.functionCalls||[])if(call.name==='compile_scene_memory'){finish(call.args);return;}
   // No repeated nudges/generation loops and no unvalidated prose fallback.
   if(packet.serverContent?.turnComplete)closed();
  }catch(error){finish(undefined,error);}};
  socket.addEventListener('message',message);socket.addEventListener('close',closed);socket.addEventListener('error',closed);
  deadline.addEventListener('abort',aborted,{once:true});socket.accept();
  if(deadline.aborted){aborted();return;}
  socket.send(JSON.stringify({setup:{model:`models/${TALK_MODEL}`,
   generationConfig:{responseModalities:['AUDIO'],maxOutputTokens:3072},
   systemInstruction:{parts:[{text:instruction+' Return the compiled result by calling compile_scene_memory exactly once. Do not speak or produce audio. No extra tools or lookups.'}]},
   tools:[{functionDeclarations:[{name:'compile_scene_memory',description:'Return the evidence-linked scene memory and premise corrections.',behavior:'BLOCKING',parameters:toolSchema(schema)}]}],
  }}));
 });
}
