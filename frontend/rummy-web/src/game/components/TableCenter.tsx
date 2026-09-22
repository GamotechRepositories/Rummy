import React, { useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { CardView } from './CardView';
import { CheckCircle2, Inbox, Eye, X, History } from 'lucide-react';
import { isDiscardPhase, isDrawPhase } from '../utils/turnPhase';
import { soundEngine } from '../audio/soundEngine';

export const TableCenter: React.FC = () => {
  const { gameState, selectedCardIds, setDeclareModalOpen, clearSelection } = useGameStore();
  const [showDiscardHistory, setShowDiscardHistory] = useState(false);

  if (!gameState) return null;

  const {
    closedDeckRemaining,
    topDiscard,
    cutJoker,
    turnPhase,
    isMyTurn,
    discardHistory = [],
  } = gameState;

  const canDraw = isDrawPhase(isMyTurn, turnPhase);
  const canDiscardOrFinish = isDiscardPhase(isMyTurn, turnPhase);

  const handleDrawClosed = () => {
    if (!canDraw) return;
    soundEngine.play('draw');
    clearSelection();
    socketClient.draw('CLOSED_DECK');
  };

  const handleDrawDiscard = () => {
    if (!canDraw || !topDiscard) return;
    soundEngine.play('draw');
    clearSelection();
    socketClient.draw('DISCARD_PILE');
  };

  const handleFinishSlotClick = () => {
    if (!canDiscardOrFinish) return;
    if (selectedCardIds.length === 1) {
      soundEngine.play('modal');
      setDeclareModalOpen(true);
    } else {
      soundEngine.play('error');
      alert('Select exactly 1 card from your hand first, then tap here to declare a win.');
    }
  };

  const handleDiscardPileDrop = () => {
    // During discard phase, tapping open pile with 1 selected card = discard
    if (canDiscardOrFinish && selectedCardIds.length === 1) {
      const cardId = selectedCardIds[0];
      soundEngine.play('discard');
      clearSelection();
      socketClient.discard(cardId);
    }
  };

  return (
    <>
      <div className="table-center-felt">
        {/* 1. Closed Draw Deck + Wild Joker */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
          <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
            {/* Wild Joker Card Underneath */}
            {cutJoker && (
              <div
                style={{
                  position: 'absolute',
                  left: '-40px',
                  transform: 'rotate(-24deg)',
                  zIndex: 1,
                  filter: 'drop-shadow(0 6px 12px rgba(0,0,0,0.65))',
                }}
              >
                <div style={{ position: 'relative' }}>
                  <CardView card={cutJoker} wildJoker={cutJoker} size="small" />
                  <div
                    style={{
                      position: 'absolute',
                      top: '-6px',
                      left: '50%',
                      transform: 'translateX(-50%)',
                      background: 'linear-gradient(135deg, #fbbf24, #d97706)',
                      color: '#000000',
                      fontSize: '9px',
                      fontWeight: 900,
                      padding: '1px 6px',
                      borderRadius: '4px',
                      boxShadow: '0 2px 6px rgba(0,0,0,0.5)',
                      letterSpacing: '0.05em',
                      zIndex: 20,
                    }}
                  >
                    JOKER
                  </div>
                </div>
              </div>
            )}

            {/* Closed Deck Stack */}
            <div
              id="deck-closed"
              onClick={handleDrawClosed}
              className={canDraw ? 'gold-glow' : ''}
              style={{
                position: 'relative',
                cursor: canDraw ? 'pointer' : 'default',
                zIndex: 5,
                transition: 'all 0.2s cubic-bezier(0.2, 0.8, 0.2, 1)',
                transform: canDraw ? 'scale(1.05)' : 'scale(1)',
                filter: canDraw ? 'drop-shadow(0 0 16px rgba(251, 191, 36, 0.6))' : 'drop-shadow(0 8px 16px rgba(0,0,0,0.6))',
              }}
            >
              <div className="rummy-card-back" />
              {/* Realistic 3D Stack Underlayers */}
              <div
                style={{
                  position: 'absolute',
                  top: '3px',
                  left: '3px',
                  width: '100%',
                  height: '100%',
                  borderRadius: '8px',
                  background: '#581c1c',
                  border: '1px solid rgba(255,255,255,0.2)',
                  zIndex: -1,
                }}
              />
              <div
                style={{
                  position: 'absolute',
                  top: '6px',
                  left: '6px',
                  width: '100%',
                  height: '100%',
                  borderRadius: '8px',
                  background: '#350a0a',
                  border: '1px solid rgba(255,255,255,0.1)',
                  zIndex: -2,
                }}
              />

              {/* Card Count Badge */}
              <span
                style={{
                  position: 'absolute',
                  bottom: '-8px',
                  left: '50%',
                  transform: 'translateX(-50%)',
                  background: 'linear-gradient(135deg, #1e293b, #0f172a)',
                  color: '#fef08a',
                  border: '1px solid var(--border-gold)',
                  fontSize: '10px',
                  fontWeight: 900,
                  padding: '2px 8px',
                  borderRadius: '12px',
                  whiteSpace: 'nowrap',
                  boxShadow: '0 4px 10px rgba(0,0,0,0.6)',
                }}
              >
                {closedDeckRemaining} cards
              </span>
            </div>
          </div>

          <span
            style={{
              fontSize: '11px',
              color: canDraw ? '#fef08a' : '#94a3b8',
              fontWeight: 800,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              marginTop: '8px',
              textShadow: '0 2px 4px rgba(0,0,0,0.8)',
            }}
          >
            {canDraw ? '⚡ Tap to Draw' : 'Closed Deck'}
          </span>
        </div>

        {/* 2. Open Discard Pile */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
          <div style={{ position: 'relative' }}>
            <div
              id="pile-discard"
              onClick={() => {
                if (canDraw) handleDrawDiscard();
                else handleDiscardPileDrop();
              }}
              className={
                (canDraw && topDiscard) || (canDiscardOrFinish && selectedCardIds.length === 1)
                  ? 'gold-glow'
                  : ''
              }
              style={{
                width: 'var(--card-w)',
                height: 'var(--card-h)',
                borderRadius: '8px',
                border: topDiscard ? 'none' : '2px dashed rgba(212, 175, 55, 0.4)',
                background: topDiscard ? 'transparent' : 'rgba(10, 26, 18, 0.4)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                cursor:
                  (canDraw && topDiscard) || (canDiscardOrFinish && selectedCardIds.length === 1)
                    ? 'pointer'
                    : 'default',
                position: 'relative',
                filter: 'drop-shadow(0 8px 16px rgba(0,0,0,0.55))',
                transition: 'all 0.2s ease',
              }}
            >
              {topDiscard ? (
                <CardView card={topDiscard} wildJoker={cutJoker} />
              ) : (
                <div style={{ color: 'rgba(255,255,255,0.4)', fontSize: '11px', fontWeight: 700 }}>Empty</div>
              )}
            </div>

            {/* Discard History Eye Button */}
            {discardHistory.length > 0 && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  soundEngine.play('click');
                  setShowDiscardHistory(true);
                }}
                title="View Discard History"
                style={{
                  position: 'absolute',
                  top: '-9px',
                  right: '-9px',
                  width: '26px',
                  height: '26px',
                  borderRadius: '50%',
                  background: 'linear-gradient(135deg, #1e293b, #0f172a)',
                  border: '1.5px solid var(--border-gold)',
                  color: '#fbbf24',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: 'pointer',
                  zIndex: 10,
                  boxShadow: '0 4px 10px rgba(0,0,0,0.6)',
                }}
              >
                <Eye size={13} />
              </button>
            )}
          </div>

          <span
            style={{
              fontSize: '11px',
              color:
                (canDraw && topDiscard) || (canDiscardOrFinish && selectedCardIds.length === 1)
                  ? '#fef08a'
                  : '#94a3b8',
              fontWeight: 800,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              textShadow: '0 2px 4px rgba(0,0,0,0.8)',
            }}
          >
            {canDraw && topDiscard
              ? '⚡ Take Open'
              : canDiscardOrFinish && selectedCardIds.length === 1
                ? '⚡ Discard Here'
                : 'Open Deck'}
          </span>
        </div>

        {/* 3. Finish / Declare Target Slot */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
          <div
            id="slot-finish"
            onClick={handleFinishSlotClick}
            style={{
              width: 'var(--card-w)',
              height: 'var(--card-h)',
              borderRadius: '8px',
              border: canDiscardOrFinish && selectedCardIds.length === 1
                ? '2px solid #10b981'
                : '2px dashed rgba(212, 175, 55, 0.45)',
              background: canDiscardOrFinish && selectedCardIds.length === 1
                ? 'rgba(16, 185, 129, 0.2)'
                : 'rgba(10, 26, 18, 0.35)',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: canDiscardOrFinish && selectedCardIds.length === 1 ? 'pointer' : 'default',
              boxShadow: canDiscardOrFinish && selectedCardIds.length === 1
                ? '0 0 20px rgba(16, 185, 129, 0.5)'
                : 'none',
              color: canDiscardOrFinish && selectedCardIds.length === 1
                ? '#86efac'
                : 'rgba(255, 255, 255, 0.45)',
              padding: '6px',
              textAlign: 'center',
              transition: 'all 0.2s ease',
            }}
          >
            {canDiscardOrFinish && selectedCardIds.length === 1 ? (
              <>
                <CheckCircle2 size={24} />
                <span style={{ fontSize: '10px', fontWeight: 900, marginTop: '4px', textTransform: 'uppercase' }}>
                  Declare
                </span>
              </>
            ) : (
              <>
                <Inbox size={22} />
                <span style={{ fontSize: '10px', fontWeight: 800, marginTop: '4px', textTransform: 'uppercase' }}>
                  Finish
                </span>
              </>
            )}
          </div>

          <span
            style={{
              fontSize: '11px',
              color: canDiscardOrFinish && selectedCardIds.length === 1 ? '#86efac' : '#94a3b8',
              fontWeight: 800,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              textShadow: '0 2px 4px rgba(0,0,0,0.8)',
            }}
          >
            Finish Slot
          </span>
        </div>
      </div>

      {/* Discard History Modal */}
      {showDiscardHistory && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.7)',
            backdropFilter: 'blur(5px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '16px',
          }}
          onClick={() => setShowDiscardHistory(false)}
        >
          <div
            style={{
              width: '100%',
              maxWidth: '480px',
              background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
              borderRadius: '20px',
              border: '1px solid var(--border-gold)',
              padding: '20px',
              boxShadow: '0 20px 40px rgba(0,0,0,0.8)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <History size={20} color="var(--gold-accent)" />
                <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 800, color: '#ffffff' }}>
                  Discarded Cards History ({discardHistory.length})
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setShowDiscardHistory(false)}
                style={{ background: 'none', border: 'none', color: '#94a3b8', cursor: 'pointer' }}
              >
                <X size={20} />
              </button>
            </div>

            <p style={{ margin: '0 0 16px', fontSize: '12px', color: '#94a3b8' }}>
              Cards thrown into the open pile in this hand (latest card on the right):
            </p>

            <div
              style={{
                display: 'flex',
                gap: '8px',
                overflowX: 'auto',
                padding: '10px 4px',
                borderRadius: '12px',
                background: 'rgba(0,0,0,0.3)',
              }}
            >
              {discardHistory.map((card, idx) => (
                <div key={card.instanceId || idx} style={{ flexShrink: 0 }}>
                  <CardView card={card} wildJoker={cutJoker} size="small" />
                </div>
              ))}
            </div>

            <div style={{ marginTop: '16px', textAlign: 'right' }}>
              <button
                type="button"
                className="btn-primary"
                onClick={() => setShowDiscardHistory(false)}
                style={{ padding: '6px 16px', fontSize: '12px' }}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
