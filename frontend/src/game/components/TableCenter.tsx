import React, { useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { CardView } from './CardView';
import { Eye } from 'lucide-react';
import { isDiscardPhase, isDrawPhase } from '../utils/turnPhase';
import { soundEngine } from '../audio/soundEngine';
import { DiscardHistoryModal } from './DiscardHistoryModal';

const DeckSlotText: React.FC<{ text: string }> = ({ text }) => {
  const vbWidth = Math.max(46, text.length * 8.5);
  return (
    <svg
      className="deck-slot-svg"
      viewBox={`0 0 ${vbWidth} 18`}
      preserveAspectRatio="xMidYMid meet"
      aria-label={text}
    >
      <text
        x={vbWidth / 2}
        y="12.5"
        textAnchor="middle"
        className="deck-slot-svg-text"
      >
        {text}
      </text>
    </svg>
  );
};

export const TableCenter: React.FC = () => {
  const { gameState, selectedCardIds, setDeclareModalOpen, clearSelection, dealInProgress } = useGameStore();
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

  const waiting = gameState.gameStatus === 'WAITING_FOR_PLAYERS';
  const canDraw = !dealInProgress && isDrawPhase(isMyTurn, turnPhase);
  const canDiscardOrFinish = !dealInProgress && isDiscardPhase(isMyTurn, turnPhase);
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
    const forbiddenId =
      (gameState.isDrawnFromDiscard && gameState.drawnCardInstanceId)
        ? gameState.drawnCardInstanceId
        : null;
    if (forbiddenId && cardId === forbiddenId) {
      soundEngine.play('error');
      useGameStore.getState().setErrorMessage('Cannot discard the card you just picked from the open discard pile');
      return;
    }
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
          <span className={`deck-pod-label${canDraw || waiting ? ' on' : ''}`}>
            {canDraw || waiting ? 'Tap to draw' : 'Closed'}
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
                <DeckSlotText text="Empty" />
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
          <span className={`deck-pod-label${canTakeOpen || canDiscardHere || waiting ? ' on' : ''}`}>
            {waiting || canTakeOpen ? 'Take open' : canDiscardHere ? 'Discard here' : 'Open'}
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
            <DeckSlotText text={canDeclare ? 'Declare' : 'Finish'} />
          </button>
          <span className={`deck-pod-label${canDeclare ? ' on ok' : waiting ? ' on' : ''}`}>Finish slot</span>
        </div>
      </div>

      <DiscardHistoryModal
        isOpen={showDiscardHistory}
        onClose={() => setShowDiscardHistory(false)}
        discardHistory={discardHistory}
        cutJoker={cutJoker}
      />
    </>
  );
};
