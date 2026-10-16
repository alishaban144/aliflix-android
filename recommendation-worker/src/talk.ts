import { DurableObject } from 'cloudflare:workers';
import { z } from 'zod';
import { verifyIdentity } from './watchParty';
import type { RecommendationEnv } from './types';
import { TalkContextSchema, TalkContext, Evidence, episodeEvidence, lookupTalkTitles, movieEvidence, talkTitleDetails, TalkToolSchema, recommendTalkTitles, completedMovieEvidence, talkHistoryBoundary } from './talkEvidence';
import { TALK_MODELS, TalkPlan, TalkTurn, answerTalk, base64, chooseTalkPlan, connectAsr, fishSpeech,
  geminiSpeech, transcribeGroq } from './talkProviders';

const MINUTE = 60_000, DAY = 86_400_000;
const json = (body: unknown, status = 200) => Response.json(body, { status, headers: { 'cache-control': 'no-store' } });
const command = z.discriminatedUnion('type', [
  z.object({ type: z.literal('start'), context: TalkContextSchema.default(() => TalkContextSchema.parse({})), keepHistory: z.boolean().default(false),
    history: z.array(z.object({ role: z.enum(['user', 'assistant']), text: z.string().max(2000) })).max(12).default([]) }).strict(),
  z.object({ type: z.literal('activity'), active: z.boolean() }).strict(),
  z.object({ type: z.literal('interrupt') }).strict(), z.object({ type: z.literal('ping') }).strict(),
  z.object({ type: z.literal('privacy'), keepHistory: z.boolean() }).strict(),
  z.object({ type: z.literal('spoilers'), allowed: z.boolean() }).strict(),
  z.object({ type: z.literal('clear') }).strict(),
]);

export async function talkRoute(request: Request, env: RecommendationEnv): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith('/v1/talk')) return null;
  if (path !== '/v1/talk' || request.method !== 'GET') return json({ error: 'NOT_FOUND' }, 404);
  try {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'WEBSOCKET_REQUIRED' }, 426);
    const identity = await verifyIdentity(request.headers.get('Authorization')?.replace(/^Bearer /, '') || '', env.CURSOR_SIGNING_SECRET);
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    const ipHash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(ip + env.CURSOR_SIGNING_SECRET))))
      .map(x => x.toString(16).padStart(2, '0')).join('');
    const rate = await env.RECOMMENDATION_RATE_LIMITER.limit({ key: 'talk:' + ipHash });
    if (!rate.success) return json({ error: 'TRY_LATER' }, 429);
    // Guest identity re-issuance cannot reset the IP or global daily budget.
    if (!await env.TALK_SESSIONS.getByName('quota:ip:' + ipHash).reserve(1, 12) ||
        !await env.TALK_SESSIONS.getByName('quota:global').reserve(1, 40)) return json({ error: 'DAILY_LIMIT' }, 429);
    return env.TALK_SESSIONS.getByName('user:' + identity.sub).fetch(new Request('https://talk/socket', {
      headers: { Upgrade: 'websocket', 'x-talk-owner': identity.sub, 'x-talk-expiry': String(Math.min(identity.exp, Date.now() + 30 * MINUTE)) } }));
  } catch { return json({ error: 'UNAUTHORIZED' }, 401); }
}

export class TalkSession extends DurableObject<RecommendationEnv> {
  private socket?: WebSocket;
  private asr?: WebSocket;
  private asrOpened = 0;
  private asrGeneration = 0;
  private plan?: TalkPlan;
  private context: TalkContext = TalkContextSchema.parse({});
  private history: TalkTurn[] = [];
  private keep = false;
  private active = false;
  private ending = false;
  private awaitingAsrEnd = false;
  private transcript = '';
  private chunks: Uint8Array[] = [];
  private chunkBytes = 0;
  private sequence = 0;
  private generation = 0;
  private turnAbort?: AbortController;
  private sessionAbort?: AbortController;
  private booting = false;
  private started = false;
  private turns = 0;
  private bytesWindow = 0;
  private windowAt = Date.now();
  private eventWindowAt = Date.now();
  private eventCount = 0;
  private expires = 0;
  private endTimer?: ReturnType<typeof setTimeout>;

