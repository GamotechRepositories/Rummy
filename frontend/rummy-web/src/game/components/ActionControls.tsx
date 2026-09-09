import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { Play, UserCheck, Bot, Trash2, Award, Flag, ArrowDownToLine } from 'lucide-react';

export const ActionControls: React.FC = () => {
  const {
    gameState,
    selectedCardIds,
    setDeclareModalOpen,
  } = useGameStore();

  if (!gameState) return null;

  const { gameStatus, isMyTurn, turnPhase, opponents, activePlayerId } = gameState;
  const isDrawPhase = isMyTurn && turnPhase === 'DRAW';
  const isDiscardPhase = isMyTurn && turnPhase === 'DISCARD';
  const totalPlayersCount = 1 + (opponents?.length ?? 0);

  const handleDrawClosed = () => socketClient.draw('CLOSED_DECK');
  const handleDrawDiscard = () => socketClient.draw('DISCARD_PILE');

  const handleDiscard = () => {
    if (selectedCardIds.length === 1) {
      socketClient.discard(selectedCardIds[0]);
    }
  };

  const handleOpenDeclare = () => {
    if (selectedCardIds.length === 1) {
      setDeclareModalOpen(true);
    }
  };

  const handleDrop = () => {
    if (window.confirm('Are you sure you want to Drop this hand? You will incur drop penalty points.')) {
      socketClient.drop();
    }
  };

  const handleAddBot = () => {
    const nextSeat = totalPlayersCount;
    if (nextSeat < 6) {
      socketClient.addBot('Bot_' + ['Boomer', 'Kangaroo', 'Sydney', 'Wombat', 'Outback'][nextSeat % 5], nextSeat);
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '12px',
        padding: '12px 20px',
        background: 'rgba(15, 23, 42, 0.85)',
        borderRadius: '16px',
        backdropFilter: 'blur(12px)',
        border: '1px solid rgba(255, 255, 255, 0.1)',
        maxWidth: '1000px',
        margin: '8px auto 0',
      }}
    >
      {/* Waiting / Dealing Controls */}
      {gameStatus === 'WAITING_FOR_PLAYERS' && (
        <>
          <button
            id="btn-ready"
            className="btn-secondary"
            onClick={() => socketClient.sendReady()}
          >
            <UserCheck size={16} />
            I am Ready
          </button>

          <button
            id="btn-add-bot"
            className="btn-secondary"
            onClick={handleAddBot}
            disabled={totalPlayersCount >= 6}
          >
            <Bot size={16} />
            Add AI Bot ({totalPlayersCount}/6)
          </button>

          <button
            id="btn-start-game"
            className="btn-primary"
            onClick={() => socketClient.startGame()}
            disabled={totalPlayersCount < 2}
          >
            <Play size={16} />
            Start Game ({totalPlayersCount} Players)
          </button>
        </>
      )}

      {/* In-Progress Turn Controls */}
      {gameStatus === 'IN_PROGRESS' && (
        <>
          {/* Draw Phase */}
          {isDrawPhase && (
            <>
              <button
                id="btn-action-draw-deck"
                className="btn-primary"
                onClick={handleDrawClosed}
              >
                <ArrowDownToLine size={16} />
                Draw from Closed Deck
              </button>

              <button
                id="btn-action-draw-discard"
                className="btn-secondary"
                onClick={handleDrawDiscard}
                disabled={!gameState.topDiscard}
              >
                <ArrowDownToLine size={16} />
                Draw Discard
              </button>
            </>
          )}

          {/* Discard Phase */}
          {isDiscardPhase && (
            <>
              <button
                id="btn-action-discard"
                className="btn-primary"
                onClick={handleDiscard}
                disabled={selectedCardIds.length !== 1}
              >
                <Trash2 size={16} />
                Discard Card {selectedCardIds.length === 1 ? '✓' : '(Select 1)'}
              </button>

              <button
                id="btn-action-declare"
                className="btn-primary"
                onClick={handleOpenDeclare}
                disabled={selectedCardIds.length !== 1}
                style={{
                  background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
                  boxShadow: '0 4px 12px rgba(16, 185, 129, 0.4)',
                }}
              >
                <Award size={16} />
                Declare Show
              </button>
            </>
          )}

          {/* Non-Turn Status Display */}
          {!isMyTurn && (
            <div style={{ color: 'var(--text-muted)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: 'var(--gold-accent)' }} />
              Waiting for {opponents.find(p => p.playerId === activePlayerId)?.displayName ?? 'Opponent'}'s move...
            </div>
          )}

          {/* Drop Button */}
          <button
            id="btn-action-drop"
            className="btn-danger"
            onClick={handleDrop}
            disabled={!isMyTurn || !isDrawPhase}
            title={!isDrawPhase ? 'Can only drop at the start of your turn prior to drawing' : 'Drop game (20/40 pts penalty)'}
          >
            <Flag size={14} />
            Drop Hand
          </button>
        </>
      )}

      {/* Game Completed / Winner Banner */}
      {gameStatus === 'COMPLETED' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          <div style={{ color: 'var(--gold-light)', fontWeight: 800, fontSize: '16px' }}>
            🎉 Game Over! Winner: {opponents.find(p => p.playerId === gameState.winnerId)?.displayName ?? 'You / Winner'}
          </div>
          <button
            id="btn-play-again"
            className="btn-primary"
            onClick={() => socketClient.startGame()}
          >
            <Play size={16} />
            Play Next Deal
          </button>
        </div>
      )}
    </div>
  );
};
