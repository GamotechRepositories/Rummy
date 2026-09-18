/**
 * API / WebSocket base URLs for local + Render.
 *
 * Render Static Site env (exact — no extra https/wss mix):
 *   VITE_API_BASE_URL=https://rummy-backend-bj5l.onrender.com
 *   VITE_WS_URL=wss://rummy-backend-bj5l.onrender.com/ws/game
 */

function trimSlash(url: string): string {
  return url.trim().replace(/\/$/, '');
}

/** Accepts https://host or host → https://host (no path). */
export const getApiBaseUrl = (): string => {
  const raw = (import.meta as unknown as { env?: { VITE_API_BASE_URL?: string } }).env
    ?.VITE_API_BASE_URL;

  if (raw) {
    let url = trimSlash(raw);
    // If someone pasted wss/ws by mistake, convert to https/http
    url = url.replace(/^wss:\/\//i, 'https://').replace(/^ws:\/\//i, 'http://');
    if (!/^https?:\/\//i.test(url)) {
      url = `https://${url.replace(/^\/+/, '')}`;
    }
    return url;
  }

  if (import.meta.env.DEV && typeof window !== 'undefined') {
    return `http://${window.location.hostname}:8081`;
  }

  if (typeof window !== 'undefined') {
    return window.location.origin;
  }
  return 'http://localhost:8081';
};

/** Always returns a clean ws/wss URL ending with /ws/game */
export const getWsBaseUrl = (): string => {
  const raw = (import.meta as unknown as { env?: { VITE_WS_URL?: string } }).env?.VITE_WS_URL;

  if (raw) {
    let url = trimSlash(raw);

    // Fix common mistakes: wss://https://host  OR  https://host
    url = url.replace(/^wss:\/\/https:\/\//i, 'wss://');
    url = url.replace(/^wss:\/\/http:\/\//i, 'ws://');
    url = url.replace(/^ws:\/\/https:\/\//i, 'wss://');
    url = url.replace(/^ws:\/\/http:\/\//i, 'ws://');
    url = url.replace(/^wss:\/\/https\/\//i, 'wss://');
    url = url.replace(/^https:\/\//i, 'wss://');
    url = url.replace(/^http:\/\//i, 'ws://');

    if (!/^wss?:\/\//i.test(url)) {
      url = `wss://${url.replace(/^\/+/, '')}`;
    }

    if (!/\/ws\/game$/i.test(url)) {
      url = `${url.replace(/\/$/, '')}/ws/game`;
    }

    return url;
  }

  if (import.meta.env.DEV && typeof window !== 'undefined') {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${proto}://${window.location.hostname}:8081/ws/game`;
  }

  if (typeof window !== 'undefined') {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${proto}://${window.location.host}/ws/game`;
  }

  return 'ws://localhost:8081/ws/game';
};
