import { useEffect, useState } from 'react';
import { useGameStore } from './game/store/useGameStore';
import { socketClient } from './game/websocket/GameSocketClient';
import { LobbyScreen } from './game/components/LobbyScreen';
import { GameBoard } from './game/components/GameBoard';
import { AppErrorBoundary } from './game/components/AppErrorBoundary';
import { LandscapeGate } from './game/components/LandscapeGate';
import { installSoundUnlock } from './game/audio/soundEngine';
import {
  clearActiveSessionLocal,
  fetchActiveSession,
  readPersistedActiveTable,
  readPersistedDisplayName,
} from './game/utils/sessionResume';
import {
  ensureAuthToken,
  getAuthenticatedPlayerId,
  redeemLaunchCode,
  takeLaunchCodeFromUrl,
} from './game/utils/authClient';
import { getApiBaseUrl } from './game/utils/apiConfig';

type SessionProblem = 'none' | 'expired-link';

function hasLaunchCodeInUrl(): boolean {
  try {
    return new URLSearchParams(window.location.search).has('launch');
  } catch {
    return false;
  }
}

let launchRedemption: Promise<'none' | 'ok' | 'failed'> | null = null;

/** Opens the session from an operator launch link, if the page was opened with one (once per page load). */
function redeemLaunchFromUrl(): Promise<'none' | 'ok' | 'failed'> {
  if (!launchRedemption) launchRedemption = redeemLaunchCodeInUrl();
  return launchRedemption;
}

async function redeemLaunchCodeInUrl(): Promise<'none' | 'ok' | 'failed'> {
  const code = takeLaunchCodeFromUrl();
  if (!code) return 'none';
  const previousPlayer = getAuthenticatedPlayerId();
  const session = await redeemLaunchCode(code);
  if (!session) return 'failed';
  if (previousPlayer && previousPlayer !== session.playerId) {
    // A different account was launched in this browser; the old player's table is not ours.
    clearActiveSessionLocal();
  }
  useGameStore.setState({ playerId: session.playerId });
  if (session.displayName) {
    useGameStore.getState().setDisplayName(session.displayName);
  }
  return 'ok';
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
  const { gameState, hasJoinedTable, resumePending } = useGameStore();
  // Restore local table synchronously so first paint can show GameBoard (not blank)
  const [bootDone, setBootDone] = useState(() => {
    if (!hasLaunchCodeInUrl()) {
      restoreLocalSessionIfAny();
    }
    return false;
  });
  const [noSession, setNoSession] = useState<SessionProblem | null>(null);

  useEffect(() => {
    installSoundUnlock();
    let cancelled = false;
    let hasSession = false;

    (async () => {
      try {
        const launch = await redeemLaunchFromUrl();
        // Backend only trusts the player id inside the JWT, so obtain it before any API/WS call
        const token = await ensureAuthToken(readPersistedDisplayName() || useGameStore.getState().displayName);
        if (!token) {
          setNoSession(launch === 'failed' ? 'expired-link' : 'none');
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
    return (
      <div className="app-frame" style={{ display: 'grid', placeItems: 'center', color: '#f8fafc', padding: 24 }}>
        <div style={{ textAlign: 'center', maxWidth: 420 }}>
          <img
            src="/image.png"
            alt="Royal Rummy"
            style={{ height: 64, maxWidth: '80vw', objectFit: 'contain', marginBottom: 16 }}
          />
          <h2 style={{ margin: '0 0 10px', fontSize: 20 }}>
            {noSession === 'expired-link' ? 'This game link has expired' : 'Open Royal Rummy from your gaming account'}
          </h2>
          <p style={{ margin: 0, fontSize: 14, color: '#94a3b8', lineHeight: 1.5 }}>
            {noSession === 'expired-link'
              ? 'Game links work once and only for a short time. Go back to your gaming account and open the game again.'
              : 'Royal Rummy is played through your gaming account. Sign in there and choose Rummy to start playing.'}
          </p>
          {import.meta.env.DEV && (
            <a
              href={`${getApiBaseUrl()}/mock-operator/`}
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
            src="/image.png"
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
      </div>
    </AppErrorBoundary>
  );
}

export default App;
