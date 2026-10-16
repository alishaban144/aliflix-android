import { env, runInDurableObject } from 'cloudflare:test';
import { describe, expect, it, vi } from 'vitest';
import { TalkContextSchema, permittedEpisode, validateTalkAnswer, stripMarkup, completedMovieEvidence, recommendTalkTitles } from '../src/talkEvidence';
import { talkRoute } from '../src/talk';
import { TALK_MODELS, boundedJson } from '../src/talkProviders';
import type { RecommendationEnv } from '../src/types';
const testEnv = { ...env, CURSOR_SIGNING_SECRET: 'test-only' } satisfies RecommendationEnv;

describe('Talk privacy and evidence boundaries', () => {
  it('rejects TMDB text, artwork, subtitles and provider credentials at the protocol boundary', () => {
    for (const field of ['overview', 'title', 'poster', 'subtitles', 'streamUrl', 'cookies', 'apiKey'])
      expect(TalkContextSchema.safeParse({ [field]: 'private' }).success).toBe(false);
    expect(TalkContextSchema.safeParse({ position: -1 }).success).toBe(false);
    expect(TalkContextSchema.safeParse({ imdbId: 'https://untrusted.test' }).success).toBe(false);
  });
  it('enforces season and episode ordering independently of a requested number', () => {
    const context = TalkContextSchema.parse({ type: 'tv', season: 2, episode: 3, position: 100 });
    expect(permittedEpisode(context, 1, 99)).toBe(true);
    expect(permittedEpisode(context, 2, 2)).toBe(true);
    expect(permittedEpisode(context, 2, 3)).toBe(false);
    expect(permittedEpisode(context, 2, 4)).toBe(false);
    expect(permittedEpisode(context, 3, 1)).toBe(false);
    expect(permittedEpisode({ ...context, completed: true }, 2, 3)).toBe(true);
    expect(permittedEpisode({ ...context, completed: true }, 2, 4)).toBe(false);
  });
  it('requires explicit permission instead of inferring completion from viewing position', () => {
    const context = TalkContextSchema.parse({ season: 1, episode: 1, position: 604800 });
    expect(permittedEpisode(context, 1, 1)).toBe(false);
    expect(permittedEpisode({ ...context, spoilers: true }, 90, 200)).toBe(true);
  });
  it('rejects fabricated references, ungrounded facts and ungrounded interpretations', () => {
    const base = { answer: 'The story is about time.', kind: 'fact', sources: [], words: 'TIME' };
    expect(() => validateTalkAnswer(base, [])).toThrow();
    expect(() => validateTalkAnswer({ ...base, kind: 'interpretation' }, [])).toThrow();
    expect(() => validateTalkAnswer({ ...base, sources: ['invented'] }, [])).toThrow();
    expect(validateTalkAnswer({ ...base, kind: 'unknown' }, []).kind).toBe('unknown');
  });
  it('retains licence attribution in the optional transcript and bounds semantic labels', () => {
    const source = { id: 'tvmaze-episode-1', title: 'Episode', text: 'Evidence', url: 'https://www.tvmaze.com/episodes/1', license: 'TVmaze · CC BY-SA 4.0' };
    const answer = validateTalkAnswer({ answer: 'A verified event.', kind: 'fact', sources: [source.id], words: '<TIME>' }, [source]);
    expect(answer.words).toBe('TIME'); expect(answer.references[0].license).toContain('CC BY-SA');
    expect(stripMarkup('<p>One &amp; two</p>')).toBe('One & two');
  });
  it('selects exactly the requested models, including an explicit free Fish identifier', () => {
    expect(TALK_MODELS).toEqual({ asr: 'gemini-3.5-transcribe-live', reasoning: 'gemini-3.8-flash', tts: 's2.1-pro-free',
      fallbackAsr: 'whisper-large-v3-turbo', fallbackReasoning: 'openai/gpt-oss-120b', fallbackTts: 'gemini-3.8-flash-lite-tts' });
  });
  it('bounds provider response memory and rejects quota failures', async () => {
    await expect(boundedJson(new Response('x'.repeat(100), { status: 200 }), 10)).rejects.toThrow('RESPONSE_LIMIT');
    await expect(boundedJson(new Response('{}', { status: 429 }))).rejects.toThrow('QUOTA');
    await expect(boundedJson(Response.json({ ok: true }))).resolves.toEqual({ ok: true });
  });
  it('never retrieves whole-movie plots for a partially watched movie', async () => {
    const spy = vi.spyOn(globalThis, 'fetch');
    try {
      expect(await completedMovieEvidence(TalkContextSchema.parse({ type: 'movie', imdbId: 'tt0816692', position: 100 }), new AbortController().signal)).toEqual([]);
      expect(spy).not.toHaveBeenCalled();
    } finally { spy.mockRestore(); }
  });
  it('retrieves verified genre recommendations without forwarding plot summaries or future links', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([
      { id: 1, name: 'Known Science Fiction', url: 'https://www.tvmaze.com/shows/1', genres: ['Science-Fiction'], premiered: '2020-01-01', rating: { average: 8 }, summary: 'FUTURE PLOT SECRET' },
      { id: 2, name: 'Comedy', url: 'https://www.tvmaze.com/shows/2', genres: ['Comedy'], rating: { average: 9 } },
    ]));
    try {
      const result = await recommendTalkTitles('science fiction', new AbortController().signal);
      expect(result).toHaveLength(1); expect(result[0].title).toBe('Known Science Fiction');
      expect(JSON.stringify(result)).not.toContain('FUTURE PLOT SECRET'); expect(result[0].license).toContain('CC BY-SA');
    } finally { spy.mockRestore(); }
  });
});

