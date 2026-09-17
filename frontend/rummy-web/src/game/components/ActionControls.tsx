import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { Play, Trash2, Award, Flag, ArrowDownToLine, Loader2, Hand } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { isDiscardPhase, isDrawPhase, normalizeTurnPhase } from '../utils/turnPhase';

export const ActionControls: React.FC = () => {
  const { gameState, selectedCardIds, setDeclareModalOpen, playerId, clearSelection } =
    useGameStore();

  if (!gameState) return null;

  const { gameStatus, isMyTurn, turnPhase, opponents, activePlayerId } = gameState;
  const drawPhase = isDrawPhase(isMyTurn, turnPhase);
  const discardPhase = isDiscardPhase(isMyTurn, turnPhase);
  const uiPhase = normalizeTurnPhase(turnPhase);
  const opponentName =
    opponents.find((p) => p.playerId === activePlayerId)?.displayName ?? 'Opponent';

  React.useEffect(() => {
    if (gameStatus === 'WAITING_FOR_PLAYERS') {
      socketClient.sendReady();
      const t = setTimeout(() => socketClient.startGame(), 500);
      return () => clearTimeout(t);
    }
  }, [gameStatus]);

  const handleDiscard = () => {
    if (selectedCardIds.length !== 1) return;
    if (!discardPhase) {
      soundEngine.play('error');
      return;
    }
    const cardId = selectedCardIds[0];
    soundEngine.play('discard');
    clearSelection();
    socketClient.discard(cardId);
  };

  const handleOpenDeclare = () => {
    if (selectedCardIds.length !== 1) return;
    if (!discardPhase) {
      soundEngine.play('error');
      return;
    }
    soundEngine.play('modal');
    setDeclareModalOpen(true);
  };

  const handleDrop = () => {
    if (window.confirm('Quit this hand? You will get penalty points.')) {
      soundEngine.play('lose');
      socketClient.drop();
    }
  };

  const handleDraw = (source: 'CLOSED_DECK' | 'DISCARD_PILE') => {
    if (!drawPhase) {
      soundEngine.play('error');
      return;
    }
    soundEngine.play('draw');
    clearSelection();
    socketClient.draw(source);
  };

  let coachTitle = '';
  let coachHint = '';
  if (gameStatus === 'WAITING_FOR_PLAYERS') {
    coachTitle = 'Dealing cards…';
    coachHint = 'Game starts automatically.';
  } else if (gameStatus === 'COMPLETED') {
    const iWon = gameState.winnerId === playerId;
    coachTitle = iWon ? 'You won this hand!' : 'Hand finished';
    coachHint = iWon
      ? 'Nice declare. Rematch with the same stake anytime.'
      : 'Tap Rematch for the same table stake, or Leave.';
  } else if (drawPhase) {
    coachTitle = 'Step 1 — Draw a card';
    coachHint = 'Mystery pile or open pile.';
  } else if (discardPhase) {
    coachTitle = 'Step 2 — Throw one card';
    coachHint =
      selectedCardIds.length === 1
        ? 'Tap Discard, or Declare if you won.'
        : 'Tap exactly one card in your hand, then Discard.';
  } else if (!isMyTurn) {
    coachTitle = `Waiting for ${opponentName}`;
    coachHint = 'Arrange your groups.';
  } else if (isMyTurn && uiPhase == null) {
    coachTitle = 'Your turn';
    coachHint = 'Waiting for table sync…';
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
        width: '100%',
        maxWidth: 1000,
        margin: '0 auto',
        height: '100%',
      }}
    >
      {coachTitle && (
        <div
          className="coach-banner"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '8px 12px',
            borderRadius: 12,
            background: isMyTurn
              ? 'linear-gradient(135deg, rgba(212,175,55,0.22), rgba(15,23,42,0.9))'
              : 'rgba(15, 23, 42, 0.85)',
            border: isMyTurn
              ? '1px solid rgba(212,175,55,0.55)'
              : '1px solid rgba(255,255,255,0.1)',
          }}
        >
          {gameStatus === 'WAITING_FOR_PLAYERS' ? (
            <Loader2 size={16} className="spinner" color="#d4af37" />
          ) : (
            <Hand size={16} color={isMyTurn ? '#fef08a' : '#94a3b8'} />
          )}
          <div style={{ minWidth: 0 }}>
            <div
              style={{
                fontWeight: 800,
                fontSize: 13,
                color: isMyTurn ? '#fef08a' : '#e2e8f0',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {coachTitle}
            </div>
            <div style={{ fontSize: 11, color: '#94a3b8' }}>{coachHint}</div>
          </div>
        </div>
      )}

      <div
        className="action-bar"
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexWrap: 'wrap',
          gap: 8,
          padding: '8px 10px',
          background: 'rgba(15, 23, 42, 0.85)',
          borderRadius: 12,
          border: '1px solid rgba(255, 255, 255, 0.1)',
        }}
      >
        {gameStatus === 'IN_PROGRESS' && (
          <>
            {drawPhase && (
              <>
                <button
                  id="btn-action-draw-deck"
                  type="button"
                  className="btn-primary"
                  onClick={() => handleDraw('CLOSED_DECK')}
                >
                  <ArrowDownToLine size={15} />
                  Draw mystery
                </button>
                <button
                  id="btn-action-draw-discard"
                  type="button"
                  className="btn-secondary"
                  onClick={() => handleDraw('DISCARD_PILE')}
                  disabled={!gameState.topDiscard}
                >
                  <ArrowDownToLine size={15} />
                  Take open
                </button>
              </>
            )}

            {discardPhase && (
              <>
                <button
                  id="btn-action-discard"
                  type="button"
                  className="btn-primary"
                  onClick={handleDiscard}
                  disabled={selectedCardIds.length !== 1}
                >
                  <Trash2 size={15} />
                  {selectedCardIds.length === 1 ? 'Discard' : 'Select 1 card'}
                </button>
                <button
                  id="btn-action-declare"
                  type="button"
                  className="btn-primary"
                  onClick={handleOpenDeclare}
                  disabled={selectedCardIds.length !== 1}
                  style={{
                    background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
                    boxShadow: '0 4px 12px rgba(16, 185, 129, 0.4)',
                  }}
                >
                  <Award size={15} />
                  Declare win
                </button>
              </>
            )}

            <button
              id="btn-action-drop"
              type="button"
              className="btn-danger"
              onClick={handleDrop}
              disabled={!isMyTurn || !drawPhase}
            >
              <Flag size={13} />
              Quit
            </button>
          </>
        )}

        {gameStatus === 'COMPLETED' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <div style={{ color: 'var(--gold-light)', fontWeight: 800, fontSize: 14 }}>
              {gameState.winnerId === playerId ? (
                <>Winner: You!</>
              ) : (
                <>
                  Winner:{' '}
                  {opponents.find((p) => p.playerId === gameState.winnerId)?.displayName ??
                    'Opponent'}
                </>
              )}
              {opponents.length > 0 && (
                <span style={{ color: '#94a3b8', fontWeight: 600, fontSize: 12, marginLeft: 8 }}>
                  (
                  {opponents
                    .map((o) => `${o.displayName}: ${o.score} pts`)
                    .join(' · ')}
                  )
                </span>
              )}
            </div>
            <button
              id="btn-play-again"
              type="button"
              className="btn-primary"
              onClick={() => {
                soundEngine.play('match');
                clearSelection();
                useGameStore.getState().setAutoMatchmakePending(true);
                socketClient.disconnect();
                useGameStore.getState().leaveTable();
              }}
            >
              <Play size={15} />
              Rematch same stake
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
