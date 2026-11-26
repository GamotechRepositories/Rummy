/**
 * Client SDK for Browser matching @gamotech/game-sdk-client specification.
 * Handles session validation and session lifecycle events with the Provider Gateway.
 * Reference: sdk-client.md and game-integration.md
 */

export interface SessionData {
  sessionToken: string;
  operatorId: string;
  playerId: string;
  playerUsername: string;
  gameCode: string;
  currency: string;
  language?: string;
  timezone?: string;
  status?: string;
  gameContext?: {
    tableId: string | null;
    currentRoundId: string | null;
  };
  createdAt?: string;
  expiresAt?: string;
}

export class ProviderSDKError extends Error {
  status?: number;
  data?: any;

  constructor(message: string, status?: number, data?: any) {
    super(message);
    this.name = 'ProviderSDKError';
    this.status = status;
    this.data = data;
  }
}

export class ProviderGameSDK {
  public apiBaseUrl: string;
  public sessionToken: string | null = null;
  public session: SessionData | null = null;

  constructor(options?: { apiBaseUrl?: string }) {
    this.apiBaseUrl =
      options?.apiBaseUrl ||
      (import.meta as any).env?.VITE_PROVIDER_API_URL ||
      'https://api.dpbossking.com/api/v1';
    if (this.apiBaseUrl.endsWith('/')) {
      this.apiBaseUrl = this.apiBaseUrl.slice(0, -1);
    }
  }

  get operatorId(): string | undefined {
    return this.session?.operatorId;
  }

  get playerId(): string | undefined {
    return this.session?.playerId;
  }

  get playerUsername(): string | undefined {
    return this.session?.playerUsername;
  }

  get gameCode(): string | undefined {
    return this.session?.gameCode;
  }

  get currency(): string | undefined {
    return this.session?.currency;
  }

  public async initFromUrl(search?: string): Promise<SessionData> {
    const query = search || (typeof window !== 'undefined' ? window.location.search : '');
    const token = new URLSearchParams(query).get('sessionToken');
    if (!token) {
      throw new ProviderSDKError('Missing sessionToken in URL query params');
    }
    return this.init(token);
  }

  public async init(sessionToken: string): Promise<SessionData> {
    this.sessionToken = sessionToken;
    try {
      const res = await fetch(`${this.apiBaseUrl}/sessions/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionToken }),
      });
      const data = await res.json();
      if (!res.ok || !data.success) {
        throw new ProviderSDKError(data.message || 'Session invalid', res.status, data);
      }
      this.session = data.session as SessionData;
      return this.session;
    } catch (err: any) {
      if (err instanceof ProviderSDKError) throw err;
      throw new ProviderSDKError(err.message || 'Failed to connect to Provider API');
    }
  }

  public async createTable(payload: { tableId?: string; [key: string]: any } = {}): Promise<any> {
    const tableId = payload.tableId || `table_${Date.now()}`;
    return this.sendEvent('TABLE_CREATED', { tableId, payload });
  }

  public async createRound(payload: { roundId?: string; [key: string]: any } = {}): Promise<string> {
    const roundId = payload.roundId || `round_${Date.now()}`;
    await this.sendEvent('ROUND_CREATED', { roundId, payload });
    return roundId;
  }

  public async startRound(payload: { roundId: string; betAmount?: number; [key: string]: any }): Promise<any> {
    return this.sendEvent('ROUND_STARTED', { roundId: payload.roundId, payload });
  }

  public async endRound(payload: { roundId: string; result?: string; payout?: number; [key: string]: any }): Promise<any> {
    return this.sendEvent('ROUND_ENDED', { roundId: payload.roundId, payload });
  }

  public async endSession(payload: { reason?: string; [key: string]: any } = { reason: 'PLAYER_LEFT' }): Promise<any> {
    return this.sendEvent('SESSION_ENDED', { payload });
  }

  public async sendEvent(
    event: string,
    opts: { tableId?: string; roundId?: string; payload?: any } = {}
  ): Promise<any> {
    if (!this.sessionToken) {
      return; // If not initialized, silently skip
    }
    try {
      const res = await fetch(`${this.apiBaseUrl}/sessions/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionToken: this.sessionToken,
          event,
          tableId: opts.tableId,
          roundId: opts.roundId,
          payload: opts.payload || {},
        }),
      });
      const data = await res.json();
      return data;
    } catch (err) {
      // Non-blocking telemetry
      console.warn('[ProviderGameSDK] Event reporting failed:', event, err);
    }
  }

  public attachUnloadHandler(): void {
    if (typeof window === 'undefined') return;
    window.addEventListener('beforeunload', () => {
      if (!this.sessionToken) return;
      try {
        navigator.sendBeacon(
          `${this.apiBaseUrl}/sessions/events`,
          JSON.stringify({
            sessionToken: this.sessionToken,
            event: 'SESSION_ENDED',
            payload: { reason: 'PAGE_UNLOAD' },
          })
        );
      } catch {
        // ignore
      }
    });
  }
}

export const providerClientSDK = new ProviderGameSDK();