describe('Talk authentication, quotas and cleanup', () => {
  it('does not intercept existing recommendation and Watch Party paths', async () => {
    expect(await talkRoute(new Request('https://worker/v1/recommendations'), testEnv)).toBeNull();
    expect(await talkRoute(new Request('https://worker/v1/party/identity'), testEnv)).toBeNull();
  });
  it('requires authenticated WebSockets and exposes no internal exception details', async () => {
    expect((await talkRoute(new Request('https://worker/v1/talk'), testEnv))?.status).toBe(426);
    const response = await talkRoute(new Request('https://worker/v1/talk', { headers: { Upgrade: 'websocket', Authorization: 'Bearer forged' } }),
      testEnv);
    expect(response?.status).toBe(401); expect(await response?.text()).toBe('{"error":"UNAUTHORIZED"}');
  });
  it('atomically bounds daily use across concurrent callers and retains no transcript in quota storage', async () => {
    const stub = env.TALK_SESSIONS.getByName('quota:test:' + crypto.randomUUID());
    const results = await Promise.all(Array.from({ length: 20 }, () => stub.reserve(1, 5)));
    expect(results.filter(Boolean)).toHaveLength(5);
    await runInDurableObject(stub, (_instance, ctx) => {
      const rows = ctx.storage.sql.exec<{ key: string; value: string }>('SELECT key,value FROM memory').toArray();
      expect(rows).toHaveLength(1); expect(rows[0].key).toMatch(/^quota:/); expect(rows[0].value).toBe('5');
    });
  });
  it('refuses a direct unauthenticated Durable Object socket', async () => {
    const stub = env.TALK_SESSIONS.getByName('user:test:' + crypto.randomUUID());
    expect((await stub.fetch(new Request('https://talk/socket', { headers: { Upgrade: 'websocket' } }))).status).toBe(401);
  });
  it('clears persistent history with no provider calls and rejects oversized frames', async () => {
    const stub = env.TALK_SESSIONS.getByName('user:test:' + crypto.randomUUID());
    const response = await stub.fetch(new Request('https://talk/socket', { headers: { Upgrade: 'websocket', 'x-talk-owner': 'test', 'x-talk-expiry': String(Date.now() + 60000) } }));
    const ws = response.webSocket!; ws.accept(); const messages: { type: string; code?: string }[] = [];
    ws.addEventListener('message', event => messages.push(JSON.parse(event.data as string)));
    await runInDurableObject(stub, (_instance, ctx) => { ctx.storage.sql.exec("INSERT INTO memory VALUES ('history','[]',?)", Date.now() + 60000); });
    ws.send(JSON.stringify({ type: 'clear' }));
    await new Promise(resolve => setTimeout(resolve, 30));
    await runInDurableObject(stub, (_instance, ctx) => { expect(ctx.storage.sql.exec("SELECT key FROM memory WHERE key='history'").toArray()).toHaveLength(0); });
    expect(messages.some(m => m.type === 'cleared')).toBe(true);
    ws.send('x'.repeat(31000)); await new Promise(resolve => setTimeout(resolve, 30));
    expect(messages.some(m => m.type === 'error')).toBe(true); ws.close();
  });
});
