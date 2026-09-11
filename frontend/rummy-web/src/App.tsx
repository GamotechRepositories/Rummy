import { useEffect, useState } from 'react';
import { useGameStore } from './game/store/useGameStore';
import { socketClient } from './game/websocket/GameSocketClient';
import { LobbyScreen } from './game/components/LobbyScreen';
import { GameBoard } from './game/components/GameBoard';
import {
  HowToPlayTutorial,
  shouldShowTutorial,
} from './game/components/HowToPlayTutorial';
import { installSoundUnlock, soundEngine } from './game/audio/soundEngine';

export function App() {
  const { gameState, connectionStatus, hasJoinedTable } = useGameStore();
  const [showTutorial, setShowTutorial] = useState(false);

  useEffect(() => {
    installSoundUnlock();
    socketClient.connect();
    if (shouldShowTutorial()) {
      setShowTutorial(true);
    }
    return () => {
      socketClient.disconnect();
    };
  }, []);

  const inGame =
    hasJoinedTable && gameState !== null && connectionStatus !== 'DISCONNECTED';

  const openTutorial = () => {
    soundEngine.play('modal');
    setShowTutorial(true);
  };

  return (
    <div className="app-frame">
      {inGame ? (
        <GameBoard onOpenTutorial={openTutorial} />
      ) : (
        <LobbyScreen onOpenTutorial={openTutorial} />
      )}

      {showTutorial && (
        <HowToPlayTutorial onClose={() => setShowTutorial(false)} />
      )}
    </div>
  );
}

export default App;
