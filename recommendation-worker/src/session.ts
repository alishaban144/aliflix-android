import { DurableObject } from 'cloudflare:workers';
import { RecommendationEnv, RecommendationResult, ServiceError } from './types';

const SESSION_TTL_MS = 30 * 60 * 1000;
export const MAX_GENERATED_CONTINUATION_PASSES = 6;

interface CursorPayload { v: 1; sessionId: string; requestId: string; fingerprint: string; offset: number }
interface SessionMeta { fingerprint: string; resultCount: number; expiresAt: number }
interface ContinuationMeta { enabled: boolean; nextPass: number; emptyPasses: number; exhausted: boolean }

export interface ContinuationReservation {
  canExpand: boolean;
  pass: number | null;
  excludedTmdbIds: number[];
  excludedTitles: string[];
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function decodeBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
  return Uint8Array.from(atob(padded), character => character.charCodeAt(0));
}

async function signingKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

export async function createCursor(secret: string, payload: CursorPayload): Promise<string> {
  const encoded = base64Url(new TextEncoder().encode(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign('HMAC', await signingKey(secret), new TextEncoder().encode(encoded));
  return `${encoded}.${base64Url(new Uint8Array(signature))}`;
}

export async function parseCursor(secret: string, cursor: string): Promise<CursorPayload> {
  const [encoded, signature, extra] = cursor.split('.');
  if (!encoded || !signature || extra) throw new ServiceError('INVALID_CURSOR', 'The cursor is invalid', 400, false);
  const valid = await crypto.subtle.verify('HMAC', await signingKey(secret), decodeBase64Url(signature), new TextEncoder().encode(encoded));
  if (!valid) throw new ServiceError('INVALID_CURSOR', 'The cursor signature is invalid', 400, false);
  try {
    const value = JSON.parse(new TextDecoder().decode(decodeBase64Url(encoded))) as CursorPayload;
    if (value.v !== 1 || !value.sessionId || !value.requestId || !value.fingerprint || !Number.isSafeInteger(value.offset) || value.offset < 0) throw new Error();
    return value;
  } catch {
    throw new ServiceError('INVALID_CURSOR', 'The cursor payload is invalid', 400, false);
  }
}

export class RecommendationSession extends DurableObject<RecommendationEnv> {
  constructor(ctx: DurableObjectState, env: RecommendationEnv) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS session_meta (id INTEGER PRIMARY KEY CHECK (id = 1), fingerprint TEXT NOT NULL, result_count INTEGER NOT NULL, expires_at INTEGER NOT NULL)');
      this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS ranked_results (position INTEGER PRIMARY KEY, result_json TEXT NOT NULL)');
      this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS continuation_state (id INTEGER PRIMARY KEY CHECK (id = 1), enabled INTEGER NOT NULL, next_pass INTEGER NOT NULL, empty_passes INTEGER NOT NULL, exhausted INTEGER NOT NULL)');
    });
  }

  private readMeta(): SessionMeta | null {
    const rows = [...this.ctx.storage.sql.exec<{ fingerprint: string; resultCount: number; expiresAt: number }>('SELECT fingerprint, result_count AS resultCount, expires_at AS expiresAt FROM session_meta WHERE id = 1')];
    return rows[0] ? { fingerprint: rows[0].fingerprint, resultCount: rows[0].resultCount, expiresAt: rows[0].expiresAt } : null;
  }

  private readContinuation(): ContinuationMeta {
    const rows = [...this.ctx.storage.sql.exec<{
      enabled: number;
      nextPass: number;
      emptyPasses: number;
      exhausted: number;
    }>('SELECT enabled, next_pass AS nextPass, empty_passes AS emptyPasses, exhausted FROM continuation_state WHERE id = 1')];
    return rows[0]
      ? {
          enabled: rows[0].enabled === 1,
          nextPass: rows[0].nextPass,
          emptyPasses: rows[0].emptyPasses,
          exhausted: rows[0].exhausted === 1,
        }
      : { enabled: false, nextPass: 1, emptyPasses: 0, exhausted: true };
  }

  private async touch(): Promise<void> {
    const expiresAt = Date.now() + SESSION_TTL_MS;
    this.ctx.storage.sql.exec('UPDATE session_meta SET expires_at = ? WHERE id = 1', expiresAt);
    await this.ctx.storage.setAlarm(expiresAt);
  }

  async getStatus(fingerprint: string): Promise<{ exists: boolean; count: number }> {
    const meta = this.readMeta();
    if (!meta) return { exists: false, count: 0 };
    if (meta.expiresAt <= Date.now()) {
      await this.ctx.storage.deleteAll();
      return { exists: false, count: 0 };
    }
    if (meta.fingerprint !== fingerprint) throw new ServiceError('REQUEST_ID_CONFLICT', 'This request ID belongs to a different request', 409, false);
    await this.touch();
    return { exists: true, count: meta.resultCount };
  }

  async store(fingerprint: string, results: RecommendationResult[], expandable = false): Promise<void> {
    const existing = this.readMeta();
    if (existing?.fingerprint && existing.fingerprint !== fingerprint) throw new ServiceError('REQUEST_ID_CONFLICT', 'This request ID belongs to a different request', 409, false);
    const expiresAt = Date.now() + SESSION_TTL_MS;
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec('DELETE FROM ranked_results');
      for (let index = 0; index < results.length; index++) {
        this.ctx.storage.sql.exec('INSERT INTO ranked_results (position, result_json) VALUES (?, ?)', index, JSON.stringify(results[index]));
      }
      this.ctx.storage.sql.exec('INSERT OR REPLACE INTO session_meta (id, fingerprint, result_count, expires_at) VALUES (1, ?, ?, ?)', fingerprint, results.length, expiresAt);
      this.ctx.storage.sql.exec(
        'INSERT OR REPLACE INTO continuation_state (id, enabled, next_pass, empty_passes, exhausted) VALUES (1, ?, 1, 0, ?)',
        expandable ? 1 : 0,
        expandable ? 0 : 1,
      );
    });
    await this.ctx.storage.setAlarm(expiresAt);
  }

  async reserveContinuation(fingerprint: string): Promise<ContinuationReservation> {
    const meta = this.readMeta();
    if (!meta || meta.expiresAt <= Date.now()) {
      if (meta) await this.ctx.storage.deleteAll();
      throw new ServiceError('SESSION_EXPIRED', 'The recommendation session expired', 410, true);
    }
    if (meta.fingerprint !== fingerprint) {
      throw new ServiceError('INVALID_CURSOR', 'The cursor does not match this request', 400, false);
    }
    const continuation = this.readContinuation();
    if (
      !continuation.enabled ||
      continuation.exhausted ||
      continuation.nextPass > MAX_GENERATED_CONTINUATION_PASSES
    ) {
      await this.touch();
      return { canExpand: false, pass: null, excludedTmdbIds: [], excludedTitles: [] };
    }

    const rows = [...this.ctx.storage.sql.exec<{ resultJson: string }>(
      'SELECT result_json AS resultJson FROM ranked_results ORDER BY position',
    )];
    const stored = rows.map(row => JSON.parse(row.resultJson) as RecommendationResult);
    const pass = continuation.nextPass;
    this.ctx.storage.sql.exec('UPDATE continuation_state SET next_pass = ? WHERE id = 1', pass + 1);
    await this.touch();
    return {
      canExpand: true,
      pass,
      excludedTmdbIds: [...new Set(stored.map(result => result.tmdbId))],
      excludedTitles: [...new Set(stored.flatMap(result => [result.title, result.originalTitle || '']).filter(Boolean))],
    };
  }

  async completeContinuation(
    fingerprint: string,
    pass: number,
    results: RecommendationResult[],
  ): Promise<{ added: number; count: number; exhausted: boolean }> {
    const meta = this.readMeta();
    if (!meta || meta.expiresAt <= Date.now()) {
      if (meta) await this.ctx.storage.deleteAll();
      throw new ServiceError('SESSION_EXPIRED', 'The recommendation session expired', 410, true);
    }
    if (meta.fingerprint !== fingerprint) {
      throw new ServiceError('INVALID_CURSOR', 'The cursor does not match this request', 400, false);
    }
    if (!Number.isSafeInteger(pass) || pass < 1 || pass > MAX_GENERATED_CONTINUATION_PASSES) {
      throw new ServiceError('INVALID_CURSOR', 'The continuation pass is invalid', 400, false);
    }

    const existingRows = [...this.ctx.storage.sql.exec<{ resultJson: string }>(
      'SELECT result_json AS resultJson FROM ranked_results ORDER BY position',
    )];
    const seen = new Set(existingRows.map(row => {
      const result = JSON.parse(row.resultJson) as RecommendationResult;
      return `${result.mediaType}:${result.tmdbId}`;
    }));
    const fresh = results.filter(result => {
      const key = `${result.mediaType}:${result.tmdbId}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    const continuation = this.readContinuation();
    const emptyPasses = fresh.length ? 0 : continuation.emptyPasses + 1;
    const exhausted = continuation.exhausted ||
      continuation.nextPass > MAX_GENERATED_CONTINUATION_PASSES ||
      emptyPasses >= 2;
    const count = meta.resultCount + fresh.length;

    this.ctx.storage.transactionSync(() => {
      fresh.forEach((result, index) => {
        this.ctx.storage.sql.exec(
          'INSERT INTO ranked_results (position, result_json) VALUES (?, ?)',
          meta.resultCount + index,
          JSON.stringify(result),
        );
      });
      this.ctx.storage.sql.exec('UPDATE session_meta SET result_count = ? WHERE id = 1', count);
      this.ctx.storage.sql.exec(
        'UPDATE continuation_state SET empty_passes = ?, exhausted = ? WHERE id = 1',
        emptyPasses,
        exhausted ? 1 : 0,
      );
    });
    await this.touch();
    return { added: fresh.length, count, exhausted };
  }

  async releaseContinuation(fingerprint: string, pass: number): Promise<void> {
    const meta = this.readMeta();
    if (!meta || meta.expiresAt <= Date.now()) return;
    if (meta.fingerprint !== fingerprint) {
      throw new ServiceError('INVALID_CURSOR', 'The cursor does not match this request', 400, false);
    }
    const continuation = this.readContinuation();
    // Only roll back the reservation when no newer request has already claimed
    // another pass. A retry then explores the same deterministic TMDB lanes.
    if (continuation.nextPass === pass + 1) {
      this.ctx.storage.sql.exec('UPDATE continuation_state SET next_pass = ? WHERE id = 1', pass);
    }
    await this.touch();
  }

  async getPage(
    fingerprint: string,
    offset: number,
    pageSize: number,
  ): Promise<{ results: RecommendationResult[]; nextOffset: number | null; totalCount: number }> {
    const meta = this.readMeta();
    if (!meta || meta.expiresAt <= Date.now()) {
      if (meta) await this.ctx.storage.deleteAll();
      throw new ServiceError('SESSION_EXPIRED', 'The recommendation session expired', 410, true);
    }
    if (meta.fingerprint !== fingerprint) throw new ServiceError('INVALID_CURSOR', 'The cursor does not match this request', 400, false);
    const rows = [...this.ctx.storage.sql.exec<{ resultJson: string }>('SELECT result_json AS resultJson FROM ranked_results WHERE position >= ? ORDER BY position LIMIT ?', offset, pageSize)];
    await this.touch();
    const next = offset + rows.length;
    const continuation = this.readContinuation();
    const canExpand = continuation.enabled &&
      !continuation.exhausted &&
      continuation.nextPass <= MAX_GENERATED_CONTINUATION_PASSES;
    return {
      results: rows.map(row => JSON.parse(row.resultJson) as RecommendationResult),
      nextOffset: next < meta.resultCount || canExpand ? next : null,
      totalCount: meta.resultCount,
    };
  }

  async alarm(): Promise<void> { await this.ctx.storage.deleteAll(); }
}

export async function requestFingerprint(request: object): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(request)));
  return base64Url(new Uint8Array(digest));
}
