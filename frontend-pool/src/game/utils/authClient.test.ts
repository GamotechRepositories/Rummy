import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  authFetch,
  clearAuthToken,
  ensureAuthToken,
  getAuthToken,
  getAuthenticatedPlayerId,
} from './authClient';

class MemoryStorage implements Storage {
  private store: Record<string, string> = {};
  get length() {
    return Object.keys(this.store).length;
  }
  clear() {
    this.store = {};
  }
  getItem(key: string) {
    return this.store[key] ?? null;
  }
  setItem(key: string, value: string) {
    this.store[key] = String(value);
  }
  removeItem(key: string) {
    delete this.store[key];
  }
  key(index: number) {
    return Object.keys(this.store)[index] ?? null;
  }
}

if (typeof globalThis.localStorage === 'undefined') {
  (globalThis as unknown as { localStorage: Storage }).localStorage = new MemoryStorage();
}
if (typeof globalThis.sessionStorage === 'undefined') {
  (globalThis as unknown as { sessionStorage: Storage }).sessionStorage = new MemoryStorage();
}

function makeJwt(sub: string, offsetSeconds: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const exp = Math.floor(Date.now() / 1000) + offsetSeconds;
  const payload = btoa(JSON.stringify({ sub, exp }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `${header}.${payload}.sig`;
}

describe('authClient token lifecycle', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('clears stale auth token when refresh returns 401', async () => {
    // Expiring in 30 minutes (< 2h refresh threshold) triggers /api/auth/refresh
    const expiringSoon = makeJwt('P_STALE', 30 * 60);
    localStorage.setItem('rummy_auth_token', expiringSoon);
    localStorage.setItem('rummy_player_id', 'P_STALE');

    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 })) as unknown as typeof fetch;

    const token = await ensureAuthToken();
    expect(token).toBeNull();
    expect(getAuthToken()).toBeNull();
    expect(getAuthenticatedPlayerId()).toBeNull();
  });

  it('clears auth token when an API request receives 401 Unauthorized', async () => {
    const valid = makeJwt('P_ACTIVE', 6 * 3600);
    localStorage.setItem('rummy_auth_token', valid);

    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 })) as unknown as typeof fetch;

    const res = await authFetch('http://localhost:8081/api/wallet/balance');
    expect(res.status).toBe(401);
    expect(getAuthToken()).toBeNull();
  });

  it('clearAuthToken clears both localStorage and sessionStorage identity keys', () => {
    localStorage.setItem('rummy_auth_token', 'tok');
    localStorage.setItem('rummy_player_id', 'P1');
    sessionStorage.setItem('rummy_player_id', 'P1');
    sessionStorage.setItem('rummy_display_name', 'Ravi');

    clearAuthToken();

    expect(localStorage.getItem('rummy_auth_token')).toBeNull();
    expect(localStorage.getItem('rummy_player_id')).toBeNull();
    expect(sessionStorage.getItem('rummy_player_id')).toBeNull();
    expect(sessionStorage.getItem('rummy_display_name')).toBeNull();
  });
});
