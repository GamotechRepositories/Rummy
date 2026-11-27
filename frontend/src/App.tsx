import { useEffect, useState } from 'react';
import { useGameStore } from './game/store/useGameStore';
import { socketClient } from './game/websocket/GameSocketClient';
import { LobbyScreen } from './game/components/LobbyScreen';
import { GameBoard } from './game/components/GameBoard';
import { AppErrorBoundary } from './game/components/AppErrorBoundary';
import { LandscapeGate } from './game/components/LandscapeGate';
import { installSoundUnlock } from './game/audio/soundEngine';
import { preloadLobbyArt } from './game/utils/preloadLobbyArt';
import {
  clearActiveSessionLocal,
  fetchActiveSession,
  readPersistedActiveTable,
  readPersistedDisplayName,
} from './game/utils/sessionResume';
import {
  clearAuthToken,
  ensureAuthToken,
  exitToOperatorLobby,
  getAuthenticatedPlayerId,
  getCloseUrl,
  redeemLaunchSession,
  saveLaunchMeta,
  SESSION_INVALID_EVENT,
  takeLaunchParamsFromUrl,
  type SessionInvalidDetail,
} from './game/utils/authClient';
import { getApiBaseUrl } from './game/utils/apiConfig';

interface SessionProblem {
  kind: 'none' | 'expired-link' | 'session-invalid';
  message?: string;
  errorCode?: string;
  closeUrl?: string | null;
}

function hasLaunchCodeInUrl(): boolean {
  try {
    const params = new URLSearchParams(window.location.search);
    return params.has('token') || params.has('launch');
  } catch {
    return false;
  }
}

let launchRedemption: Promise<
  | { status: 'none' }
  | { status: 'ok' }
  | { status: 'failed'; problem: SessionProblem }
> | null = null;

/** Opens the session from an Operations / operator launch URL once per page load. */
function redeemLaunchFromUrl() {
  if (!launchRedemption) launchRedemption = redeemLaunchCodeInUrl();
  return launchRedemption;
}

async function redeemLaunchCodeInUrl(): Promise<
  | { status: 'none' }
  | { status: 'ok' }
  | { status: 'failed'; problem: SessionProblem }
> {
  const params = takeLaunchParamsFromUrl();
  if (!params) return { status: 'none' };

  const previousPlayer = getAuthenticatedPlayerId();
  const result = await redeemLaunchSession(params);
  if (!result.ok) {
    // Expired or invalid launch links must never fall back to an older cached player session.
    clearAuthToken();
    clearActiveSessionLocal();
    return {
      status: 'failed',
      problem: {
        kind: result.error === 'SESSION_INVALID' ? 'session-invalid' : 'expired-link',
        message: result.message,
        errorCode: result.errorCode,
        closeUrl: result.closeUrl ?? getCloseUrl(),
      },
    };
  }

  const session = result.session;
  if (previousPlayer && previousPlayer !== session.playerId) {
    // A different account was launched in this browser; the old player's table is not ours.
    clearActiveSessionLocal();
  }
  useGameStore.setState({ playerId: session.playerId });
  if (session.displayName) {
    useGameStore.getState().setDisplayName(session.displayName);
  }
  useGameStore.getState().setLaunchMeta({
    currency: session.currency,
    lang: session.lang,
    closeUrl: session.closeUrl,
  });
  if (session.balance != null) {
    useGameStore.getState().setWalletBalance(Number(session.balance));
  }
  return { status: 'ok' };
}

function restoreLocalSessionIfAny(): boolean {
  const localTable = readPersistedActiveTable();
  if (!localTable) return false;
  const { playerId, displayName, setSession, setHasJoinedTable, setResumePending } =
    useGameStore.getState();
  setSession(
    localTable,
    playerId,
    readPersistedDisplayName() || displayName || 'Player'
  );
  setHasJoinedTable(true);
  setResumePending(true);
  return true;
}

