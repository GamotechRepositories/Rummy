import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  authFetch,
  clearAuthToken,
  ensureAuthToken,
  getAuthToken,
  getAuthenticatedPlayerId,
  getCloseUrl,
  getCurrencyCode,
  getLangCode,
  redeemLaunchSession,
  SESSION_INVALID_EVENT,
  takeLaunchParamsFromUrl,
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
if (typeof globalThis.window === 'undefined') {
  const target = new EventTarget();
  const mockWin = {
    location: new URL('https://play.rummy.example/'),
    history: {
      state: null as unknown,
      replaceState: (_data: unknown, _unused: string, url?: string | URL | null) => {
        if (url) {
          mockWin.location = new URL(String(url), mockWin.location.origin);
        }
      },
    },
    addEventListener: target.addEventListener.bind(target),
    removeEventListener: target.removeEventListener.bind(target),
    dispatchEvent: target.dispatchEvent.bind(target),
  };
  (globalThis as unknown as { window: typeof mockWin }).window = mockWin;
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

describe('authClient token & Operations boot lifecycle', () => {
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

  it('dispatches SESSION_INVALID event while preserving closeUrl on 401 SESSION_INVALID', async () => {
    const valid = makeJwt('P_ACTIVE', 6 * 3600);
    localStorage.setItem('rummy_auth_token', valid);

    const events: CustomEvent[] = [];
    const handler = (ev: Event) => events.push(ev as CustomEvent);
    window.addEventListener(SESSION_INVALID_EVENT, handler);

    globalThis.fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          error: 'SESSION_INVALID',
          errorCode: 'ERR-0011',
          message: 'Session expired',
          closeUrl: 'https://lobby.example.com/return',
        }),
        { status: 401, headers: { 'Content-Type': 'application/json' } }
      )
    ) as unknown as typeof fetch;

    await authFetch('http://localhost:8081/api/matchmaking/join', { method: 'POST' });
    window.removeEventListener(SESSION_INVALID_EVENT, handler);

    expect(getAuthToken()).toBeNull();
    expect(getCloseUrl()).toBe('https://lobby.example.com/return');
    expect(events).toHaveLength(1);
    expect(events[0].detail.errorCode).toBe('ERR-0011');
  });

  it('reads and strips Operations launch parameters from URL and exchanges via backend', async () => {
    const replaceSpy = vi.spyOn(window.history, 'replaceState');
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: new URL(
        'https://play.rummy.example/?token=ops_tok_1&partner_code=ALPHA-TEST&gid=1001&game_code=RUMMY&username=Ravi&user_id=U42&currency=USD&lang=hi&extsess=ext9&close_url=https%3A%2F%2Flobby.partner.example%2Fback'
      ),
    });

    const params = takeLaunchParamsFromUrl();
    expect(params).toEqual({
      token: 'ops_tok_1',
      partnerCode: 'ALPHA-TEST',
      gid: '1001',
      gameCode: 'RUMMY',
      username: 'Ravi',
      userId: 'U42',
      currency: 'USD',
      lang: 'hi',
      extsess: 'ext9',
      closeUrl: 'https://lobby.partner.example/back',
      code: undefined,
    });
    expect(replaceSpy).toHaveBeenCalled();
    expect(window.location.search).toBe('');
    expect(getCloseUrl()).toBe('https://lobby.partner.example/back');
    expect(getCurrencyCode()).toBe('USD');
    expect(getLangCode()).toBe('hi');

    const jwt = makeJwt('P_OPS_1', 6 * 3600);
    globalThis.fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          token: jwt,
          playerId: 'P_OPS_1',
          displayName: 'Ravi',
          balance: 2500.5,
          currency: 'USD',
          lang: 'hi',
          closeUrl: 'https://lobby.partner.example/back',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    ) as unknown as typeof fetch;

    const result = await redeemLaunchSession(params!);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.session.playerId).toBe('P_OPS_1');
      expect(result.session.balance).toBe(2500.5);
    }
    expect(getAuthenticatedPlayerId()).toBe('P_OPS_1');
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

  it('rejects unsafe non-HTTP(S) close_url schemes such as javascript: and data:', () => {
    for (const unsafe of ['javascript:alert(1)', 'data:text/html,<script>alert(1)</script>']) {
      Object.defineProperty(window, 'location', {
        configurable: true,
        writable: true,
        value: new URL(
          `https://play.rummy.example/?token=ops_tok_xss&partner_code=ALPHA-TEST&close_url=${encodeURIComponent(unsafe)}`
        ),
      });

      const params = takeLaunchParamsFromUrl();
      expect(params?.closeUrl).toBeUndefined();
      expect(getCloseUrl()).toBeNull();
      expect(localStorage.getItem('rummy_close_url')).toBeNull();
      expect(sessionStorage.getItem('rummy_close_url')).toBeNull();
    }
  });
});
