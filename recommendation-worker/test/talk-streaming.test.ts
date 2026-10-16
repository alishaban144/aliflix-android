import { afterEach, describe, expect, it, vi } from 'vitest';
import { decode, encode } from '@msgpack/msgpack';
import { connectAsr, fishSpeech } from '../src/talkProviders';
import type { RecommendationEnv } from '../src/types';
import { env } from 'cloudflare:test';

const configuration = { ...env, CURSOR_SIGNING_SECRET: 'test', GEMINI_API_KEY: 'test-gemini', FISH_AUDIO_API_KEY: 'test-fish' } satisfies RecommendationEnv;
afterEach(() => vi.restoreAllMocks());

describe('Talk streaming protocol and cancellation', () => {
  it('uses binary MessagePack, explicit free model header and incremental PCM', async () => {
    const events: string[] = []; const chunks: Uint8Array[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, init) => {
      const headers = new Headers(init?.headers);
      expect(headers.get('model')).toBe('s2.1-pro-free');
      expect(headers.get('Authorization')).toBe('Bearer test-fish');
      const pair = new WebSocketPair(); pair[1].binaryType = 'arraybuffer'; pair[1].accept();
      pair[1].addEventListener('message', event => {
        const msg = decode(new Uint8Array(event.data as ArrayBuffer)) as { event: string; request?: { format: string; sample_rate: number } };
        events.push(msg.event);
        if (msg.event === 'start') { expect(msg.request?.format).toBe('pcm'); expect(msg.request?.sample_rate).toBe(24000); }
        if (msg.event === 'text') pair[1].send(encode({ event: 'audio', audio: new Uint8Array([1, 0, 2, 0]) }));
        if (msg.event === 'stop') { pair[1].send(encode({ event: 'audio', audio: new Uint8Array([3, 0]) })); pair[1].send(encode({ event: 'finish', reason: 'stop' })); }
      });
      return new Response(null, { status: 101, webSocket: pair[0] });
    });
    await fishSpeech(configuration, 'Hello', bytes => chunks.push(bytes), new AbortController().signal);
    expect(events).toEqual(['start', 'text', 'flush', 'stop']); expect(chunks).toHaveLength(2);
  });
  it('closes synthesis on interruption and rejects an empty/failed output', async () => {
    const controller = new AbortController(); let remote: WebSocket | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      const pair = new WebSocketPair(); pair[1].binaryType = 'arraybuffer'; pair[1].accept(); remote = pair[1];
      pair[1].addEventListener('message', () => controller.abort());
      return new Response(null, { status: 101, webSocket: pair[0] });
    });
    await expect(fishSpeech(configuration, 'Hello', () => { throw Error('Stale audio'); }, controller.signal)).rejects.toThrow('CANCELLED');
    remote?.close();
  });
  it('negotiates multilingual ASR before capture and distinguishes interim hypotheses from final text', async () => {
    const heard: { text: string; final: boolean }[] = []; let remote: WebSocket | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, init) => {
      expect(new Headers(init?.headers).get('x-goog-api-key')).toBe('test-gemini');
      const pair = new WebSocketPair(); pair[1].binaryType = 'arraybuffer'; pair[1].accept(); remote = pair[1];
      pair[1].addEventListener('message', event => {
        const setup = JSON.parse(event.data as string).setup;
        expect(setup.model).toBe('models/gemini-3.5-transcribe-live');
        expect(setup.inputAudioTranscription.languageCodes).toEqual([]);
        expect(setup.realtimeInputConfig.automaticActivityDetection.disabled).toBe(true);
        pair[1].send(JSON.stringify({ setupComplete: {} }));
      });
      return new Response(null, { status: 101, webSocket: pair[0] });
    });
    const socket = await connectAsr(configuration, (text, final) => heard.push({ text, final }), () => {}, new AbortController().signal);
    remote!.send(JSON.stringify({ serverContent: { interimInputTranscription: { text: 'Guten' } } }));
    remote!.send(JSON.stringify({ serverContent: { inputTranscription: { text: 'Guten Abend' } } }));
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(heard).toEqual([{ text: 'Guten', final: false }, { text: 'Guten Abend', final: true }]);
    socket.close(); remote?.close();
  });
});
