import {estimateGeminiInput,geminiCooldown} from './geminiQuota';
import type { RecommendationEnv, SecretBindings } from './types';

export type GeminiKeySlot = 'primary' | 'secondary' | 'tertiary';
type GeminiCredentials = Pick<SecretBindings, 'GEMINI_API_KEY' | 'GEMINI_API_KEY_SECONDARY' | 'GEMINI_API_KEY_TERTIARY'> & Partial<Pick<RecommendationEnv,'TALK_SESSIONS'|'GEMINI_FREE_TIER_MODE'|'GEMINI_FREE_TIER_RPM'|'GEMINI_FREE_TIER_TPM'>>;
type Key = { slot: GeminiKeySlot; value: string };
type Cooling = { until: number; status: number };
type GeminiRequestInit=RequestInit & {geminiInputTokens?:number};

function keys(env: GeminiCredentials): Key[] {
 const result: Key[] = [];
 for (const [slot, value] of [['primary', env.GEMINI_API_KEY], ['secondary', env.GEMINI_API_KEY_SECONDARY], ['tertiary',env.GEMINI_API_KEY_TERTIARY]] as const)
  if (value?.trim() && !result.some(key => key.value === value.trim())) result.push({ slot, value: value.trim() });
 return result;
}
export const hasGeminiCredentials = (env: GeminiCredentials) => keys(env).length > 0;
export const configuredGeminiSlots = (env:GeminiCredentials)=>keys(env).map(key=>key.slot);
export function redactGeminiKeys(env: GeminiCredentials, text: string) {
 for (const key of keys(env)) text = text.replaceAll(key.value, '[key]');
 return text;
}

// Best-effort edge-local cooldowns, scoped to a model/API family. Cache keys
// contain a hash, never a credential. No secret or mutable quota state lives in
// module globals, and a quota error for embeddings does not disable voice.
async function healthRequest(key: Key, url: string) {
 const family = new URL(url).pathname.match(/\/models\/([^/:]+)/)?.[1] || (url.includes('/auth_tokens') ? 'live-token' : 'live-stream');
 const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key.value));
 const id = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
 return new Request(`https://aliflix-provider-health.invalid/gemini/${family}/${id}`);
}
async function cooling(request: Request): Promise<Cooling | undefined> {
 try {
  const response = await caches.default.match(request);
  const value = response && await response.json<Cooling>();
  return value && value.until > Date.now() ? value : undefined;
 } catch { return undefined; }
}
async function cool(request: Request, response: Response) {
 const value=await geminiCooldown(response),duration=Math.max(1,Math.ceil((value.until-Date.now())/1000));
 try { await caches.default.put(request, Response.json(value,
  { headers: { 'cache-control': `max-age=${duration}` } })); } catch {}
 return value;
}

/** Balance independent projects; at most one attempt per distinct key, within
 * the caller's original deadline. A minted Live session can pin its project. */
export async function geminiProjectRequest(env: GeminiCredentials, url: string, init: GeminiRequestInit,
 pinned?: GeminiKeySlot,excluded:GeminiKeySlot[]=[]): Promise<{ response: Response; keySlot?: GeminiKeySlot }> {
 const free=env.GEMINI_FREE_TIER_MODE==='true';
 if(free&&!env.TALK_SESSIONS)throw Error('GEMINI_BUDGET_UNAVAILABLE');
 const quota=free?env.TALK_SESSIONS!.getByName('gemini-project-budget-v1'):undefined;
 const {geminiInputTokens,...requestInit}=init;
 const cost=geminiInputTokens??estimateGeminiInput(init.body),rpm=Math.max(1,Math.min(20,Number(env.GEMINI_FREE_TIER_RPM)||3)),tpm=Math.max(1024,Number(env.GEMINI_FREE_TIER_TPM)||32000);
 const pool = keys(env), trackHealth = pool.length > 1||free;
 let candidates = pool.filter(key=>!excluded.includes(key.slot));
 if (pinned) candidates = candidates.filter(key => key.slot === pinned);
 else if (candidates.length > 1) {
  const start=Math.floor(Math.random()*candidates.length);
  candidates=[...candidates.slice(start),...candidates.slice(0,start)];
 }
 if (!candidates.length) throw Error('GEMINI_NOT_CONFIGURED');
 const unavailable: Cooling[] = [];
 for (let index = 0; index < candidates.length; index++) {
  init.signal?.throwIfAborted();
  const key = candidates[index], health = trackHealth ? await healthRequest(key, url) : undefined;
  const state = health ? await cooling(health) : undefined;
  if (state) { unavailable.push(state); continue; }
  const pieces=health?.url.split('/'),owner=pieces?.at(-1)||'',scope=pieces?.at(-2)||'';
  let lease:string|undefined;
  if(quota){
   const grant=await quota.acquireGeminiBudget(owner,scope,scope==='live-token'?0:cost,scope==='live-token'?rpm*2:rpm,tpm);
   if(!grant.allowed){unavailable.push({until:grant.retryAt||Date.now()+1000,status:grant.status||429});continue;}
   lease=grant.lease;
  }
  const headers = new Headers(init.headers); headers.set('x-goog-api-key', key.value);
  let response: Response;
  try { response = await fetch(url, { ...requestInit, headers }); }
  catch (error) {
   if (init.signal?.aborted || index === candidates.length - 1) throw error;
   continue;
  }finally{if(quota&&lease)await quota.releaseGeminiBudget(owner,scope,lease).catch(()=>undefined);}
  const retryable = [401, 403, 408, 429].includes(response.status) || response.status >= 500;
  if (!retryable) return { response, keySlot: key.slot };
  const blocked=health?await cool(health,response):undefined;
  if(quota&&blocked)await quota.blockGeminiBudget(owner,scope,blocked.until,blocked.status);
  if (index === candidates.length - 1) return { response, keySlot: key.slot };
  await response.body?.cancel().catch(() => undefined);
  unavailable.push(blocked||{ until: Date.now() + 2000, status: response.status });
 }
 const until = Math.min(...unavailable.map(value => value.until));
 const status = unavailable.every(value => value.status === 429) ? 429 : 503;
 return { response: Response.json({ error: { code: status, status: status === 429 ? 'RESOURCE_EXHAUSTED' : 'UNAVAILABLE',
  message: 'Gemini project capacity is temporarily unavailable.' } },
  { status, headers: { 'retry-after': String(Math.max(1, Math.ceil((until - Date.now()) / 1000))) } }) };
}

export async function fetchGemini(env: GeminiCredentials, url: string, init: GeminiRequestInit) {
 return (await geminiProjectRequest(env, url, init)).response;
}

/** Only server-observed provider errors may cool a shared project. Client
 * rotation hints stay private to that conversation in TalkSession. */
export async function markGeminiUnavailable(env:GeminiCredentials,slot:GeminiKeySlot,url:string,status:number){
 const key=keys(env).find(key=>key.slot===slot);if(!key)return;
 const request=await healthRequest(key,url),blocked=await cool(request,Response.json({error:{message:'Live provider capacity unavailable'}},{status}));
 if(env.GEMINI_FREE_TIER_MODE==='true'&&env.TALK_SESSIONS){
  const parts=request.url.split('/');
  await env.TALK_SESSIONS.getByName('gemini-project-budget-v1').blockGeminiBudget(parts.at(-1)!,parts.at(-2)!,blocked.until,blocked.status);
 }
}
