import React from 'react';
import { createPortal } from 'react-dom';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import {
  Trash2,
  Award,
  Flag,
  ArrowDownToLine,
  Layers,
  ArrowUpDown,
  XCircle,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { isDiscardPhase, isDrawPhase } from '../utils/turnPhase';
import { calculateHandPenalty } from '../rules/clientValidator';
import { TurnTimerRing } from './TurnTimerRing';
import { getAvatarForPlayer } from '../utils/avatarUtils';

export const ActionControls: React.FC = () => {
  const {
    gameState,
    selectedCardIds,
    setDeclareModalOpen,
    clearSelection,
    displayName,
    groups,
    groupSelectedCards,
    autoSortHand,
  } = useGameStore();
  const [confirmDropOpen, setConfirmDropOpen] = React.useState(false);

  const gameStatus = gameState?.gameStatus;
  const isMyTurn = gameState?.isMyTurn ?? false;
  const turnPhase = gameState?.turnPhase;

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
  const myAvatar = getAvatarForPlayer(displayName);
  const wildJoker = gameState.cutJoker ?? null;
  const hasPure = groups.some((g) => g.groupType === 'PURE_SEQUENCE');
  const liveScore = calculateHandPenalty(groups, wildJoker);

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

  return (
    <div className="table-action-controls">
      <div className="bottom-control-bar">
        <div className="bcb-tools">
          <button
            id="btn-group-cards"
            type="button"
            className="bcb-tool"
            onClick={() => {
              soundEngine.play('group');
              groupSelectedCards();
            }}
            disabled={selectedCardIds.length < 2}
          >
            <Layers size={18} />
            Group{selectedCardIds.length >= 2 ? ` ${selectedCardIds.length}` : ''}
          </button>
          <button
            id="btn-sort-cards"
            type="button"
            className="bcb-tool"
            onClick={() => {
              soundEngine.play('sort');
              autoSortHand();
            }}
          >
            <ArrowUpDown size={18} />
            Sort
          </button>
          {selectedCardIds.length > 0 && (
            <button
              type="button"
              className="bcb-tool"
              onClick={clearSelection}
              aria-label="Clear selection"
            >
              <XCircle size={18} />
            </button>
          )}
        </div>

        <div className="bcb-player">
          <div className="bcb-avatar-wrap">
            {isMyTurn && (
              <div className="bcb-timer">
                <TurnTimerRing turnDeadline={gameState.turnDeadline ?? null} strokeWidth={3.5} />
              </div>
            )}
            <div className={`bcb-avatar${isMyTurn ? ' on' : ''}`}>{myAvatar.renderSvg(40)}</div>
          </div>
          <div className="bcb-player-meta">
            <div className="bcb-player-name">
              {displayName}
              <span className="bcb-vip">{myAvatar.vipTier}</span>
            </div>
            {gameStatus === 'IN_PROGRESS' && (
              <div className="bcb-player-stats">
                <span className={`bcb-score${liveScore === 0 && hasPure ? ' ok' : ''}`}>
                  {liveScore} pts
                </span>
                <span className="bcb-dot" aria-hidden />
                <span className={`bcb-pure${hasPure ? ' ok' : ''}`}>
                  {hasPure ? 'Pure run ready' : 'Need pure run'}
                </span>
              </div>
            )}
          </div>
        </div>

        <div className="bcb-actions">
          {gameStatus === 'IN_PROGRESS' && !discardPhase && (
            <>
              <button
                id="btn-action-draw-deck"
                type="button"
                className="bcb-action bcb-action--primary"
                onClick={() => handleDraw('CLOSED_DECK')}
                disabled={!drawPhase}
              >
                <ArrowDownToLine size={18} />
                Draw
              </button>
              <button
                id="btn-action-draw-discard"
                type="button"
                className="bcb-action"
                onClick={() => handleDraw('DISCARD_PILE')}
                disabled={!drawPhase || !gameState.topDiscard}
              >
                <ArrowDownToLine size={18} />
                Open
              </button>
            </>
          )}

          {gameStatus === 'IN_PROGRESS' && discardPhase && (
            <>
              <button
                id="btn-action-discard"
                type="button"
                className="bcb-action bcb-action--danger"
                onClick={handleDiscard}
                disabled={selectedCardIds.length !== 1}
              >
                <Trash2 size={18} />
                {selectedCardIds.length === 1 ? 'Discard' : 'Select 1'}
              </button>
              <button
                id="btn-action-declare"
                type="button"
                className="bcb-action bcb-action--primary"
                onClick={handleOpenDeclare}
                disabled={selectedCardIds.length !== 1}
              >
                <Award size={18} />
                Declare
              </button>
            </>
          )}

          {gameStatus === 'IN_PROGRESS' && (
            <button
              id="btn-action-drop"
              type="button"
              className="bcb-action bcb-action--danger"
              onClick={() => setConfirmDropOpen(true)}
              disabled={!isMyTurn || !drawPhase}
            >
              <Flag size={18} />
              Drop {dropPenaltyPoints}
            </button>
          )}
        </div>
      </div>

      {confirmDropOpen &&
        createPortal(
          <div className="drop-confirm-overlay" role="presentation">
            <div className="drop-confirm-card" role="dialog" aria-label="Confirm drop">
              <div className="drop-confirm-icon">
                <Flag size={24} />
              </div>
              <h3>Confirm Drop ({dropPenaltyPoints} Pts)?</h3>
              <p>
                Are you sure you want to drop this hand?
                <br />
                <strong>
                  {isFirstTurn ? 'First Drop' : 'Middle Drop'}: {dropPenaltyPoints} penalty points
                </strong>{' '}
                will be added to your score.
              </p>
              <div className="drop-confirm-actions">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => setConfirmDropOpen(false)}
                >
                  Cancel
                </button>
                <button type="button" className="btn-danger" onClick={handleConfirmDrop}>
                  Confirm Drop
                </button>
              </div>
            </div>
          </div>,
          document.body
        )}
    </div>
  );
};
