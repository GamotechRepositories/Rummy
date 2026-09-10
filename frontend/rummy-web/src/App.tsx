import { useEffect } from 'react';
import { useGameStore } from './game/store/useGameStore';
import { socketClient } from './game/websocket/GameSocketClient';
import { LobbyScreen } from './game/components/LobbyScreen';
import { GameBoard } from './game/components/GameBoard';

export function App() {
  const { gameState, connectionStatus, hasJoinedTable } = useGameStore();

  useEffect(() => {
    // Automatically attempt connection on initial mount
    socketClient.connect();

    return () => {
      socketClient.disconnect();
    };
  }, []);

  const inGame =
    hasJoinedTable && gameState !== null && connectionStatus !== 'DISCONNECTED';

  return (
    <div style={{ minHeight: '100vh', width: '100%' }}>
      {inGame ? <GameBoard /> : <LobbyScreen />}
    </div>
  );
}

export default App;
