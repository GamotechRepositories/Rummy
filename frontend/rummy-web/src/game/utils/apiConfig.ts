export const getApiBaseUrl = (): string => {
  if (typeof window !== 'undefined' && window.location.hostname) {
    return `http://${window.location.hostname}:8081`;
  }
  return 'http://localhost:8081';
};

export const getWsBaseUrl = (): string => {
  const envUrl = (import.meta as unknown as { env?: { VITE_WS_URL?: string } }).env?.VITE_WS_URL;
  if (envUrl) return envUrl;
  if (typeof window !== 'undefined' && window.location.hostname) {
    return `ws://${window.location.hostname}:8081/ws/game`;
  }
  return 'ws://localhost:8081/ws/game';
};
