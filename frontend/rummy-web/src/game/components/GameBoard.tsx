import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { OpponentSeat } from './OpponentSeat';
import { TableCenter } from './TableCenter';
import { PlayerHand } from './PlayerHand';
import { ActionControls } from './ActionControls';
import { DeclareModal } from './DeclareModal';
import { TurnTimerRing } from './TurnTimerRing';
import { LogOut, Wifi, AlertCircle, Sparkles, User, HelpCircle } from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { soundEngine } from '../audio/soundEngine';
import { normalizeTurnPhase } from '../utils/turnPhase';

interface GameBoardProps {
  onOpenTutorial?: () => void;
}

export const GameBoard: React.FC<GameBoardProps> = ({ onOpenTutorial }) => {
  const {
    gameState,
    connectionStatus,
    errorMessage,
    leaveTable,
    displayName,
    groups,
    playerId,
  } = useGameStore();

  const handleLeaveTable = () => {
    socketClient.disconnect();
    leaveTable();
  };

  const opponents = gameState?.opponents ?? [];
  const isMyTurn = gameState?.isMyTurn ?? false;
  const estimatedHandPts = groups.reduce((sum, g) => sum + (g.deadwoodPoints || 0), 0);

  const turnLabel = !gameState
    ? 'Connecting…'
    : gameState.gameStatus === 'WAITING_FOR_PLAYERS'
      ? 'Getting ready…'
      : gameState.gameStatus === 'COMPLETED'
        ? gameState.winnerId === playerId
          ? 'You won'
          : 'Finished'
        : isMyTurn
          ? normalizeTurnPhase(gameState.turnPhase) === 'DRAW'
            ? 'Your turn — draw'
            : normalizeTurnPhase(gameState.turnPhase) === 'DISCARD'
              ? 'Your turn — discard'
              : 'Your turn'
          : 'Opponent’s turn';

  return (
    <div className="game-frame">
      <header className="game-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <Sparkles size={16} color="var(--gold-accent)" />
          <span style={{ fontWeight: 800, fontSize: 14, color: 'var(--gold-light)' }}>
            Royal Rummy
          </span>
          <span
            className={isMyTurn ? 'my-turn-pulse' : undefined}
            style={{
              fontSize: 11,
              fontWeight: 700,
              padding: '3px 8px',
              borderRadius: 16,
              background: isMyTurn ? 'rgba(212,175,55,0.2)' : 'rgba(255,255,255,0.06)',
              color: isMyTurn ? '#fef08a' : '#94a3b8',
              border: isMyTurn ? '1px solid rgba(212,175,55,0.5)' : '1px solid transparent',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              maxWidth: 160,
            }}
          >
            {turnLabel}
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              fontSize: 10,
              fontWeight: 700,
              padding: '3px 8px',
              borderRadius: 16,
              background:
                connectionStatus === 'CONNECTED'
                  ? 'rgba(16, 185, 129, 0.15)'
                  : 'rgba(245, 158, 11, 0.15)',
              color: connectionStatus === 'CONNECTED' ? 'var(--color-pure)' : 'var(--color-impure)',
            }}
          >
            <Wifi size={11} />
            {connectionStatus === 'CONNECTED' ? 'Live' : '…'}
          </div>

          {onOpenTutorial && (
            <button
              type="button"
              onClick={() => {
                soundEngine.play('click');
                onOpenTutorial();
              }}
              className="btn-secondary"
              style={{ padding: '4px 8px', fontSize: 11 }}
              title="How to play"
            >
              <HelpCircle size={14} />
            </button>
          )}

          <SoundToggle compact />

          <button
            id="btn-leave-table"
            type="button"
            onClick={() => {
              soundEngine.play('click');
              handleLeaveTable();
            }}
            className="btn-secondary"
            style={{ padding: '4px 8px', fontSize: 11 }}
          >
            <LogOut size={13} />
            Leave
          </button>
        </div>
      </header>

      {errorMessage && (
        <div
          style={{
            position: 'absolute',
            top: 56,
            left: '50%',
            transform: 'translateX(-50%)',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            background: 'rgba(239, 68, 68, 0.95)',
            color: '#fff',
            padding: '8px 14px',
            borderRadius: 8,
            fontSize: 12,
            fontWeight: 600,
            zIndex: 50,
            maxWidth: '90%',
          }}
        >
          <AlertCircle size={16} />
          <span>{errorMessage}</span>
        </div>
      )}

      <main className="casino-table" id="game-felt-table">
        <div className="table-felt-pattern" />

        <section
          aria-label="Opponents"
          style={{
            display: 'flex',
            justifyContent: 'space-around',
            width: '100%',
            maxWidth: 850,
            zIndex: 10,
            scale: '0.92',
          }}
        >
          {opponents.length > 0 ? (
            opponents.map((opp) => (
              <OpponentSeat
                key={opp.playerId}
                player={opp}
                activePlayerId={gameState?.activePlayerId}
                turnDeadline={gameState?.turnDeadline}
                seatNumber={opp.seatIndex}
              />
            ))
          ) : (
            <div
              style={{
                color: '#fef08a',
                fontSize: 12,
                fontWeight: 600,
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                background: 'rgba(15, 23, 42, 0.75)',
                padding: '6px 14px',
                borderRadius: 20,
                border: '1px solid rgba(212, 175, 55, 0.4)',
                boxShadow: '0 4px 15px rgba(0, 0, 0, 0.4)',
              }}
            >
              <div
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  background: '#fbbf24',
                  boxShadow: '0 0 8px #fbbf24',
                  animation: 'pulse 1.5s infinite',
                }}
              />
              Waiting for opponent… bot joins in a few seconds
            </div>
          )}
        </section>

        <section aria-label="Table Center" style={{ zIndex: 10, scale: '0.9' }}>
          <TableCenter />
        </section>

        <section
          aria-label="My status"
          style={{ zIndex: 10, display: 'flex', justifyContent: 'center' }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              background: isMyTurn ? 'rgba(212,175,55,0.15)' : 'rgba(15,23,42,0.7)',
              border: isMyTurn ? '1px solid var(--border-gold)' : '1px solid rgba(255,255,255,0.1)',
              padding: '3px 12px',
              borderRadius: 20,
            }}
          >
            <div style={{ position: 'relative', width: 28, height: 28 }}>
              {isMyTurn && (
                <div style={{ position: 'absolute', top: -3, left: -3 }}>
                  <TurnTimerRing turnDeadline={gameState?.turnDeadline ?? null} size={34} strokeWidth={3} />
                </div>
              )}
              <div
                style={{
                  width: 28,
                  height: 28,
                  borderRadius: '50%',
                  background: 'var(--felt-green-center)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'var(--gold-light)',
                }}
              >
                <User size={14} />
              </div>
            </div>
            <span style={{ fontSize: 12, fontWeight: 800 }}>{displayName}</span>
            {gameState?.gameStatus === 'IN_PROGRESS' && (
              <span
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  color: estimatedHandPts <= 40 ? '#86efac' : '#fde68a',
                  background: 'rgba(0,0,0,0.25)',
                  padding: '2px 8px',
                  borderRadius: 10,
                }}
                title="Estimated ungrouped / deadwood points"
              >
                ~{estimatedHandPts} pts
              </span>
            )}
          </div>
        </section>
      </main>

      <section className="hand-area" aria-label="Player Hand">
        <PlayerHand />
      </section>

      <section className="actions-area" aria-label="Actions">
        <ActionControls />
      </section>

      <DeclareModal />
    </div>
  );
};