  constructor(ctx: DurableObjectState, env: RecommendationEnv) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS memory (key TEXT PRIMARY KEY, value TEXT NOT NULL, expires INTEGER NOT NULL)');
    // Rehydration after eviction is intentionally a reconnect, never an orphan
    // hot microphone. The phone owns audio capture and its connection generation.
    for (const ws of ctx.getWebSockets()) ws.close(1012, 'Reconnect');
  }
  async reserve(amount: number, limit: number): Promise<boolean> {
    const day = Math.floor(Date.now() / DAY), key = 'quota:' + day;
    this.ctx.storage.sql.exec('DELETE FROM memory WHERE expires < ?', Date.now());
    const current = this.ctx.storage.sql.exec<{ value: string }>('SELECT value FROM memory WHERE key=?', key).toArray()[0];
    const used = Number(current?.value || 0); if (used + amount > limit) return false;
    this.ctx.storage.sql.exec('INSERT OR REPLACE INTO memory VALUES (?,?,?)', key, String(used + amount), (day + 2) * DAY);
    await this.ctx.storage.setAlarm((day + 2) * DAY); return true;
  }
  async fetch(request: Request): Promise<Response> {
    if (!request.headers.get('x-talk-owner') || request.headers.get('Upgrade') !== 'websocket') return json({ error: 'UNAUTHORIZED' }, 401);
    this.stop(); this.socket?.close(1000, 'Session moved');
    const pair = new WebSocketPair(); const [client, server] = Object.values(pair);
    this.socket = server; this.generation++; this.expires = Number(request.headers.get('x-talk-expiry'));
    this.started = false; this.booting = false; this.turns = 0;
    // An active voice session owns ephemeral PCM and upstream streams. Keep it
    // warm, bounded to 30 minutes, rather than persisting microphone buffers or
    // losing the first utterance when an idle ASR connection hibernates. Watch
    // Party continues to use its separate hibernating sockets unchanged.
    server.binaryType = 'arraybuffer'; server.accept();
    server.addEventListener('message', event => this.ctx.waitUntil(this.webSocketMessage(server, event.data)));
    server.addEventListener('close', () => {
      server.close(1000, 'Ended'); this.ctx.waitUntil(this.webSocketClose(server));
    });
    server.addEventListener('error', () => this.ctx.waitUntil(this.webSocketError(server)));
    await this.ctx.storage.setAlarm(Math.min(this.expires, Date.now() + 30 * MINUTE));
    this.send({ type: 'connected', protocol: 1 });
    return new Response(null, { status: 101, webSocket: client });
  }
  private send(value: unknown) {
    try { this.socket?.send(JSON.stringify(value)); }
    catch {
      // Clear first: stop() emits an interruption, so a failed socket must not
      // recursively re-enter send()/stop() during disconnect cleanup.
      const dead = this.socket; this.socket = undefined; this.stop(); dead?.close(1011, 'Connection lost');
    }
  }
  private interrupt() {
    this.sequence++; this.turnAbort?.abort(); this.turnAbort = undefined;
    if (this.endTimer) clearTimeout(this.endTimer); this.endTimer = undefined;
    this.ending = false;
    this.send({ type: 'interrupted', sequence: this.sequence });
  }
  private stop() {
    this.interrupt(); this.sessionAbort?.abort(); this.asrGeneration++; this.asr?.close(1000, 'Session ended'); this.asr = undefined;
    this.active = false; this.started = false; this.chunks = []; this.chunkBytes = 0;
    if (!this.keep) this.history = [];
  }
  private async startAsr(ticket: number) {
    const connection = ++this.asrGeneration;
    const setupAbort = new AbortController();
    const setupTimer = setTimeout(() => setupAbort.abort(), 7000);
    const signal = AbortSignal.any([this.sessionAbort!.signal, setupAbort.signal]);
    try {
      const ws = await connectAsr(this.env, (text, final) => {
        if (ticket !== this.generation || connection !== this.asrGeneration || !this.started || !this.active && !this.ending) return;
        if (final) this.transcript = (this.transcript + text).slice(0, 2000);
        if (text) this.send({ type: 'transcript', role: 'user', text: final ? this.transcript : this.transcript + text, final: false, sequence: this.sequence });
        if (final && this.ending) { this.awaitingAsrEnd = false; this.scheduleFinish(100); }
      }, () => {
        if (ticket !== this.generation || connection !== this.asrGeneration || !this.started) return;
        this.asr = undefined;
        // Recover on the next boundary; never turn an outage into an empty LLM request.
        if (this.env.GROQ_API_KEY && this.plan) this.plan.asr = 'groq';
        this.send({ type: 'providers', plan: this.plan });
      }, signal);
      if (ticket !== this.generation) { ws.close(1000); return; }
      this.asr = ws; this.asrOpened = Date.now(); this.plan!.asr = 'gemini';
    } catch {
      if (ticket !== this.generation) return;
      if (!this.env.GROQ_API_KEY) throw Error('ASR_UNAVAILABLE');
      this.plan!.asr = 'groq';
    } finally { clearTimeout(setupTimer); }
  }
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    if (ws !== this.socket) { ws.close(1008); return; }
    if (Date.now() - this.eventWindowAt >= 1000) { this.eventWindowAt = Date.now(); this.eventCount = 0; }
    if (++this.eventCount > 120) { this.stop(); ws.close(1008, 'Message rate'); return; }
    if (Date.now() > this.expires) { this.stop(); ws.close(1000, 'Session limit'); return; }
    try {
      if (typeof message !== 'string') { this.audio(new Uint8Array(message)); return; }
      if (message.length > 30_000) throw Error('MESSAGE_LIMIT');
      const event = command.parse(JSON.parse(message));
      if (event.type === 'ping') { this.send({ type: 'pong' }); return; }
      if (event.type === 'clear') {
        this.interrupt(); this.history = []; this.ctx.storage.sql.exec("DELETE FROM memory WHERE key IN ('history','history-context')");
        this.active = false; this.ending = false; this.transcript = ''; this.chunks = []; this.chunkBytes = 0;
        this.asrGeneration++; this.asr?.close(1000, 'History cleared'); this.asr = undefined;
        this.send({ type: 'cleared' }); return;
      }
      if (event.type === 'privacy') {
        this.keep = event.keepHistory; if (!this.keep) this.ctx.storage.sql.exec("DELETE FROM memory WHERE key IN ('history','history-context')");
        else await this.persistHistory(); return;
      }
      if (event.type === 'spoilers') {
        this.context = { ...this.context, spoilers: event.allowed }; this.interrupt();
        if (!event.allowed) { this.history = []; this.ctx.storage.sql.exec("DELETE FROM memory WHERE key IN ('history','history-context')"); }
        return;
      }
      if (event.type === 'interrupt') { this.interrupt(); return; }
      if (event.type === 'start') {
        if (this.started || this.booting) return;
        this.booting = true; const ticket = this.generation;
        this.sessionAbort = new AbortController(); this.context = event.context; this.keep = event.keepHistory;
        const saved = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM memory WHERE key='history' AND expires>?", Date.now()).toArray()[0];
        const boundary = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM memory WHERE key='history-context' AND expires>?", Date.now()).toArray()[0];
        this.history = this.keep && saved && boundary?.value === talkHistoryBoundary(this.context) ?
          z.array(z.object({ role: z.enum(['user', 'assistant']), text: z.string().max(2000) })).max(12).parse(JSON.parse(saved.value)) : event.history;
        if (!this.keep) this.ctx.storage.sql.exec("DELETE FROM memory WHERE key IN ('history','history-context')");
        this.plan = await chooseTalkPlan(this.env, AbortSignal.any([this.sessionAbort.signal, AbortSignal.timeout(6000)]));
        const cooldown = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM memory WHERE key='reasoning-cooldown' AND expires>?", Date.now()).toArray()[0];
        if (cooldown && this.env.GROQ_API_KEY) this.plan.reasoning = 'groq';
        if (ticket !== this.generation) return;
        this.started = true;
        if (this.plan.asr === 'gemini') await this.startAsr(ticket);
        this.booting = false;
        this.send({ type: 'ready', plan: this.plan, models: TALK_MODELS, history: this.history }); return;
      }
      if (!this.started || !this.plan) return;
      if (event.type === 'activity') {
        if (event.active) {
          if (this.active) return;
          const unfinished = this.awaitingAsrEnd;
          this.interrupt(); this.transcript = ''; this.chunks = []; this.chunkBytes = 0; this.active = true;
          this.awaitingAsrEnd = false;
          if (this.asr && (unfinished || Date.now() - this.asrOpened > 9 * MINUTE)) {
            this.asrGeneration++; this.asr.close(1000, 'Rotate'); this.asr = undefined;
          }
          const sequence = this.sequence;
          if (this.plan.asr === 'gemini' && !this.asr) {
            await this.startAsr(this.generation);
          }
          if (sequence !== this.sequence || !this.active) return;
          this.asr?.send(JSON.stringify({ realtimeInput: { activityStart: {} } }));
          // Audio arriving during a new handshake is already bounded in chunks.
          // Flush it before live frames so rotation cannot drop opening words.
          if (this.asr) for (const pcm of this.chunks)
            this.asr.send(JSON.stringify({ realtimeInput: { audio: { data: base64(pcm), mimeType: 'audio/pcm;rate=16000' } } }));
        } else if (this.active) {
          this.active = false; this.ending = true;
          this.awaitingAsrEnd = true;
          this.asr?.send(JSON.stringify({ realtimeInput: { activityEnd: {} } })); this.scheduleFinish(this.plan.asr === 'groq' ? 0 : 900);
        }
      }
    } catch (error) {
      const failedStart = this.booting; this.booting = false;
      if (failedStart) { this.started = false; this.sessionAbort?.abort(); this.asr?.close(1000); this.asr = undefined; }
      this.send({ type: 'error', code: error instanceof z.ZodError ? 'INVALID_MESSAGE' : error instanceof Error && error.message === 'QUOTA' ? 'QUOTA' : 'CONNECTION_FAILED' });
    }
  }
  private audio(pcm: Uint8Array) {
    if (pcm.length > 6400 || pcm.length % 2) { this.stop(); this.socket?.close(1009, 'Audio frame limit'); return; }
    if (!this.started || !this.active) return;
    const now = Date.now(); if (now - this.windowAt >= 1000) { this.windowAt = now; this.bytesWindow = 0; }
    this.bytesWindow += pcm.length;
    if (this.bytesWindow > 64_000 || this.chunkBytes + pcm.length > 960_000) { this.stop(); this.socket?.close(1008, 'Audio limit'); return; }
    this.chunks.push(pcm); this.chunkBytes += pcm.length;
    if (this.plan?.asr === 'gemini' && this.asr) this.asr.send(JSON.stringify({ realtimeInput: { audio: { data: base64(pcm), mimeType: 'audio/pcm;rate=16000' } } }));
  }
  private scheduleFinish(delay: number) {
    if (this.endTimer) clearTimeout(this.endTimer);
    this.endTimer = setTimeout(() => { this.endTimer = undefined; this.ctx.waitUntil(this.finishTurn()); }, delay);
  }
  private async evidence(text: string, signal: AbortSignal): Promise<Evidence[]> {
    const timeout = AbortSignal.any([signal, AbortSignal.timeout(5000)]);
    // Episode requests are resolved only inside the saved boundary. A future
    // number mentioned in a prompt cannot move that boundary.
    const pair = /(?:season|staffel|s)\s*(\d+)\s*(?:episode|folge|e)\s*(\d+)/i.exec(text);
    const season = pair ? Number(pair[1]) : this.context.season;
    const episode = pair ? Number(pair[2]) : this.context.episode;
    if (/recommend|suggest|empfehl|اقتراح|اقترح|رشح|recomiend|recomend/i.test(text)) {
      const suggestions = await recommendTalkTitles(text, timeout).catch(() => [] as Evidence[]);
      if (suggestions.length) return suggestions;
    }
    if (this.context.type === 'movie' && this.context.imdbId && (this.context.completed || this.context.spoilers)) {
      const key = `evidence:movie:${this.context.id || 0}:${this.context.imdbId}`;
      const cached = this.ctx.storage.sql.exec<{ value: string }>('SELECT value FROM memory WHERE key=? AND expires>?', key, Date.now()).toArray()[0];
      if (cached) return JSON.parse(cached.value) as Evidence[];
      const facts = await completedMovieEvidence(this.context, timeout);
      if (facts.length) { this.ctx.storage.sql.exec('INSERT OR REPLACE INTO memory VALUES (?,?,?)', key, JSON.stringify(facts), Date.now() + DAY); this.trimCache(); }
      return facts;
    }
    if (this.context.type === 'tv' && this.context.imdbId) {
      const key = `evidence:tv:${this.context.id || 0}:${this.context.imdbId}:${season}:${episode}`;
      // Check permission BEFORE touching the cache; older permitted responses
      // must never leak through after a context/boundary change.
      const { permittedEpisode } = await import('./talkEvidence');
      if (permittedEpisode(this.context, season, episode)) {
        const cached = this.ctx.storage.sql.exec<{ value: string }>('SELECT value FROM memory WHERE key=? AND expires>?', key, Date.now()).toArray()[0];
        if (cached) return JSON.parse(cached.value) as Evidence[];
        const facts = await episodeEvidence(this.context, season, episode, timeout);
        if (facts.length) { this.ctx.storage.sql.exec('INSERT OR REPLACE INTO memory VALUES (?,?,?)', key, JSON.stringify(facts), Date.now() + DAY); this.trimCache(); }
        return facts;
      }
      // No whole-episode summary is retrieved for the partially watched episode.
      if (!/recommend|suggest|empfehl|اقترح|أفلام|pel[ií]cula|movie|series/i.test(text)) return [];
    }
    const results = await Promise.allSettled([lookupTalkTitles(text, timeout), movieEvidence(text, timeout)]);
    return results.flatMap(r => r.status === 'fulfilled' ? r.value : []).slice(0, 6);
  }
  private trimCache() {
    this.ctx.storage.sql.exec('DELETE FROM memory WHERE expires<?', Date.now());
    this.ctx.storage.sql.exec("DELETE FROM memory WHERE key LIKE 'evidence:%' AND key NOT IN (SELECT key FROM memory WHERE key LIKE 'evidence:%' ORDER BY expires DESC LIMIT 30)");
  }
  private async tool(request: z.infer<typeof TalkToolSchema>, signal: AbortSignal): Promise<Evidence[]> {
    const bounded = AbortSignal.any([signal, AbortSignal.timeout(5000)]);
    switch (request.tool) {
      case 'recommendations': return recommendTalkTitles(request.query || '', bounded);
      case 'episode_evidence': return episodeEvidence(this.context, request.season ?? this.context.season,
        request.episode ?? this.context.episode, bounded);
      case 'title_details': return talkTitleDetails(request.imdbId || this.context.imdbId || '', bounded);
      case 'my_space': {
        const results = await Promise.allSettled(this.context.library.slice(0, 4).filter(x => x.type === 'tv').map(async item =>
          (await talkTitleDetails(item.imdbId, bounded)).map(fact => ({ ...fact, text: fact.text + '\nViewer: ' + item.kind }))));
        return results.flatMap(r => r.status === 'fulfilled' ? r.value : []);
      }
      default: {
        const query = request.query?.trim(); if (!query) return [];
        const results = await Promise.allSettled([lookupTalkTitles(query, bounded), movieEvidence(query, bounded)]);
        return results.flatMap(r => r.status === 'fulfilled' ? r.value : []).slice(0, 6);
      }
    }
  }
  private async persistHistory() {
    if (this.keep) {
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO memory VALUES ('history',?,?)", JSON.stringify(this.history.slice(-12)), Date.now() + 7 * DAY);
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO memory VALUES ('history-context',?,?)", talkHistoryBoundary(this.context), Date.now() + 7 * DAY);
    }
    await this.ctx.storage.setAlarm(this.socket ? this.expires : this.keep ? Date.now() + 7 * DAY : Date.now() + MINUTE);
  }
  private async finishTurn() {
    if (!this.ending || !this.plan || this.active) return;
    this.ending = false; const seq = this.sequence, ticket = this.generation;
    this.turnAbort?.abort(); const controller = new AbortController(); this.turnAbort = controller;
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30000)]);
    const valid = () => !signal.aborted && seq === this.sequence && ticket === this.generation;
    try {
      let text = this.transcript.trim();
      if ((!text || this.plan.asr === 'groq') && this.chunkBytes >= 6400 && this.env.GROQ_API_KEY) {
        const joined = new Uint8Array(this.chunkBytes); let at = 0;
        for (const chunk of this.chunks) { joined.set(chunk, at); at += chunk.length; }
        text = await transcribeGroq(this.env, joined, signal);
      }
      this.chunks = []; this.chunkBytes = 0; if (!valid() || !text) { if (valid()) this.send({ type: 'listening', sequence: seq }); return; }
      if (++this.turns > 25) { this.send({ type: 'error', code: 'SESSION_LIMIT' }); this.stop(); return; }
      this.send({ type: 'transcript', role: 'user', text, final: true, sequence: seq });
      this.send({ type: 'thinking', sequence: seq });
      this.history.push({ role: 'user', text }); this.history = this.history.slice(-12);
      let facts = await this.evidence(text, signal).catch(() => [] as Evidence[]);
      let answer;
      try { answer = await answerTalk(this.env, this.plan, this.history, this.context, facts, signal); }
      catch (error) {
        if (!valid() || this.plan.reasoning !== 'gemini' || !this.env.GROQ_API_KEY) throw error;
        this.plan.reasoning = 'groq'; this.send({ type: 'providers', plan: this.plan });
        this.ctx.storage.sql.exec("INSERT OR REPLACE INTO memory VALUES ('reasoning-cooldown','groq',?)", Date.now() + 15 * MINUTE);
        answer = await answerTalk(this.env, this.plan, this.history, this.context, facts, signal);
      }
      if ('tool' in answer) {
        const more = await this.tool(answer, signal).catch(() => [] as Evidence[]);
        facts = [...facts, ...more].filter((s, i, all) => all.findIndex(x => x.id === s.id) === i).slice(-10);
        answer = await answerTalk(this.env, this.plan, this.history, this.context, facts, signal, false);
      }
      if ('tool' in answer) throw Error('TOOL_LIMIT');
      if (!valid()) return;
      this.history.push({ role: 'assistant', text: answer.answer }); this.history = this.history.slice(-12);
      await this.persistHistory();
      if (!valid()) return;
      this.send({ type: 'answer', text: answer.answer, words: answer.words, language: answer.language, kind: answer.kind, references: answer.references, sequence: seq });
      let emitted = 0;
      const audio = (bytes: Uint8Array) => {
        if (!valid()) return;
        if (emitted + bytes.length > 2_400_000 || bytes.length % 2) throw Error('AUDIO_LIMIT');
        emitted += bytes.length;
        // Gemini's fallback may return a complete utterance. Bound every client
        // message exactly like streamed Fish audio, without changing PCM samples.
        for (let at = 0; at < bytes.length; at += 24000)
          this.send({ type: 'audio', data: base64(bytes.subarray(at, at + 24000)), sampleRate: 24000, sequence: seq });
      };
      try {
        if (this.plan.tts === 'fish') await fishSpeech(this.env, answer.answer, audio, signal);
        else if (this.plan.tts === 'gemini') audio(await geminiSpeech(this.env, answer.answer, signal));
        else throw Error('ANDROID_TTS');
      } catch {
        if (!valid()) return;
        // Never repeat a partially spoken answer after a provider fails.
        if (emitted === 0 && this.plan.tts === 'fish' && this.env.GEMINI_API_KEY) {
          try { audio(await geminiSpeech(this.env, answer.answer, signal)); }
          catch { if (valid()) this.send({ type: 'speak-local', text: answer.answer, language: answer.language, sequence: seq }); }
        } else if (emitted === 0) this.send({ type: 'speak-local', text: answer.answer, language: answer.language, sequence: seq });
      }
      if (valid()) this.send({ type: 'audio-end', sequence: seq });
    } catch (error) {
      console.warn(JSON.stringify({ event: 'talk_turn_failed',
        error: error instanceof Error ? error.name : 'Unknown',
        code: error instanceof Error && /^[A-Z_0-9]{1,60}$/.test(error.message) ? error.message : 'INVALID_OR_INTERRUPTED',
        aborted: signal.aborted }));
      if (!controller.signal.aborted && seq === this.sequence && ticket === this.generation) this.send({ type: 'error',
        code: error instanceof Error && error.message === 'QUOTA' ? 'QUOTA' : 'TURN_FAILED',
        reason: error instanceof Error && /^[A-Z_0-9]{1,60}$/.test(error.message) ? error.message : error instanceof Error ? error.name : 'Unknown',
        fields: error instanceof z.ZodError ? error.issues.map(i => ({ path: i.path, code: i.code })) : [], sequence: seq });
    }
  }
  async webSocketClose(ws: WebSocket) { if (ws === this.socket) { this.socket = undefined; this.stop(); await this.persistHistory(); } }
  async webSocketError(ws: WebSocket) { await this.webSocketClose(ws); }
  async alarm() {
    this.trimCache();
    if (this.socket && Date.now() >= this.expires) { this.stop(); this.socket.close(1000, 'Session limit'); this.socket = undefined; }
    const next = this.ctx.storage.sql.exec<{ expires: number }>('SELECT MIN(expires) as expires FROM memory').toArray()[0]?.expires;
    if (next) await this.ctx.storage.setAlarm(Math.max(Date.now() + MINUTE, next));
  }
}
