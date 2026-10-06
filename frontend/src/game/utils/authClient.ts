import { getApiBaseUrl } from './apiConfig';

const TOKEN_KEY = 'rummy_auth_token';
const PLAYER_ID_KEY = 'rummy_player_id';
const DISPLAY_NAME_KEY = 'rummy_display_name';
const REFRESH_WHEN_REMAINING_MS = 2 * 60 * 60 * 1000;

export interface TokenResponse {
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
    if (response.displayName) {
      localStorage.setItem(DISPLAY_NAME_KEY, response.displayName);
      sessionStorage.setItem(DISPLAY_NAME_KEY, response.displayName);
    }
  } catch {
    // storage unavailable (private mode) — token still usable for this page load
  }
  return response.token;
}

function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
}

/**
 * Removes the one-time launch code from the address bar (so it is not bookmarked or shared)
 * and returns it.
 */
export function takeLaunchCodeFromUrl(): string | null {
  try {
    const url = new URL(window.location.href);
    const code = url.searchParams.get('launch');
    if (!code) return null;
    url.searchParams.delete('launch');
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
    return code;
  } catch {
    return null;
  }
}

/** Exchanges an operator launch code for a session. Null if the link expired or was already used. */
export async function redeemLaunchCode(code: string): Promise<TokenResponse | null> {
  try {
    const res = await fetch(`${getApiBaseUrl()}/api/operator/session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
    if (!res.ok) return null;
    const session = (await res.json()) as TokenResponse;
    storeToken(session);
    return session;
  } catch {
    return null;
  }
}

let inflight: Promise<string | null> | null = null;

/**
 * Returns a valid session token, refreshing it when close to expiry, or null when the player has no
 * session (they must open the game from their operator). Concurrent callers share one request.
 */
export function ensureAuthToken(displayName?: string): Promise<string | null> {
  if (inflight) return inflight;
  inflight = (async () => {
    const existing = getAuthToken();
    const expMs = existing ? (decodeClaims(existing)?.exp ?? 0) * 1000 : 0;
    const now = Date.now();
    if (!existing || expMs <= now) {
      if (existing) clearToken();
      return null;
    }
    if (expMs - now > REFRESH_WHEN_REMAINING_MS) {
      return existing;
    }
    try {
      const res = await fetch(`${getApiBaseUrl()}/api/auth/refresh`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${existing}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(displayName ? { name: displayName } : {}),
      });
      if (res.ok) return storeToken(await res.json());
    } catch {
      // backend offline — the current token is still valid for now
    }
    return existing;
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
