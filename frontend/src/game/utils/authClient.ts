import { getApiBaseUrl } from './apiConfig';

const TOKEN_KEY = 'rummy_auth_token';
const PLAYER_ID_KEY = 'rummy_player_id';
const ADMIN_KEY_STORAGE = 'rummy_admin_key';
const REFRESH_WHEN_REMAINING_MS = 7 * 24 * 60 * 60 * 1000;

interface TokenResponse {
  token: string;
  playerId: string;
  displayName?: string;
}

interface TokenClaims {
  sub?: string;
  exp?: number;
}

function decodeClaims(token: string): TokenClaims | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    return JSON.parse(atob(padded)) as TokenClaims;
  } catch {
    return null;
  }
}

export function getAuthToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Player id the server issued; the only id the backend will accept for this browser. */
export function getAuthenticatedPlayerId(): string | null {
  const token = getAuthToken();
  return token ? decodeClaims(token)?.sub ?? null : null;
}

function storeToken(response: TokenResponse): string {
  try {
    localStorage.setItem(TOKEN_KEY, response.token);
    localStorage.setItem(PLAYER_ID_KEY, response.playerId);
    sessionStorage.setItem(PLAYER_ID_KEY, response.playerId);
  } catch {
    // storage unavailable (private mode) — token still usable for this page load
  }
  return response.token;
}

let inflight: Promise<string | null> | null = null;

/**
 * Returns a valid JWT, minting a guest identity on first visit and refreshing
 * tokens that are close to expiry. Concurrent callers share one request.
 */
export function ensureAuthToken(displayName?: string): Promise<string | null> {
  if (inflight) return inflight;
  inflight = (async () => {
    const existing = getAuthToken();
    const expMs = existing ? (decodeClaims(existing)?.exp ?? 0) * 1000 : 0;
    const now = Date.now();
    const existingValid = !!existing && expMs > now;

    if (existingValid && expMs - now > REFRESH_WHEN_REMAINING_MS) {
      return existing;
    }

    const base = getApiBaseUrl();
    try {
      if (existingValid) {
        const res = await fetch(`${base}/api/auth/refresh`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${existing}`, 'Content-Type': 'application/json' },
          body: JSON.stringify(displayName ? { name: displayName } : {}),
        });
        if (res.ok) return storeToken(await res.json());
        if (res.status !== 401) return existing;
      }

      const res = await fetch(`${base}/api/auth/guest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: displayName || 'Player' }),
      });
      if (res.ok) return storeToken(await res.json());
    } catch {
      // backend offline — keep whatever token we still have
    }
    return existingValid ? existing : null;
  })().finally(() => {
    inflight = null;
  });
  return inflight;
}

/** fetch() that attaches the player's bearer token. */
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = await ensureAuthToken();
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  return fetch(input, { ...init, headers });
}

/** fetch() for operator endpoints; the key is entered manually and kept only in this browser. */
export function adminFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  let key: string | null = null;
  try {
    key = localStorage.getItem(ADMIN_KEY_STORAGE);
  } catch {
    key = null;
  }
  if (key) headers.set('X-Admin-Key', key);
  return fetch(input, { ...init, headers });
}

export function setAdminKey(key: string | null): void {
  try {
    if (key) localStorage.setItem(ADMIN_KEY_STORAGE, key);
    else localStorage.removeItem(ADMIN_KEY_STORAGE);
  } catch {
    // ignore
  }
}
