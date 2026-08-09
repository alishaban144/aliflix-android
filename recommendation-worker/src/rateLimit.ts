export class RateLimiter {
  private requestCounts: Map<string, { count: number; lastReset: number }> = new Map();
  private readonly limit: number;
  private readonly windowMs: number;

  constructor(limit: number = 30, windowMs: number = 60000) {
    this.limit = limit;
    this.windowMs = windowMs;
  }

  public check(ip: string, strict: boolean = false): boolean {
    const now = Date.now();
    const record = this.requestCounts.get(ip) || { count: 0, lastReset: now };

    if (now - record.lastReset > this.windowMs) {
      record.count = 0;
      record.lastReset = now;
    }

    const currentLimit = strict ? Math.floor(this.limit / 3) : this.limit;

    if (record.count >= currentLimit) {
      return false;
    }

    record.count++;
    this.requestCounts.set(ip, record);
    return true;
  }
}

export const rateLimiter = new RateLimiter();
