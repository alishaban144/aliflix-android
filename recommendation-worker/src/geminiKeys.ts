import type { SecretBindings } from './types';

export type GeminiKeySlot = 'primary' | 'secondary';
type GeminiCredentials = Pick<SecretBindings, 'GEMINI_API_KEY' | 'GEMINI_API_KEY_SECONDARY'>;
type Key = { slot: GeminiKeySlot; value: string };
type Cooling = { until: number; status: number };

function keys(env: GeminiCredentials): Key[] {
 const result: Key[] = [];
 for (const [slot, value] of [['primary', env.GEMINI_API_KEY], ['secondary', env.GEMINI_API_KEY_SECONDARY]] as const)
  if (value?.trim() && !result.some(key => key.value === value.trim())) result.push({ slot, value: value.trim() });
 return result;
}
export const hasGeminiCredentials = (env: GeminiCredentials) => keys(env).length > 0;
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
 const retry = response.headers.get('retry-after');
 const seconds = retry && /^\d+(?:\.\d+)?$/.test(retry) ? Number(retry) : retry ? (Date.parse(retry) - Date.now()) / 1000 : NaN;
 const fallback = response.status === 429 ? 30 : [401, 403].includes(response.status) ? 300 : 2;
 const duration = Math.min(300, Math.max(1, Number.isFinite(seconds) ? seconds : fallback));
 try { await caches.default.put(request, Response.json({ until: Date.now() + duration * 1000, status: response.status },
  { headers: { 'cache-control': `max-age=${Math.ceil(duration)}` } })); } catch { /* Cache availability never blocks a request. */ }
}

/** Balance independent projects; at most one attempt per distinct key, within
 * the caller's original deadline. A minted Live session can pin its project. */
export async function geminiProjectRequest(env: GeminiCredentials, url: string, init: RequestInit,
 pinned?: GeminiKeySlot): Promise<{ response: Response; keySlot?: GeminiKeySlot }> {
 const pool = keys(env), trackHealth = pool.length > 1;
 let candidates = pool;
 if (pinned) candidates = candidates.filter(key => key.slot === pinned);
 else if (candidates.length > 1 && Math.random() >= .5) candidates.reverse();
 if (!candidates.length) throw Error('GEMINI_NOT_CONFIGURED');
 const unavailable: Cooling[] = [];
 for (let index = 0; index < candidates.length; index++) {
  init.signal?.throwIfAborted();
  const key = candidates[index], health = trackHealth ? await healthRequest(key, url) : undefined;
  const state = health ? await cooling(health) : undefined;
  if (state) { unavailable.push(state); continue; }
  const headers = new Headers(init.headers); headers.set('x-goog-api-key', key.value);
  let response: Response;
  try { response = await fetch(url, { ...init, headers }); }
  catch (error) {
   if (init.signal?.aborted || index === candidates.length - 1) throw error;
   continue;
  }
  const retryable = [401, 403, 408, 429].includes(response.status) || response.status >= 500;
  if (!retryable) return { response, keySlot: key.slot };
  if (health) await cool(health, response);
  if (index === candidates.length - 1) return { response, keySlot: key.slot };
  await response.body?.cancel().catch(() => undefined);
  unavailable.push({ until: Date.now() + 1000, status: response.status });
 }
 const until = Math.min(...unavailable.map(value => value.until));
 const status = unavailable.every(value => value.status === 429) ? 429 : 503;
 return { response: Response.json({ error: { code: status, status: status === 429 ? 'RESOURCE_EXHAUSTED' : 'UNAVAILABLE',
  message: 'Gemini project capacity is temporarily unavailable.' } },
  { status, headers: { 'retry-after': String(Math.max(1, Math.ceil((until - Date.now()) / 1000))) } }) };
}

export async function fetchGemini(env: GeminiCredentials, url: string, init: RequestInit) {
 return (await geminiProjectRequest(env, url, init)).response;
}
