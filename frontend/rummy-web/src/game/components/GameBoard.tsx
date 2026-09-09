import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { OpponentSeat } from './OpponentSeat';
import { TableCenter } from './TableCenter';
import { PlayerHand } from './PlayerHand';
import { ActionControls } from './ActionControls';
import { DeclareModal } from './DeclareModal';
import { TurnTimerRing } from './TurnTimerRing';
import { LogOut, Wifi, AlertCircle, Sparkles, User } from 'lucide-react';

export const GameBoard: React.FC = () => {
  const {
    gameState,
    tableId,
    connectionStatus,
    errorMessage,
    lastEventMessage,
    setSession,
  } = useGameStore();

  const handleLeaveTable = () => {
    socketClient.disconnect();
    // Return to lobby
    setSession(tableId, useGameStore.getState().playerId, useGameStore.getState().displayName);
    useGameStore.setState({ gameState: null });
  };

  const opponents = gameState?.opponents ?? [];
  const isMyTurn = gameState?.isMyTurn ?? false;
  const displayName = useGameStore.getState().displayName;

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        minHeight: '100vh',
        background: 'radial-gradient(ellipse at 50% 10%, #0d1e16 0%, #05080c 100%)',
        padding: '12px 20px',
      }}
    >
      {/* Top Header Bar */}
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '8px 16px',
          background: 'rgba(15, 23, 42, 0.75)',
          backdropFilter: 'blur(10px)',
          borderRadius: '12px',
          border: '1px solid rgba(255, 255, 255, 0.08)',
          marginBottom: '10px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Sparkles size={18} color="var(--gold-accent)" />
            <span style={{ fontWeight: 800, fontSize: '15px', color: 'var(--gold-light)' }}>
              Royal Rummy
            </span>
          </div>
          <span style={{ color: 'rgba(255,255,255,0.2)' }}>|</span>
          <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            Table: <strong style={{ color: 'var(--text-main)' }}>{tableId}</strong>
          </div>
          {gameState && (
            <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              Round: <strong style={{ color: 'var(--text-main)' }}>#{gameState.gameId.substring(0, 8)}</strong>
            </div>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
          {/* Connection Status Badge */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              fontSize: '11px',
              fontWeight: 700,
              padding: '4px 10px',
              borderRadius: '20px',
              background:
                connectionStatus === 'CONNECTED'
                  ? 'rgba(16, 185, 129, 0.15)'
                  : connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING'
                  ? 'rgba(245, 158, 11, 0.15)'
                  : 'rgba(239, 68, 68, 0.15)',
              color:
                connectionStatus === 'CONNECTED'
                  ? 'var(--color-pure)'
                  : connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING'
                  ? 'var(--color-impure)'
                  : 'var(--color-invalid)',
              border: '1px solid currentColor',
            }}
          >
            <Wifi size={12} />
            <span>{connectionStatus}</span>
          </div>

          <button
            id="btn-leave-table"
            onClick={handleLeaveTable}
            className="btn-secondary"
            style={{ padding: '6px 12px', fontSize: '12px' }}
          >
            <LogOut size={14} />
            Leave Table
          </button>
        </div>
      </header>

      {/* Floating Error Toast */}
      {errorMessage && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            background: 'rgba(239, 68, 68, 0.9)',
            color: '#ffffff',
            padding: '10px 16px',
            borderRadius: '8px',
            fontSize: '13px',
            fontWeight: 600,
            margin: '0 auto 10px',
            maxWidth: '600px',
            boxShadow: '0 8px 20px rgba(0,0,0,0.5)',
            zIndex: 50,
          }}
        >
          <AlertCircle size={18} />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* Event Notification Sub-toast */}
      {lastEventMessage && (
        <div
          style={{
            textAlign: 'center',
            fontSize: '11px',
            color: 'var(--gold-light)',
            opacity: 0.8,
            marginBottom: '4px',
          }}
        >
          Latest Game Event: {lastEventMessage}
        </div>
      )}

      {/* Master Casino Oval Felt Table */}
      <main className="casino-table" id="game-felt-table">
        <div className="table-felt-pattern" />

        {/* Top Opponents Row */}
        <section
          aria-label="Opponents"
          style={{
            display: 'flex',
            justifyContent: 'space-around',
            width: '100%',
            maxWidth: '850px',
            margin: '0 auto',
            zIndex: 10,
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
                color: 'rgba(255, 255, 255, 0.4)',
                fontSize: '13px',
                padding: '12px',
                background: 'rgba(0,0,0,0.2)',
                borderRadius: '8px',
              }}
            >
              Waiting for opponents to join... Click "Add AI Bot" below to practice solo!
            </div>
          )}
        </section>

        {/* Table Center (Decks, Wild Joker, Discard, Finish Slot) */}
        <section aria-label="Table Center" style={{ margin: 'auto', zIndex: 10 }}>
          <TableCenter />
        </section>

        {/* Bottom Current Player Indicator */}
        <section
          aria-label="My Player Status"
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '12px',
            zIndex: 10,
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
              background: isMyTurn ? 'rgba(212, 175, 55, 0.15)' : 'rgba(15, 23, 42, 0.7)',
              border: isMyTurn ? '1px solid var(--border-gold)' : '1px solid rgba(255,255,255,0.1)',
              padding: '4px 14px',
              borderRadius: '24px',
            }}
          >
            <div style={{ position: 'relative', width: '32px', height: '32px' }}>
              {isMyTurn && (
                <div style={{ position: 'absolute', top: '-4px', left: '-4px' }}>
                  <TurnTimerRing turnDeadline={gameState?.turnDeadline ?? null} size={40} strokeWidth={3} />
                </div>
              )}
              <div
                style={{
                  width: '32px',
                  height: '32px',
                  borderRadius: '50%',
                  background: 'var(--felt-green-center)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'var(--gold-light)',
                }}
              >
                <User size={18} />
              </div>
            </div>

            <div>
              <span style={{ fontSize: '13px', fontWeight: 800, color: 'var(--text-main)' }}>
                {displayName} (You)
              </span>
              <span style={{ fontSize: '11px', color: 'var(--gold-accent)', marginLeft: '8px' }}>
                {isMyTurn ? `Your Turn — Phase: ${gameState?.turnPhase}` : 'Waiting for turn...'}
              </span>
            </div>
          </div>
        </section>
      </main>

      {/* Player's Card Hand */}
      <section aria-label="Player Hand" style={{ marginTop: '8px' }}>
        <PlayerHand />
      </section>

      {/* Action Controls Bar */}
      <section aria-label="Action Controls">
        <ActionControls />
      </section>

      {/* Declaration Confirmation Modal */}
      <DeclareModal />
    </div>
  );
};
