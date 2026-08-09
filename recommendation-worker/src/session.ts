import { DurableObject } from 'cloudflare:workers';
import { RecommendationEnv, RecommendationResult, ServiceError } from './types';

const SESSION_TTL_MS = 30 * 60 * 1000;

interface CursorPayload { v: 1; sessionId: string; requestId: string; fingerprint: string; offset: number }
interface SessionMeta { fingerprint: string; resultCount: number; expiresAt: number }

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
    });
  }

  private readMeta(): SessionMeta | null {
    const rows = [...this.ctx.storage.sql.exec<{ fingerprint: string; resultCount: number; expiresAt: number }>('SELECT fingerprint, result_count AS resultCount, expires_at AS expiresAt FROM session_meta WHERE id = 1')];
    return rows[0] ? { fingerprint: rows[0].fingerprint, resultCount: rows[0].resultCount, expiresAt: rows[0].expiresAt } : null;
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

  async store(fingerprint: string, results: RecommendationResult[]): Promise<void> {
    const existing = this.readMeta();
    if (existing?.fingerprint && existing.fingerprint !== fingerprint) throw new ServiceError('REQUEST_ID_CONFLICT', 'This request ID belongs to a different request', 409, false);
    const expiresAt = Date.now() + SESSION_TTL_MS;
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec('DELETE FROM ranked_results');
      for (let index = 0; index < results.length; index++) {
        this.ctx.storage.sql.exec('INSERT INTO ranked_results (position, result_json) VALUES (?, ?)', index, JSON.stringify(results[index]));
      }
      this.ctx.storage.sql.exec('INSERT OR REPLACE INTO session_meta (id, fingerprint, result_count, expires_at) VALUES (1, ?, ?, ?)', fingerprint, results.length, expiresAt);
    });
    await this.ctx.storage.setAlarm(expiresAt);
  }

  async getPage(fingerprint: string, offset: number, pageSize: number): Promise<{ results: RecommendationResult[]; nextOffset: number | null }> {
    const meta = this.readMeta();
    if (!meta || meta.expiresAt <= Date.now()) {
      if (meta) await this.ctx.storage.deleteAll();
      throw new ServiceError('SESSION_EXPIRED', 'The recommendation session expired', 410, true);
    }
    if (meta.fingerprint !== fingerprint) throw new ServiceError('INVALID_CURSOR', 'The cursor does not match this request', 400, false);
    const rows = [...this.ctx.storage.sql.exec<{ resultJson: string }>('SELECT result_json AS resultJson FROM ranked_results WHERE position >= ? ORDER BY position LIMIT ?', offset, pageSize)];
    await this.touch();
    const next = offset + rows.length;
    return { results: rows.map(row => JSON.parse(row.resultJson) as RecommendationResult), nextOffset: next < meta.resultCount ? next : null };
  }

  async alarm(): Promise<void> { await this.ctx.storage.deleteAll(); }
}

export async function requestFingerprint(request: object): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(request)));
  return base64Url(new Uint8Array(digest));
}
