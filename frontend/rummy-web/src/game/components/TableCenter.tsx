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
  const canTakeOpen = canDraw && !!topDiscard;
  const canDiscardHere = canDiscardOrFinish && selectedCardIds.length === 1;
  const canDeclare = canDiscardOrFinish && selectedCardIds.length === 1;

  const handleDrawClosed = () => {
    if (!canDraw) return;
    soundEngine.play('draw');
    clearSelection();
    socketClient.draw('CLOSED_DECK');
  };

  const handleDrawDiscard = () => {
    if (!canTakeOpen) return;
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
    if (!canDiscardHere) return;
    const cardId = selectedCardIds[0];
    soundEngine.play('discard');
    clearSelection();
    socketClient.discard(cardId);
  };

  return (
    <>
      <div className="table-center-felt" aria-label="Table piles">
        {/* Closed deck + joker */}
        <div className="deck-pod">
          <div className="deck-pod-stack">
            {cutJoker && (
              <div className="deck-joker" title="Wild joker">
                <CardView card={cutJoker} wildJoker={cutJoker} size="small" />
                <span className="deck-joker-badge">JOKER</span>
              </div>
            )}
            <button
              type="button"
              id="deck-closed"
              className={`deck-closed${canDraw ? ' is-active' : ''}`}
              onClick={handleDrawClosed}
              disabled={!canDraw}
              aria-label="Draw from closed deck"
            >
              <span className="deck-closed-layer deck-closed-layer--2" />
              <span className="deck-closed-layer deck-closed-layer--1" />
              <span className="rummy-card-back deck-closed-face" />
              <span className="deck-count-badge">{closedDeckRemaining}</span>
            </button>
          </div>
          <span className={`deck-pod-label${canDraw ? ' on' : ''}`}>
            {canDraw ? 'Tap to draw' : 'Closed'}
          </span>
        </div>

        {/* Open discard */}
        <div className="deck-pod">
          <div className="deck-pod-stack">
            <button
              type="button"
              id="pile-discard"
              className={`deck-open${canTakeOpen || canDiscardHere ? ' is-active' : ''}${
                topDiscard ? '' : ' is-empty'
              }`}
              onClick={() => {
                if (canDraw) handleDrawDiscard();
                else handleDiscardPileDrop();
              }}
              aria-label="Open discard pile"
            >
              {topDiscard ? (
                <CardView card={topDiscard} wildJoker={cutJoker} size="normal" />
              ) : (
                <span className="deck-open-empty">Empty</span>
              )}
            </button>
            {discardHistory.length > 0 && (
              <button
                type="button"
                className="deck-history-btn"
                onClick={(e) => {
                  e.stopPropagation();
                  soundEngine.play('click');
                  setShowDiscardHistory(true);
                }}
                title="Discard history"
              >
                <Eye size={12} />
              </button>
            )}
          </div>
          <span className={`deck-pod-label${canTakeOpen || canDiscardHere ? ' on' : ''}`}>
            {canTakeOpen ? 'Take open' : canDiscardHere ? 'Discard here' : 'Open'}
          </span>
        </div>

        {/* Finish / declare */}
        <div className="deck-pod">
          <button
            type="button"
            id="slot-finish"
            className={`deck-finish${canDeclare ? ' is-ready' : ''}`}
            onClick={handleFinishSlotClick}
            aria-label="Finish slot"
          >
            {canDeclare ? (
              <>
                <CheckCircle2 size={20} />
                <span>Declare</span>
              </>
            ) : (
              <>
                <Inbox size={18} />
                <span>Finish</span>
              </>
            )}
          </button>
          <span className={`deck-pod-label${canDeclare ? ' on ok' : ''}`}>Finish slot</span>
        </div>
      </div>

      {showDiscardHistory && (
        <div
          className="discard-history-overlay"
          onClick={() => setShowDiscardHistory(false)}
          role="presentation"
        >
          <div
            className="discard-history-card"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-label="Discard history"
          >
            <div className="discard-history-head">
              <div className="discard-history-title">
                <History size={18} color="var(--gold-accent)" />
                <h3>Discard history ({discardHistory.length})</h3>
              </div>
              <button type="button" className="discard-history-close" onClick={() => setShowDiscardHistory(false)}>
                <X size={18} />
              </button>
            </div>
            <p className="discard-history-hint">Latest card is on the right.</p>
            <div className="discard-history-row">
              {discardHistory.map((card, idx) => (
                <div key={card.instanceId || idx} className="discard-history-item">
                  <CardView card={card} wildJoker={cutJoker} size="small" />
                </div>
              ))}
            </div>
            <div className="discard-history-actions">
              <button type="button" className="btn-primary" onClick={() => setShowDiscardHistory(false)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
