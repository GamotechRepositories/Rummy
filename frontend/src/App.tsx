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
    restoreLocalSessionIfAny();
    return false;
  });

  useEffect(() => {
    installSoundUnlock();
    let cancelled = false;

    (async () => {
      try {
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
        socketClient.connect();
        socketClient.ensureTableJoined();
        setBootDone(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  // Soft-reconnect: stay on GameBoard while reclaiming table
  const inGame = hasJoinedTable && (gameState !== null || resumePending);

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
          <LandscapeGate enabled>
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
