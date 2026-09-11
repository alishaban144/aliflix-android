import { afterEach, expect, it, vi } from 'vitest';
import { boundedBytes, speechTimingWords, subtitleAudioTiming, validateSubtitleWav } from '../src/subtitleAudio';
import worker from '../src/index';

function wav(seconds = 4) {
  const bytes = new Uint8Array(44 + seconds * 32000), data = new DataView(bytes.buffer);
  const tag = (at: number, text: string) => bytes.set(new TextEncoder().encode(text), at);
  tag(0, 'RIFF'); tag(8, 'WAVE'); tag(12, 'fmt '); tag(36, 'data');
  data.setUint32(4, bytes.length - 8, true); data.setUint32(16, 16, true);
  data.setUint16(20, 1, true); data.setUint16(22, 1, true); data.setUint32(24, 16000, true);
  data.setUint32(28, 32000, true); data.setUint16(32, 2, true); data.setUint16(34, 16, true);
  data.setUint32(40, bytes.length - 44, true); return bytes;
}
afterEach(() => vi.restoreAllMocks());
it('accepts bounded PCM and rejects malformed, stereo and oversized samples', () => {
  expect(validateSubtitleWav(wav())).toBe(4);
  for (const bytes of [wav(3), wav(21), new Uint8Array(128044)]) expect(() => validateSubtitleWav(bytes)).toThrow();
  const stereo = wav(); stereo[22] = 2; expect(() => validateSubtitleWav(stereo)).toThrow();
});
it('bounds chunked bodies even without a content length', async () => {
  await expect(boundedBytes(new Response(new Uint8Array(10)).body, 5)).rejects.toThrow('too large');
});
it('rejects silence, hallucinations and out-of-range timestamps', () => {
  const words = [{ word: 'hello', start: 1, end: 2 }, { word: 'outside', start: 9, end: 10 }];
  expect(speechTimingWords({ words, segments: [{ start: 0, end: 4, no_speech_prob: .1, avg_logprob: -.2 }] }, 4).words).toEqual([{ text: 'hello', start: 1, end: 2 }]);
  expect(speechTimingWords({ words, segments: [{ start: 0, end: 4, no_speech_prob: .9, avg_logprob: -.2 }] }, 4).words).toEqual([]);
});
it('sends only bounded audio to the fixed transcription endpoint', async () => {
  vi.spyOn(caches.default, 'match').mockResolvedValue(undefined);
  vi.spyOn(caches.default, 'put').mockResolvedValue(undefined);
  const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    expect(input).toBe('https://api.groq.com/openai/v1/audio/transcriptions');
    expect((init?.body as FormData).get('model')).toBe('whisper-large-v3');
    return Response.json({ language: 'english', words: [{ word: 'hello', start: 1, end: 2 }], segments: [{ start: 0, end: 4, no_speech_prob: .1, avg_logprob: -.2 }] });
  });
  const response = await subtitleAudioTiming(new Request('https://test/audio', { method: 'POST', headers: { 'Content-Type': 'audio/wav' }, body: wav() }), { GROQ_API_KEY: 'test' } as any);
  expect(response.status).toBe(200); expect(fetchMock).toHaveBeenCalledTimes(1);
});
it('rate limits before processing audio', async () => {
  const fetchMock = vi.spyOn(globalThis, 'fetch');
  const response = await worker.fetch(new Request('https://test/v3/subtitles/audio-timing', { method: 'POST' }), { RECOMMENDATION_RATE_LIMITER: { limit: async () => ({ success: false }) } } as any);
  expect(response.status).toBe(429); expect(fetchMock).not.toHaveBeenCalled();
});
