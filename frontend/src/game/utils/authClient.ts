import { getApiBaseUrl } from './apiConfig';

const TOKEN_KEY = 'rummy_auth_token';
const PLAYER_ID_KEY = 'rummy_player_id';
const DISPLAY_NAME_KEY = 'rummy_display_name';
const CLOSE_URL_KEY = 'rummy_close_url';
const CURRENCY_KEY = 'rummy_currency';
const LANG_KEY = 'rummy_lang';
const REFRESH_WHEN_REMAINING_MS = 2 * 60 * 60 * 1000;

export const SESSION_INVALID_EVENT = 'rummy:session-invalid';

export interface SessionInvalidDetail {
  message: string;
  errorCode?: string;
  closeUrl?: string | null;
}

export interface OperationsLaunchParams {
  token?: string;
  partnerCode?: string;
  gid?: string;
  gameCode?: string;
  username?: string;
  userId?: string;
  currency?: string;
  lang?: string;
  extsess?: string;
  closeUrl?: string;
  code?: string;
}

export interface TokenResponse {
  token: string;
  playerId: string;
  displayName?: string;
  balance?: number;
  partnerCode?: string;
  gid?: string;
  gameCode?: string;
  currency?: string;
  lang?: string;
  closeUrl?: string;
}

export type LaunchExchangeResult =
  | { ok: true; session: TokenResponse }
  | { ok: false; error: string; errorCode?: string; message?: string; closeUrl?: string | null };

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

function storeMeta(key: string, value: string | null | undefined): void {
  try {
    if (value && value.trim()) {
      localStorage.setItem(key, value.trim());
      sessionStorage.setItem(key, value.trim());
    }
  } catch {
    // ignore storage errors
  }
}

function readMeta(key: string): string | null {
  try {
    return sessionStorage.getItem(key) ?? localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function sanitizeHttpUrl(raw?: string | null): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > 1024) {
    return null;
  }
  for (let i = 0; i < trimmed.length; i++) {
    const code = trimmed.charCodeAt(i);
    if (code < 0x20 || code === 0x7f) {
      return null;
    }
  }
  try {
    const parsed = new URL(trimmed);
    const proto = parsed.protocol.toLowerCase();
    if ((proto === 'http:' || proto === 'https:') && parsed.host) {
      return trimmed;
    }
  } catch {
    return null;
  }
  return null;
}

export function getCloseUrl(): string | null {
  return sanitizeHttpUrl(readMeta(CLOSE_URL_KEY));
}

export function getCurrencyCode(): string {
  return readMeta(CURRENCY_KEY) || 'INR';
}

export function getLangCode(): string {
  return readMeta(LANG_KEY) || 'en';
}

export function saveLaunchMeta(meta: {
  closeUrl?: string | null;
  currency?: string | null;
  lang?: string | null;
}): void {
  if (meta.closeUrl !== undefined) {
    const safeCloseUrl = sanitizeHttpUrl(meta.closeUrl);
    if (safeCloseUrl) {
      storeMeta(CLOSE_URL_KEY, safeCloseUrl);
    }
  }
  storeMeta(CURRENCY_KEY, meta.currency);
  storeMeta(LANG_KEY, meta.lang);
}

export function formatCurrencySymbol(currency?: string | null): string {
  const code = (currency || 'INR').trim().toUpperCase();
  switch (code) {
    case 'INR':
      return '₹';
    case 'USD':
      return '$';
    case 'EUR':
      return '€';
    case 'GBP':
      return '£';
    default:
      return `${code} `;
  }
}

/** Exits back to the operator lobby (`close_url`) or closes the window/tab per Guide 4.3. */
export function exitToOperatorLobby(explicitCloseUrl?: string | null): void {
  const closeUrl = sanitizeHttpUrl(explicitCloseUrl !== undefined ? explicitCloseUrl : getCloseUrl());
  if (!closeUrl) {
    if (typeof window !== 'undefined') {
      try {
        window.close();
      } catch {
        // ignore
      }
    }
    return;
  }
  try {
    (window.top || window).location.href = closeUrl;
  } catch {
    window.location.href = closeUrl;
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
  saveLaunchMeta({
    closeUrl: response.closeUrl,
    currency: response.currency,
    lang: response.lang,
  });
  return response.token;
}

export function clearAuthToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(PLAYER_ID_KEY);
    localStorage.removeItem(DISPLAY_NAME_KEY);
    sessionStorage.removeItem(PLAYER_ID_KEY);
    sessionStorage.removeItem(DISPLAY_NAME_KEY);
  } catch {
    // ignore
  }
}

