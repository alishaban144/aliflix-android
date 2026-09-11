import { RecommendationEnv, ServiceError } from './types';

const MAX_WAV_BYTES = 640_044; // At most 20 seconds of 16 kHz, mono, PCM16 audio.

export async function boundedBytes(body: ReadableStream<Uint8Array> | null, maximum: number): Promise<Uint8Array> {
  if (!body) throw new ServiceError('INVALID_AUDIO', 'Missing audio sample', 400, false);
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > maximum) {
        await reader.cancel();
        throw new ServiceError('PAYLOAD_TOO_LARGE', 'Audio sample is too large', 413, false);
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}

export function validateSubtitleWav(bytes: Uint8Array): number {
  if (bytes.length < 128_044 || bytes.length > MAX_WAV_BYTES) throw new ServiceError('INVALID_AUDIO', 'Use a 4–20 second audio sample', 400, false);
  const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const tag = (offset: number) => new TextDecoder().decode(bytes.subarray(offset, offset + 4));
  if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE' || tag(12) !== 'fmt ' || tag(36) !== 'data' ||
      data.getUint32(4, true) !== bytes.length - 8 || data.getUint32(16, true) !== 16 ||
      data.getUint16(20, true) !== 1 || data.getUint16(22, true) !== 1 || data.getUint32(24, true) !== 16000 ||
      data.getUint32(28, true) !== 32000 || data.getUint16(32, true) !== 2 || data.getUint16(34, true) !== 16 ||
      data.getUint32(40, true) !== bytes.length - 44 || (bytes.length - 44) % 2 !== 0) {
    throw new ServiceError('INVALID_AUDIO', 'Expected mono PCM16 WAV at 16 kHz', 400, false);
  }
  return (bytes.length - 44) / 32000;
}

export function speechTimingWords(raw: unknown, duration: number): { language: string; words: { text: string; start: number; end: number }[] } {
  const value = raw as { language?: unknown; words?: unknown; segments?: unknown };
  if (!value || !Array.isArray(value.words) || !Array.isArray(value.segments)) throw new ServiceError('INVALID_SPEECH_RESPONSE', 'Speech timing was unavailable', 502, true);
  const speech = value.segments.filter((segment): segment is { start: number; end: number } => {
    if (!segment || typeof segment !== 'object') return false;
    const s = segment as Record<string, unknown>;
    return typeof s.start === 'number' && typeof s.end === 'number' &&
      typeof s.no_speech_prob === 'number' && s.no_speech_prob < 0.6 &&
      typeof s.avg_logprob === 'number' && s.avg_logprob > -1;
  });
  const words = value.words.slice(0, 240).flatMap(item => {
    if (!item || typeof item !== 'object') return [];
    const w = item as Record<string, unknown>;
    if (typeof w.word !== 'string' || w.word.length > 80 || typeof w.start !== 'number' || typeof w.end !== 'number' ||
        !Number.isFinite(w.start) || !Number.isFinite(w.end) || w.start < 0 || w.end <= w.start || w.end > duration + 0.5 ||
        !speech.some(s => (w.start as number) >= s.start - 0.1 && (w.end as number) <= s.end + 0.1)) return [];
    return [{ text: w.word.trim(), start: w.start, end: w.end }];
  });
  return { language: typeof value.language === 'string' ? value.language.slice(0, 30) : '', words };
}

/** Only bounded PCM samples are accepted; this endpoint never fetches a client-supplied URL. */
export async function subtitleAudioTiming(request: Request, env: RecommendationEnv): Promise<Response> {
  if (!env.GROQ_API_KEY) throw new ServiceError('SPEECH_UNAVAILABLE', 'Audio matching is unavailable', 503, true);
  if (!request.headers.get('content-type')?.startsWith('audio/wav')) throw new ServiceError('INVALID_AUDIO', 'Expected WAV audio', 415, false);
  const bytes = await boundedBytes(request.body, MAX_WAV_BYTES);
  const duration = validateSubtitleWav(bytes);
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))).map(b => b.toString(16).padStart(2, '0')).join('');
  const cacheKey = new Request(`https://subtitle-timing.aliflix.invalid/v1/${hash}`);
  const cached = await caches.default.match(cacheKey);
  if (cached) return cached;
  const form = new FormData();
  form.set('file', new Blob([bytes], { type: 'audio/wav' }), 'sample.wav');
  form.set('model', 'whisper-large-v3');
  form.set('response_format', 'verbose_json');
  form.set('temperature', '0');
  form.append('timestamp_granularities[]', 'word');
  form.append('timestamp_granularities[]', 'segment');
  let upstream: Response;
  try {
    upstream = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
      method: 'POST', headers: { Authorization: `Bearer ${env.GROQ_API_KEY}` }, body: form, signal: AbortSignal.timeout(25_000),
    });
  } catch { throw new ServiceError('SPEECH_UNAVAILABLE', 'Audio matching timed out', 503, true); }
  if (!upstream.ok) { await upstream.body?.cancel(); throw new ServiceError('SPEECH_UNAVAILABLE', 'Audio matching is temporarily unavailable', 503, true); }
  const raw = await boundedBytes(upstream.body, 65_536);
  let json: unknown;
  try { json = JSON.parse(new TextDecoder().decode(raw)); } catch { throw new ServiceError('INVALID_SPEECH_RESPONSE', 'Speech timing was unavailable', 502, true); }
  const response = Response.json(speechTimingWords(json, duration), { headers: { 'Cache-Control': 'public, max-age=86400' } });
  await caches.default.put(cacheKey, response.clone());
  return response;
}
