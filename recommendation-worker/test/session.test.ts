import { env } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { createCursor, parseCursor, RecommendationSession } from '../src/session';
import { RecommendationResult } from '../src/types';

const result = (id: number): RecommendationResult => ({
  tmdbId: id, mediaType: 'movie', title: `Movie ${id}`, genres: ['Comedy'], originCountries: [], tmdbRating: 7,
  tmdbVoteCount: 100, matchLevel: 'Relevant', finalScore: .5, matchReasons: ['Concept match'], retrievalSources: ['discover:test'],
});

describe('recommendation sessions', () => {
  it('stores a ranked pool once and returns stable non-overlapping pages through exhaustion', async () => {
    const namespace = (env as any).RECOMMENDATION_SESSIONS as DurableObjectNamespace<RecommendationSession>;
    const stub = namespace.get(namespace.idFromName(crypto.randomUUID())) as DurableObjectStub<RecommendationSession>;
    const values = Array.from({ length: 45 }, (_, index) => result(index + 1));
    await stub.store('fingerprint', values);
    expect(await stub.getStatus('fingerprint')).toEqual({ exists: true, count: 45 });
    const first = await stub.getPage('fingerprint', 0, 20);
    const second = await stub.getPage('fingerprint', first.nextOffset!, 20);
    const last = await stub.getPage('fingerprint', second.nextOffset!, 20);
    expect(first.results.map(item => item.tmdbId)).toEqual(Array.from({ length: 20 }, (_, i) => i + 1));
    expect(second.results[0].tmdbId).toBe(21);
    expect(last.results).toHaveLength(5);
    expect(last.nextOffset).toBeNull();
  });

  it('signs cursors and rejects tampering', async () => {
    const cursor = await createCursor('secret', { v: 1, sessionId: 's', requestId: 'r', fingerprint: 'f', offset: 20 });
    expect((await parseCursor('secret', cursor)).offset).toBe(20);
    await expect(parseCursor('secret', `${cursor}x`)).rejects.toMatchObject({ code: 'INVALID_CURSOR' });
  });
});
