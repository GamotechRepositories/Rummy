import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { CardView } from './CardView';
import { CheckCircle2, Inbox } from 'lucide-react';

export const TableCenter: React.FC = () => {
  const { gameState, selectedCardIds, setDeclareModalOpen } = useGameStore();

  if (!gameState) return null;

  const {
    closedDeckRemaining,
    topDiscard,
    cutJoker,
    turnPhase,
    isMyTurn,
  } = gameState;

  const canDraw = isMyTurn && turnPhase === 'DRAW';
  const canDiscardOrFinish = isMyTurn && turnPhase === 'DISCARD';

  const handleDrawClosed = () => {
    if (canDraw) {
      socketClient.draw('CLOSED_DECK');
    }
  };

  const handleDrawDiscard = () => {
    if (canDraw && topDiscard) {
      socketClient.draw('DISCARD_PILE');
    }
  };

  const handleFinishSlotClick = () => {
    if (canDiscardOrFinish) {
      if (selectedCardIds.length === 1) {
        setDeclareModalOpen(true);
      } else {
        alert('Please select exactly 1 card to place in the Finish slot to declare.');
      }
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '40px',
        padding: '16px 28px',
        background: 'rgba(5, 29, 18, 0.65)',
        borderRadius: '24px',
        border: '2px solid rgba(212, 175, 55, 0.3)',
        boxShadow: 'inset 0 0 30px rgba(0, 0, 0, 0.6)',
      }}
    >
      {/* 1. Closed Draw Deck + Wild Joker */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px' }}>
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
          {/* Wild Joker Card Underneath */}
          {cutJoker && (
            <div
              style={{
                position: 'absolute',
                left: '-42px',
                transform: 'rotate(-25deg)',
                zIndex: 1,
                filter: 'drop-shadow(0 4px 8px rgba(0,0,0,0.5))',
              }}
            >
              <CardView card={cutJoker} wildJoker={cutJoker} size="small" />
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
              transition: 'transform 0.2s ease',
              transform: canDraw ? 'scale(1.04)' : 'scale(1)',
            }}
          >
            <div className="rummy-card-back" />
            {/* 3D Stack Illusion */}
            <div
              style={{
                position: 'absolute',
                top: '2px',
                left: '2px',
                width: '72px',
                height: '104px',
                borderRadius: '8px',
                border: '1px solid rgba(255,255,255,0.2)',
                zIndex: -1,
              }}
            />
            {/* Card Count Badge */}
            <span
              style={{
                position: 'absolute',
                bottom: '-8px',
                left: '50%',
                transform: 'translateX(-50%)',
                background: 'rgba(15, 23, 42, 0.95)',
                color: 'var(--gold-light)',
                border: '1px solid var(--border-gold)',
                fontSize: '10px',
                fontWeight: 800,
                padding: '2px 8px',
                borderRadius: '10px',
                whiteSpace: 'nowrap',
              }}
            >
              {closedDeckRemaining} cards
            </span>
          </div>
        </div>

        <span
          style={{
            fontSize: '11px',
            color: canDraw ? 'var(--gold-accent)' : 'var(--text-muted)',
            fontWeight: canDraw ? 700 : 500,
            marginTop: '8px',
          }}
        >
          {canDraw ? '👆 Click to Draw' : 'Closed Deck'}
        </span>
      </div>

      {/* 2. Open Discard Pile */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px' }}>
        <div
          id="pile-discard"
          onClick={handleDrawDiscard}
          className={canDraw && topDiscard ? 'gold-glow' : ''}
          style={{
            minWidth: '72px',
            minHeight: '104px',
            borderRadius: '8px',
            border: '2px dashed rgba(255, 255, 255, 0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: canDraw && topDiscard ? 'pointer' : 'default',
            position: 'relative',
          }}
        >
          {topDiscard ? (
            <CardView card={topDiscard} wildJoker={cutJoker} />
          ) : (
            <div style={{ color: 'var(--text-muted)', fontSize: '11px' }}>Empty</div>
          )}
        </div>

        <span
          style={{
            fontSize: '11px',
            color: canDraw && topDiscard ? 'var(--gold-accent)' : 'var(--text-muted)',
            fontWeight: canDraw && topDiscard ? 700 : 500,
          }}
        >
          {canDraw && topDiscard ? '👆 Draw Discard' : 'Open Discard'}
        </span>
      </div>

      {/* 3. Finish / Declare Target Slot */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px' }}>
        <div
          id="slot-finish"
          onClick={handleFinishSlotClick}
          style={{
            width: '72px',
            height: '104px',
            borderRadius: '8px',
            border: canDiscardOrFinish && selectedCardIds.length === 1
              ? '2px solid var(--color-pure)'
              : '2px dashed rgba(255, 255, 255, 0.25)',
            background: canDiscardOrFinish && selectedCardIds.length === 1
              ? 'rgba(16, 185, 129, 0.15)'
              : 'rgba(0, 0, 0, 0.2)',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: canDiscardOrFinish && selectedCardIds.length === 1 ? 'pointer' : 'default',
            boxShadow: canDiscardOrFinish && selectedCardIds.length === 1
              ? '0 0 15px rgba(16, 185, 129, 0.4)'
              : 'none',
            color: canDiscardOrFinish && selectedCardIds.length === 1
              ? 'var(--color-pure)'
              : 'var(--text-muted)',
            padding: '6px',
            textAlign: 'center',
            transition: 'all 0.2s ease',
          }}
        >
          {canDiscardOrFinish && selectedCardIds.length === 1 ? (
            <>
              <CheckCircle2 size={24} />
              <span style={{ fontSize: '11px', fontWeight: 700, marginTop: '4px' }}>
                Declare Show
              </span>
            </>
          ) : (
            <>
              <Inbox size={22} style={{ opacity: 0.5 }} />
              <span style={{ fontSize: '10px', marginTop: '4px', opacity: 0.7 }}>
                Finish Slot
              </span>
            </>
          )}
        </div>

        <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
          14th Card Slot
        </span>
      </div>
    </div>
  );
};
