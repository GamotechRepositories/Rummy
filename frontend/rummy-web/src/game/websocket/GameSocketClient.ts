import { useGameStore } from '../store/useGameStore';
import type { CardInstance, PlayerGameView, WsClientMessage, WsServerMessage } from '../types/game';
import { soundEngine } from '../audio/soundEngine';
import { getWsBaseUrl } from '../utils/apiConfig';

class GameSocketClient {
  private ws: WebSocket | null = null;
  private reconnectTimer: number | null = null;
  private pingInterval: number | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 10;
  private isExplicitDisconnect = false;
  private socketGeneration = 0;
  private resumeJoinTimer: number | null = null;

  private url: string;

  constructor() {
    this.url = getWsBaseUrl();
  }

  public connect(): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      // Already connected (Strict Mode / late resume flags) — rejoin if needed
      this.ensureTableJoined();
      return;
    }
    if (this.ws && this.ws.readyState === WebSocket.CONNECTING) {
      return;
    }

    this.url = getWsBaseUrl();
    this.isExplicitDisconnect = false;
    useGameStore.getState().setConnectionStatus(
      this.reconnectAttempts > 0 ? 'RECONNECTING' : 'CONNECTING'
    );

    const generation = ++this.socketGeneration;

    try {
      // Check for stored or query token
      const token = localStorage.getItem('rummy_auth_token');
      const wsUrlWithToken = token ? `${this.url}?token=${encodeURIComponent(token)}` : this.url;
      this.ws = new WebSocket(wsUrlWithToken);
    } catch (e) {
      console.error('[WS] Connection creation failed:', e);
      this.scheduleReconnect();
      return;
    }

    this.ws.onopen = () => {
      if (generation !== this.socketGeneration) return;
      console.log('[WS] Connected to game gateway:', this.url);
      useGameStore.getState().setConnectionStatus('CONNECTED');
      this.reconnectAttempts = 0;
      this.startHeartbeat();

      // Automatically authenticate with stored player ID / token
      const { playerId } = useGameStore.getState();
      this.sendMessage({
        type: 'AUTH',
        requestId: 'auth_' + Date.now(),
        payload: { playerId },
      });
    };

    this.ws.onmessage = (event) => {
      if (generation !== this.socketGeneration) return;
      try {
        const msg: WsServerMessage = JSON.parse(event.data);
        this.handleMessage(msg);
      } catch (e) {
        console.error('[WS] Failed to parse inbound frame:', event.data, e);
      }
    };

    this.ws.onclose = (event) => {
      if (generation !== this.socketGeneration) return;
      console.warn('[WS] Disconnected (code: ' + event.code + ', reason: ' + event.reason + ')');
      this.stopHeartbeat();
      useGameStore.getState().setConnectionStatus('DISCONNECTED');
      if (!this.isExplicitDisconnect) {
        this.scheduleReconnect();
      }
    };

    this.ws.onerror = (err) => {
      if (generation !== this.socketGeneration) return;
      console.error('[WS] Error:', err);
    };
  }

  /** Call after setting hasJoinedTable / on AUTH — safe if already joined. */
  public ensureTableJoined(): void {
    const { hasJoinedTable, gameState, tableId } = useGameStore.getState();
    if (!hasJoinedTable || !tableId) return;
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    this.joinTable();
    if (!gameState) {
      this.scheduleResumeJoinRetry();
    }
  }

  public disconnect(): void {
    this.isExplicitDisconnect = true;
    this.socketGeneration++;
    this.stopHeartbeat();
    this.clearResumeJoinTimer();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    useGameStore.getState().setConnectionStatus('DISCONNECTED');
  }

  public sendMessage(msg: WsClientMessage): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[WS] Message queued/dropped, socket not OPEN:', msg.type);
      return;
    }
    const fullMsg: WsClientMessage = {
      ...msg,
      tableId: msg.tableId || useGameStore.getState().tableId,
      requestId: msg.requestId || 'req_' + Date.now() + '_' + Math.random().toString(36).substring(2, 6),
    };
    this.ws.send(JSON.stringify(fullMsg));
  }

  private handleMessage(msg: WsServerMessage): void {
    switch (msg.type) {
      case 'CONNECTED':
        console.log('[WS] Handshake established:', msg.payload);
        break;

      case 'AUTH_SUCCESS':
        console.log('[WS] Authenticated successfully');
        // Rejoin after lobby match or soft-reconnect / page resume
        this.ensureTableJoined();
        break;

      case 'GAME_VIEW':
        this.clearResumeJoinTimer();
        if (msg.payload) {
          const prev = useGameStore.getState().gameState;
          const next = msg.payload as PlayerGameView;
          useGameStore.getState().updateGameState(next);

          // Sound cues from state transitions
          if (!prev && next.gameStatus === 'IN_PROGRESS') {
            soundEngine.play('deal');
          } else if (prev && !prev.isMyTurn && next.isMyTurn && next.gameStatus === 'IN_PROGRESS') {
            soundEngine.play('turn');
          } else if (
            prev?.gameStatus === 'IN_PROGRESS' &&
            next.gameStatus === 'COMPLETED'
          ) {
            const me = useGameStore.getState().playerId;
            soundEngine.play(next.winnerId === me ? 'win' : 'lose');
          } else if (
            prev &&
            next.hand &&
            prev.hand &&
            next.hand.length > prev.hand.length
          ) {
            soundEngine.play('draw');
          } else if (
            prev &&
            next.hand &&
            prev.hand &&
            next.hand.length < prev.hand.length
          ) {
            soundEngine.play('discard');
          }
        }
        break;

      case 'GAME_EVENT':
        if (msg.payload && typeof msg.payload === 'object' && 'eventType' in msg.payload) {
          const evt = msg.payload as { eventType: string };
          useGameStore.getState().setLastEventMessage(evt.eventType);
          if (evt.eventType.includes('Started') || evt.eventType === 'GAME_STARTED') {
            soundEngine.play('deal');
          }
        }
        break;

      case 'GAME_SETTLEMENT':
        if (msg.payload) {
          console.log('[WS] Received match financial settlement:', msg.payload);
          useGameStore.getState().setGameSettlement(msg.payload as any);
        }
        break;

      case 'ERROR':
        if (msg.payload && typeof msg.payload === 'object' && 'message' in msg.payload) {
          const err = msg.payload as { message: string };
          useGameStore.getState().setErrorMessage(err.message);
          soundEngine.play('error');
          setTimeout(() => {
            if (useGameStore.getState().errorMessage === err.message) {
              useGameStore.getState().setErrorMessage(null);
            }
          }, 5000);
        }
        break;

      case 'PONG':
        // Heartbeat ACK
        break;

      default:
        console.log('[WS] Unhandled message:', msg.type, msg.payload);
    }
  }

  private clearResumeJoinTimer(): void {
    if (this.resumeJoinTimer) {
      clearTimeout(this.resumeJoinTimer);
      this.resumeJoinTimer = null;
    }
  }

  /** If GAME_VIEW never arrives after refresh, retry JOIN a couple of times. */
  private scheduleResumeJoinRetry(): void {
    this.clearResumeJoinTimer();
    let attempts = 0;
    const tick = () => {
      const { resumePending, gameState, hasJoinedTable } = useGameStore.getState();
      if (!resumePending || gameState || !hasJoinedTable) {
        this.resumeJoinTimer = null;
        return;
      }
      attempts += 1;
      console.log(`[WS] Resume join retry #${attempts}`);
      this.joinTable();
      if (attempts < 3) {
        this.resumeJoinTimer = window.setTimeout(tick, 1200);
      } else {
        this.resumeJoinTimer = null;
      }
    };
    this.resumeJoinTimer = window.setTimeout(tick, 1200);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.pingInterval = window.setInterval(() => {
      this.sendMessage({ type: 'PING' });
    }, 15000);
  }

  private stopHeartbeat(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WS] Max reconnect attempts reached');
      return;
    }
    const delay = Math.min(1000 * Math.pow(1.5, this.reconnectAttempts), 10000);
    this.reconnectAttempts++;
    console.log(`[WS] Scheduling reconnect #${this.reconnectAttempts} in ${Math.round(delay)}ms`);
    this.reconnectTimer = window.setTimeout(() => {
      this.connect();
    }, delay);
  }

  // --- High-level game commands ---

  public joinTable(seatIndex = 0, isBot = false): void {
    const { tableId, playerId, displayName } = useGameStore.getState();
    this.sendMessage({
      type: 'JOIN_TABLE',
      tableId,
      payload: {
        playerId,
        displayName,
        seatIndex,
        isBot,
      },
    });
  }

  public addBot(displayName: string, seatIndex: number): void {
    const { tableId } = useGameStore.getState();
    const botId = 'BOT_' + Math.floor(1000 + Math.random() * 9000);
    this.sendMessage({
      type: 'JOIN_TABLE',
      tableId,
      payload: {
        playerId: botId,
        displayName,
        seatIndex,
        isBot: true,
      },
    });
  }

  public sendReady(): void {
    this.sendMessage({ type: 'READY' });
  }

  public startGame(): void {
    this.sendMessage({ type: 'START_GAME' });
  }

  public draw(source: 'CLOSED_DECK' | 'DISCARD_PILE'): void {
    this.sendMessage({
      type: 'DRAW',
      payload: { source },
    });
  }

  public discard(cardInstanceId: string): void {
    this.sendMessage({
      type: 'DISCARD',
      payload: { cardInstanceId },
    });
  }

  public declare(finishCardInstanceId: string, groups: { cards: CardInstance[] }[]): void {
    const serializedGroups = groups.map(g =>
      g.cards.map(c => ({ instanceId: c.instanceId }))
    );
    this.sendMessage({
      type: 'DECLARE',
      payload: {
        finishCardInstanceId,
        groups: serializedGroups,
      },
    });
  }

  public drop(): void {
    this.sendMessage({ type: 'DROP' });
  }

  /** Voluntary leave — clears server resume binding (and drops if still active). */
  public leaveTable(): void {
    const { tableId, playerId } = useGameStore.getState();
    this.sendMessage({
      type: 'LEAVE_TABLE',
      tableId,
      payload: { playerId },
    });
  }
}

export const socketClient = new GameSocketClient();