export function App() {
  const { gameState, hasJoinedTable, resumePending, sessionInvalidInfo, closeUrl } = useGameStore();
  // Restore local table synchronously so first paint can show GameBoard (not blank)
  const [bootDone, setBootDone] = useState(() => {
    if (!hasLaunchCodeInUrl()) {
      restoreLocalSessionIfAny();
    }
    return false;
  });
  const [noSession, setNoSession] = useState<SessionProblem | null>(null);
  const [mockEnabled, setMockEnabled] = useState(false);
  const [mockKeyRequired, setMockKeyRequired] = useState(false);

  useEffect(() => {
    const onSessionInvalid = (ev: Event) => {
      const detail = (ev as CustomEvent<SessionInvalidDetail>).detail;
      if (detail) {
        useGameStore.getState().setSessionInvalidInfo(detail);
      }
    };
    window.addEventListener(SESSION_INVALID_EVENT, onSessionInvalid);
    return () => window.removeEventListener(SESSION_INVALID_EVENT, onSessionInvalid);
  }, []);

  useEffect(() => {
    fetch(`${getApiBaseUrl()}/api/auth/config`)
      .then((r) => r.json())
      .then((data) => {
        if (data?.mockOperatorEnabled) {
          setMockEnabled(true);
        }
        if (data?.mockOperatorKeyRequired) {
          setMockKeyRequired(true);
        }
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    installSoundUnlock();
    preloadLobbyArt();
    let cancelled = false;
    let hasSession = false;

    (async () => {
      try {
        const launch = await redeemLaunchFromUrl();
        if (launch.status === 'failed') {
          setNoSession(launch.problem);
          return;
        }
        // Backend only trusts the player id inside the JWT, so obtain it before any API/WS call
        const token = await ensureAuthToken(readPersistedDisplayName() || useGameStore.getState().displayName);
        if (!token) {
          setNoSession({ kind: 'none', closeUrl: getCloseUrl() });
          return;
        }
        hasSession = true;
        const serverPlayerId = getAuthenticatedPlayerId();
        if (serverPlayerId && serverPlayerId !== useGameStore.getState().playerId) {
          useGameStore.setState({ playerId: serverPlayerId });
        }

        // Ensure local resume flags even if useState init was skipped by Fast Refresh quirks
        const hadLocal = restoreLocalSessionIfAny();

        const {
          playerId,
          displayName,
          setSession,
          setHasJoinedTable,
          setResumePending,
          leaveTable,
        } = useGameStore.getState();

        const remote = await fetchActiveSession(playerId);

        if (!cancelled) {
          if (remote === null) {
            // Network/timeout — keep local resume if present
            if (!hadLocal && !readPersistedActiveTable()) {
              setResumePending(false);
            }
          } else if (remote.active && remote.tableId) {
            setSession(
              remote.tableId,
              playerId,
              remote.displayName || displayName || 'Player'
            );
            setHasJoinedTable(true);
            setResumePending(true);
            if (remote.serverInstanceId) {
              try {
                sessionStorage.setItem('rummy_matched_server', remote.serverInstanceId);
              } catch {
                // ignore
              }
            }
          } else {
            // Server confirmed no resumable table
            clearActiveSessionLocal();
            if (useGameStore.getState().resumePending && !useGameStore.getState().gameState) {
              leaveTable();
            } else {
              setResumePending(false);
              setHasJoinedTable(false);
            }
          }
        }
      } finally {
        // Always connect + finish boot — even if effect was cancelled (Strict Mode)
        if (hasSession) {
          socketClient.connect();
          socketClient.ensureTableJoined();
        }
        setBootDone(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  // Soft-reconnect: stay on GameBoard while reclaiming table
  const inGame = hasJoinedTable && (gameState !== null || resumePending);

  if (bootDone && noSession) {
    const effectiveCloseUrl = noSession.closeUrl ?? closeUrl ?? getCloseUrl();
    if (effectiveCloseUrl) {
      saveLaunchMeta({ closeUrl: effectiveCloseUrl });
    }
    const title =
      noSession.kind === 'session-invalid'
        ? 'Your game session has expired'
        : noSession.kind === 'expired-link'
          ? 'This game link has expired'
          : 'Open Royal Rummy from your gaming account';
    const description =
      noSession.message ||
      (noSession.kind === 'session-invalid'
        ? 'Please return to the gaming lobby and open the game again.'
        : noSession.kind === 'expired-link'
          ? 'Game links work once and only for a short time. Go back to your gaming account and open the game again.'
          : 'Royal Rummy is played through your gaming account. Sign in there and choose Rummy to start playing.');

    return (
      <div className="app-frame" style={{ display: 'grid', placeItems: 'center', color: '#f8fafc', padding: 24 }}>
        <div style={{ textAlign: 'center', maxWidth: 420 }}>
          <img
            src="/image.webp"
            alt="Royal Rummy"
            style={{ height: 64, maxWidth: '80vw', objectFit: 'contain', marginBottom: 16 }}
          />
          <h2 style={{ margin: '0 0 10px', fontSize: 20 }}>{title}</h2>
          <p style={{ margin: 0, fontSize: 14, color: '#94a3b8', lineHeight: 1.5 }}>{description}</p>
          {noSession.errorCode && (
            <p style={{ margin: '8px 0 0', fontSize: 12, color: '#64748b' }}>Code: {noSession.errorCode}</p>
          )}
          {effectiveCloseUrl && (
            <div style={{ marginTop: 20 }}>
              <button
                type="button"
                className="royal-btn-gold"
                onClick={() => exitToOperatorLobby(effectiveCloseUrl)}
                style={{ padding: '10px 22px', fontSize: 14, fontWeight: 700, cursor: 'pointer' }}
              >
                Return to Lobby
              </button>
            </div>
          )}
          {mockEnabled && (
            <a
              href={
                mockKeyRequired
                  ? `${getApiBaseUrl()}/mock-operator/`
                  : `${getApiBaseUrl()}/mock-operator/play?returnTo=${encodeURIComponent(window.location.origin)}`
              }
              style={{ display: 'inline-block', marginTop: 18, color: '#facc15', fontSize: 14 }}
            >
              Test login (test money)
            </a>
          )}
        </div>
      </div>
    );
  }

  if (!bootDone) {
    return (
      <div className="app-frame" style={{ display: 'grid', placeItems: 'center', color: '#f8fafc' }}>
        <div style={{ textAlign: 'center', opacity: 0.95 }}>
          <img
            src="/image.webp"
            alt="Royal Rummy"
            style={{
              height: 64,
              maxWidth: '80vw',
              objectFit: 'contain',
              marginBottom: 12,
              filter: 'drop-shadow(0 6px 18px rgba(0,0,0,0.65))',
            }}
          />
          <div style={{ fontSize: 13, marginTop: 8, color: '#94a3b8' }}>Checking for active table…</div>
        </div>
      </div>
    );
  }

  return (
    <AppErrorBoundary>
      <div className="app-frame">
        {inGame ? (
          <LandscapeGate enabled hideFloatingBadge>
            <GameBoard />
          </LandscapeGate>
        ) : (
          <LobbyScreen />
        )}
        {sessionInvalidInfo && (
          <div className="royal-dialog-backdrop" role="presentation" style={{ zIndex: 9999 }}>
            <div className="royal-dialog-card" role="alertdialog" aria-modal="true" aria-labelledby="session-expired-title">
              <div className="royal-dialog-header">
                <h3 id="session-expired-title" className="royal-dialog-title">Session Expired</h3>
                <p className="royal-dialog-subtitle">
                  {sessionInvalidInfo.message}
                  {sessionInvalidInfo.errorCode ? ` (${sessionInvalidInfo.errorCode})` : ''}
                </p>
              </div>
              <div className="royal-dialog-actions">
                <button
                  type="button"
                  className="royal-btn-gold"
                  onClick={() => exitToOperatorLobby(sessionInvalidInfo.closeUrl ?? closeUrl)}
                >
                  Return to Lobby
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </AppErrorBoundary>
  );
}

export default App;
