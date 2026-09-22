import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import {
  Trash2,
  Award,
  Flag,
  ArrowDownToLine,
  Loader2,
  Hand,
  Layers,
  ArrowUpDown,
  XCircle,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { isDiscardPhase, isDrawPhase, normalizeTurnPhase } from '../utils/turnPhase';
import { calculateHandPenalty } from '../rules/clientValidator';
import { TurnTimerRing } from './TurnTimerRing';
import { getAvatarForPlayer } from '../utils/avatarUtils';

export const ActionControls: React.FC = () => {
  const {
    gameState,
    selectedCardIds,
    setDeclareModalOpen,
    playerId,
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
    <div className="table-action-controls">
      {coachTitle && (
        <div className="action-bar-coach">
          <div className="action-coach-pill">
            {gameStatus === 'WAITING_FOR_PLAYERS' ? (
              <Loader2 size={13} className="spinner" color="#fbbf24" />
            ) : (
              <Hand size={13} color={isMyTurn ? '#fef08a' : '#94a3b8'} />
            )}
            <span className="action-coach-title">{coachTitle}</span>
            <span className="action-coach-hint">• {coachHint}</span>
          </div>
        </div>
      )}

      <div className="bottom-control-bar">
        <button
          id="btn-group-cards"
          type="button"
          className="btn-secondary bottom-bar-btn"
          onClick={() => {
            soundEngine.play('group');
            groupSelectedCards();
          }}
          disabled={selectedCardIds.length < 2}
        >
          <Layers size={13} />
          Group{selectedCardIds.length >= 2 ? ` (${selectedCardIds.length})` : ''}
        </button>
        <button
          id="btn-sort-cards"
          type="button"
          className="btn-secondary bottom-bar-btn"
          onClick={() => {
            soundEngine.play('sort');
            autoSortHand();
          }}
        >
          <ArrowUpDown size={13} />
          Sort
        </button>

        <div className="player-hand-user-pill">
          <div className="bottom-bar-avatar-wrap">
            {isMyTurn && (
              <div className="bottom-bar-timer">
                <TurnTimerRing turnDeadline={gameState.turnDeadline ?? null} size={30} strokeWidth={2.5} />
              </div>
            )}
            <div className={`bottom-bar-avatar${isMyTurn ? ' on' : ''}`}>
              {myAvatar.renderSvg(24)}
            </div>
          </div>
          <span className="bottom-bar-name">{displayName}</span>
          <span className="bottom-bar-vip">{myAvatar.vipTier}</span>
        </div>

        {gameStatus === 'IN_PROGRESS' && (
          <span className={`player-hand-score${liveScore === 0 && hasPure ? ' ok' : ''}`}>
            {liveScore === 0 && hasPure ? '✓ Score: 0 pts' : `Score: ${liveScore} pts`}
          </span>
        )}
        <span className={`player-hand-pure${hasPure ? ' ok' : ''}`}>
          {hasPure ? 'Pure run ✓' : 'Need pure run'}
        </span>

        {selectedCardIds.length > 0 && (
          <button
            type="button"
            className="btn-secondary bottom-bar-btn"
            onClick={clearSelection}
            aria-label="Clear selection"
          >
            <XCircle size={13} />
          </button>
        )}

        <div className="bottom-bar-spacer" />

        {gameStatus === 'IN_PROGRESS' && drawPhase && (
          <>
            <button
              id="btn-action-draw-deck"
              type="button"
              className="btn-primary bottom-bar-btn bottom-bar-btn-primary"
              onClick={() => handleDraw('CLOSED_DECK')}
            >
              <ArrowDownToLine size={14} />
              Draw from Deck
            </button>
            <button
              id="btn-action-draw-discard"
              type="button"
              className="btn-secondary bottom-bar-btn"
              onClick={() => handleDraw('DISCARD_PILE')}
              disabled={!gameState.topDiscard}
            >
              <ArrowDownToLine size={14} />
              Take Open Card
            </button>
          </>
        )}

        {gameStatus === 'IN_PROGRESS' && discardPhase && (
          <>
            <button
              id="btn-action-discard"
              type="button"
              className="btn-danger bottom-bar-btn"
              onClick={handleDiscard}
              disabled={selectedCardIds.length !== 1}
            >
              <Trash2 size={14} />
              {selectedCardIds.length === 1 ? 'Discard' : 'Select 1 Card'}
            </button>
            <button
              id="btn-action-declare"
              type="button"
              className="btn-primary bottom-bar-btn bottom-bar-btn-primary"
              onClick={handleOpenDeclare}
              disabled={selectedCardIds.length !== 1}
            >
              <Award size={14} />
              Declare Win
            </button>
          </>
        )}

        {gameStatus === 'IN_PROGRESS' && (
          <button
            id="btn-action-drop"
            type="button"
            className="btn-danger bottom-bar-btn"
            onClick={() => setConfirmDropOpen(true)}
            disabled={!isMyTurn || !drawPhase}
          >
            <Flag size={14} />
            Drop ({dropPenaltyPoints})
          </button>
        )}
      </div>

      {confirmDropOpen && (
        <div className="drop-confirm-overlay">
          <div className="drop-confirm-card">
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
        </div>
      )}
    </div>
  );
};
