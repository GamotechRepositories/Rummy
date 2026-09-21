import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { Trash2, Award, Flag, ArrowDownToLine, Loader2, Hand } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { isDiscardPhase, isDrawPhase, normalizeTurnPhase } from '../utils/turnPhase';

export const ActionControls: React.FC = () => {
  const { gameState, selectedCardIds, setDeclareModalOpen, playerId, clearSelection } =
    useGameStore();
  const [confirmDropOpen, setConfirmDropOpen] = React.useState(false);

  const gameStatus = gameState?.gameStatus;
  const isMyTurn = gameState?.isMyTurn ?? false;
  const turnPhase = gameState?.turnPhase;
  const opponents = gameState?.opponents ?? [];
  const activePlayerId = gameState?.activePlayerId;

  React.useEffect(() => {
    if (gameStatus === 'WAITING_FOR_PLAYERS') {
      socketClient.sendReady();
      const t = setTimeout(() => socketClient.startGame(), 500);
      return () => clearTimeout(t);
    }
  }, [gameStatus]);

  if (!gameState) return null;

  const drawPhase = isDrawPhase(isMyTurn, turnPhase);
  const discardPhase = isDiscardPhase(isMyTurn, turnPhase);
  const uiPhase = normalizeTurnPhase(turnPhase);
  const opponentName =
    opponents.find((p) => p.playerId === activePlayerId)?.displayName ?? 'Opponent';

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

  const isFirstTurn = (gameState.discardHistory?.length ?? 0) <= 1;
  const dropPenaltyPoints = isFirstTurn ? 20 : 40;

  const handleOpenDeclare = () => {
    if (selectedCardIds.length !== 1) return;
    if (!discardPhase) {
      soundEngine.play('error');
      return;
    }
    soundEngine.play('modal');
    setDeclareModalOpen(true);
  };

  const handleConfirmDrop = () => {
    setConfirmDropOpen(false);
    soundEngine.play('lose');
    socketClient.drop();
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
    coachTitle = 'Waiting for opponent…';
    coachHint = 'Game starts automatically once matched.';
  } else if (gameStatus === 'DEALING') {
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
      className="table-action-controls"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 6,
        width: '100%',
        maxWidth: 1000,
        margin: '0 auto',
        flexShrink: 0,
      }}
    >
      {coachTitle && (
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            padding: '4px 16px',
            borderRadius: 20,
            background: isMyTurn
              ? 'linear-gradient(135deg, rgba(212,175,55,0.25), rgba(15,23,42,0.85))'
              : 'rgba(10, 25, 18, 0.75)',
            border: isMyTurn
              ? '1px solid rgba(251, 191, 36, 0.6)'
              : '1px solid rgba(255, 255, 255, 0.1)',
            boxShadow: isMyTurn ? '0 0 16px rgba(251, 191, 36, 0.35)' : 'none',
            backdropFilter: 'blur(10px)',
            margin: '0 auto',
            maxWidth: '90%',
          }}
        >
          {gameStatus === 'WAITING_FOR_PLAYERS' ? (
            <Loader2 size={14} className="spinner" color="#fbbf24" />
          ) : (
            <Hand size={14} color={isMyTurn ? '#fef08a' : '#94a3b8'} />
          )}
          <span style={{ fontWeight: 800, fontSize: '12px', color: isMyTurn ? '#fef08a' : '#e2e8f0' }}>
            {coachTitle}
          </span>
          <span style={{ fontSize: '11px', color: isMyTurn ? '#fef08a' : '#94a3b8' }}>• {coachHint}</span>
        </div>
      )}

      <div
        className="action-bar"
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexWrap: 'wrap',
          gap: 12,
          padding: '2px 8px',
          background: 'transparent',
          border: 'none',
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
                  style={{
                    padding: '9px 18px',
                    fontSize: '13px',
                    fontWeight: 800,
                    borderRadius: '12px',
                    boxShadow: '0 4px 14px rgba(251, 191, 36, 0.4)',
                  }}
                >
                  <ArrowDownToLine size={15} />
                  Draw from Deck
                </button>
                <button
                  id="btn-action-draw-discard"
                  type="button"
                  className="btn-secondary"
                  onClick={() => handleDraw('DISCARD_PILE')}
                  disabled={!gameState.topDiscard}
                  style={{
                    padding: '9px 18px',
                    fontSize: '13px',
                    fontWeight: 800,
                    borderRadius: '12px',
                  }}
                >
                  <ArrowDownToLine size={15} />
                  Take Open Card
                </button>
              </>
            )}

            {discardPhase && (
              <>
                <button
                  id="btn-action-discard"
                  type="button"
                  className="btn-danger"
                  onClick={handleDiscard}
                  disabled={selectedCardIds.length !== 1}
                  style={{
                    padding: '9px 20px',
                    fontSize: '13px',
                    fontWeight: 800,
                    borderRadius: '12px',
                    background: selectedCardIds.length === 1
                      ? 'linear-gradient(135deg, #ef4444 0%, #b91c1c 100%)'
                      : 'rgba(239, 68, 68, 0.25)',
                    border: '1px solid rgba(239, 68, 68, 0.5)',
                    color: '#ffffff',
                    cursor: selectedCardIds.length === 1 ? 'pointer' : 'not-allowed',
                    boxShadow: selectedCardIds.length === 1 ? '0 4px 16px rgba(239, 68, 68, 0.5)' : 'none',
                  }}
                >
                  <Trash2 size={15} />
                  {selectedCardIds.length === 1 ? 'Discard' : 'Select 1 Card'}
                </button>
                <button
                  id="btn-action-declare"
                  type="button"
                  className="btn-primary"
                  onClick={handleOpenDeclare}
                  disabled={selectedCardIds.length !== 1}
                  style={{
                    padding: '9px 22px',
                    fontSize: '13px',
                    fontWeight: 800,
                    borderRadius: '12px',
                    background: selectedCardIds.length === 1
                      ? 'linear-gradient(135deg, #10b981 0%, #047857 100%)'
                      : 'rgba(16, 185, 129, 0.25)',
                    border: '1px solid rgba(16, 185, 129, 0.5)',
                    color: '#ffffff',
                    boxShadow: selectedCardIds.length === 1 ? '0 4px 18px rgba(16, 185, 129, 0.6)' : 'none',
                    cursor: selectedCardIds.length === 1 ? 'pointer' : 'not-allowed',
                  }}
                >
                  <Award size={16} />
                  Declare Win
                </button>
              </>
            )}

            <button
              id="btn-action-drop"
              type="button"
              className="btn-danger"
              onClick={() => setConfirmDropOpen(true)}
              disabled={!isMyTurn || !drawPhase}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '8px 16px',
                fontSize: '13px',
                fontWeight: 800,
                borderRadius: '10px',
                background:
                  !isMyTurn || !drawPhase
                    ? 'rgba(239, 68, 68, 0.2)'
                    : 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
                border: '1px solid rgba(239, 68, 68, 0.5)',
                color: '#ffffff',
                cursor: !isMyTurn || !drawPhase ? 'not-allowed' : 'pointer',
                boxShadow:
                  isMyTurn && drawPhase
                    ? '0 0 14px rgba(239, 68, 68, 0.35)'
                    : 'none',
              }}
            >
              <Flag size={14} />
              Drop ({dropPenaltyPoints})
            </button>
          </>
        )}
      </div>

      {confirmDropOpen && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.78)',
            backdropFilter: 'blur(6px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1100,
            padding: '16px',
          }}
        >
          <div
            style={{
              width: '100%',
              maxWidth: '380px',
              background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
              borderRadius: '20px',
              border: '1px solid rgba(239, 68, 68, 0.4)',
              boxShadow: '0 20px 40px rgba(0, 0, 0, 0.8), 0 0 25px rgba(239, 68, 68, 0.25)',
              padding: '24px',
              textAlign: 'center',
            }}
          >
            <div
              style={{
                width: '48px',
                height: '48px',
                borderRadius: '50%',
                background: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid rgba(239, 68, 68, 0.4)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#f87171',
                marginBottom: '14px',
              }}
            >
              <Flag size={24} />
            </div>

            <h3
              style={{
                margin: '0 0 8px',
                fontSize: '18px',
                fontWeight: 800,
                color: '#ffffff',
              }}
            >
              Confirm Drop ({dropPenaltyPoints} Pts)?
            </h3>

            <p
              style={{
                margin: '0 0 16px',
                fontSize: '13px',
                color: '#94a3b8',
                lineHeight: 1.5,
              }}
            >
              Are you sure you want to drop this hand?
              <br />
              <strong style={{ color: '#fca5a5' }}>
                {isFirstTurn ? 'First Drop' : 'Middle Drop'}: {dropPenaltyPoints} penalty points
              </strong>{' '}
              will be added to your score.
            </p>

            <div style={{ display: 'flex', gap: '10px' }}>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => setConfirmDropOpen(false)}
                style={{ flex: 1, padding: '10px', fontSize: '13px' }}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn-danger"
                onClick={handleConfirmDrop}
                style={{
                  flex: 1.3,
                  padding: '10px',
                  fontSize: '13px',
                  fontWeight: 800,
                  background: 'linear-gradient(135deg, #ef4444 0%, #b91c1c 100%)',
                }}
              >
                Confirm Drop
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