/**
 * Reads Operations launch query params (`token`, `partner_code`, `gid`, `game_code`, `username`,
 * `user_id`, `currency`, `lang`, `extsess`, `close_url`) or mock `?launch=`, cleans them from
 * the address bar so credentials are not bookmarked/shared, and returns them.
 */
export function takeLaunchParamsFromUrl(): OperationsLaunchParams | null {
  try {
    const url = new URL(window.location.href);
    const p = url.searchParams;
    const token = p.get('token') || undefined;
    const launchCode = p.get('launch') || undefined;
    if (!token && !launchCode) {
      return null;
    }

    const params: OperationsLaunchParams = {
      token,
      partnerCode: p.get('partner_code') || undefined,
      gid: p.get('gid') || undefined,
      gameCode: p.get('game_code') || undefined,
      username: p.get('username') || undefined,
      userId: p.get('user_id') || undefined,
      currency: p.get('currency') || undefined,
      lang: p.get('lang') || undefined,
      extsess: p.get('extsess') || undefined,
      closeUrl: sanitizeHttpUrl(p.get('close_url')) ?? undefined,
      code: launchCode,
    };

    saveLaunchMeta({
      closeUrl: params.closeUrl,
      currency: params.currency,
      lang: params.lang,
    });

    for (const key of [
      'token',
      'launch',
      'partner_code',
      'gid',
      'game_code',
      'username',
      'user_id',
      'currency',
      'lang',
      'extsess',
      'close_url',
    ]) {
      p.delete(key);
    }
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
    return params;
  } catch {
    return null;
  }
}

/** Forward Operations token / launch code to game backend (never call Operations /api/v1/wallet/* from browser). */
export async function redeemLaunchSession(params: OperationsLaunchParams): Promise<LaunchExchangeResult> {
  try {
    const body: Record<string, string> = {};
    if (params.token) body.token = params.token;
    if (params.partnerCode) body.partner_code = params.partnerCode;
    if (params.gid) body.gid = params.gid;
    if (params.gameCode) body.game_code = params.gameCode;
    if (params.username) body.username = params.username;
    if (params.userId) body.user_id = params.userId;
    if (params.currency) body.currency = params.currency;
    if (params.lang) body.lang = params.lang;
    if (params.extsess) body.extsess = params.extsess;
    if (params.closeUrl) body.close_url = params.closeUrl;
    if (params.code) body.code = params.code;

    const res = await fetch(`${getApiBaseUrl()}/api/operator/session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      const closeUrl = sanitizeHttpUrl(typeof err.closeUrl === 'string' ? err.closeUrl : params.closeUrl);
      if (closeUrl) {
        saveLaunchMeta({ closeUrl });
      }
      return {
        ok: false,
        error: typeof err.error === 'string' ? err.error : 'INVALID_LAUNCH',
        errorCode: typeof err.errorCode === 'string' ? err.errorCode : undefined,
        message: typeof err.message === 'string' ? err.message : undefined,
        closeUrl,
      };
    }
    const session = (await res.json()) as TokenResponse;
    storeToken(session);
    return { ok: true, session };
  } catch {
    return {
      ok: false,
      error: 'NETWORK_ERROR',
      message: 'Could not reach game server to start your session.',
      closeUrl: sanitizeHttpUrl(params.closeUrl),
    };
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
      if (existing) clearAuthToken();
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
      if (res.status === 401 || res.status === 403) {
        clearAuthToken();
        return null;
      }
    } catch {
      // backend offline — the current token is still valid for now
    }
    return existing;
  })().finally(() => {
    inflight = null;
  });
  return inflight;
}

/** fetch() that attaches the player's bearer token and broadcasts Operations SESSION_INVALID responses. */
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = await ensureAuthToken();
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const res = await fetch(input, { ...init, headers });
  if (res.status === 401) {
    clearAuthToken();
    if (typeof window !== 'undefined') {
      try {
        const data = await res.clone().json().catch(() => null) as Record<string, unknown> | null;
        if (data && data.error === 'SESSION_INVALID') {
          const closeUrl = typeof data.closeUrl === 'string' ? data.closeUrl : getCloseUrl();
          if (closeUrl) saveLaunchMeta({ closeUrl });
          window.dispatchEvent(new CustomEvent<SessionInvalidDetail>(SESSION_INVALID_EVENT, {
            detail: {
              message: typeof data.message === 'string'
                ? data.message
                : 'Your game session has expired. Please return to the lobby.',
              errorCode: typeof data.errorCode === 'string' ? data.errorCode : undefined,
              closeUrl,
            },
          }));
        }
      } catch {
        // ignore clone/parse errors on non-JSON 401
      }
    }
  }
  return res;
}
