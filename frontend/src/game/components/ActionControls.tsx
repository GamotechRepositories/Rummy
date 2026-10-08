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
  Sparkles,
  LogOut,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { isDiscardPhase, isDrawPhase } from '../utils/turnPhase';
import { calculateHandPenalty, isJoker } from '../rules/clientValidator';
import { TurnTimerRing } from './TurnTimerRing';
import { photoForCharacter } from '../utils/avatarUtils';
import { useModalScroll } from '../hooks/useModalScroll';

export const ActionControls: React.FC = () => {
  const modalScroll = useModalScroll();
  const {
    gameState,
    selectedCardIds,
    setDeclareModalOpen,
    clearSelection,
    displayName,
    avatarId,
    groups,
    groupSelectedCards,
    autoSortHand,
    dealInProgress,
    leaveTable,
    lastGameConfig,
  } = useGameStore();
  const [confirmDropOpen, setConfirmDropOpen] = React.useState(false);
  const [confirmRejoinOpen, setConfirmRejoinOpen] = React.useState(false);
  const [justDrawnFromDiscardId, setJustDrawnFromDiscardId] = React.useState<string | null>(null);

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

  React.useEffect(() => {
    if (!isMyTurn || turnPhase !== 'AWAITING_DISCARD') {
      setJustDrawnFromDiscardId(null);
    }
  }, [isMyTurn, turnPhase]);

  // Auto-submit visual groups when Showdown timer expires if player hasn't clicked Submit Meld
  React.useEffect(() => {
    if (
      gameStatus !== 'SHOWDOWN' ||
      gameState?.hasSubmittedMeld ||
      gameState?.winnerId === gameState?.viewerPlayerId
    ) {
      return;
    }
    if (!gameState?.showdownDeadline) return;

    const deadlineMs = new Date(gameState.showdownDeadline).getTime();
    // Trigger slightly before deadline (300ms) or at 0 to guarantee delivery to server
    const msUntilExpiry = Math.max(0, deadlineMs - Date.now() - 300);

    const timer = setTimeout(() => {
      const current = useGameStore.getState();
      if (
        current.gameState?.gameStatus === 'SHOWDOWN' &&
        !current.gameState?.hasSubmittedMeld &&
        current.gameState?.winnerId !== current.gameState?.viewerPlayerId
      ) {
        soundEngine.play('click');
        socketClient.submitMeld(current.groups);
      }
    }, msUntilExpiry);

    return () => clearTimeout(timer);
  }, [
    gameStatus,
    gameState?.hasSubmittedMeld,
    gameState?.showdownDeadline,
    gameState?.winnerId,
    gameState?.viewerPlayerId,
  ]);

  if (!gameState) return null;

  const drawPhase = isDrawPhase(isMyTurn, turnPhase);
  const discardPhase = isDiscardPhase(isMyTurn, turnPhase);
  const isRummy21 = gameState.rulesetId?.includes('21') || gameState.rulesetId === 'RUMMY_21';
  const isPool201 = gameState.rulesetId?.includes('201') ?? false;
  const wildJoker = gameState.cutJoker ?? null;
  const pureCount = groups.filter((g) => g.groupType === 'PURE_SEQUENCE').length;
  const neededPure = isRummy21 ? 3 : 1;
  const hasPure = pureCount >= neededPure;
  const liveScore = calculateHandPenalty(groups, wildJoker, isRummy21 ? 120 : 80, gameState.rulesetId);

  const forbiddenDiscardId =
    (gameState.isDrawnFromDiscard && gameState.drawnCardInstanceId)
      ? gameState.drawnCardInstanceId
      : justDrawnFromDiscardId;

  const isSelectedSameAsDrawnDiscard =
    selectedCardIds.length === 1 && selectedCardIds[0] === forbiddenDiscardId;

  const handleDiscard = () => {
    if (selectedCardIds.length !== 1) return;
    if (!discardPhase) {
      soundEngine.play('error');
      return;
    }
    if (isSelectedSameAsDrawnDiscard) {
      soundEngine.play('error');
      useGameStore.getState().setErrorMessage('Cannot discard the card you just picked from the open discard pile');
      return;
    }
    const cardId = selectedCardIds[0];
    soundEngine.play('discard');
    clearSelection();
    socketClient.discard(cardId);
  };

  const isFirstTurn = gameState.firstDropAvailable
    ?? !(gameState.hasTakenFirstTurn ?? ((gameState.discardHistory?.length ?? 0) > 1));
  const dropPenaltyPoints = isRummy21
    ? (isFirstTurn ? 30 : 60)
    : isPool201
      ? (isFirstTurn ? 25 : 50)
      : (isFirstTurn ? 20 : 40);
  const isPointsTable = isRummy21 || (gameState.rulesetId ?? '').toUpperCase().includes('POINT');
  const tableStake = gameState.stakeTier || lastGameConfig?.entryFee || 0;
  const dropRupees = isPointsTable && tableStake > 0
    ? (dropPenaltyPoints * tableStake) / (isRummy21 ? 120 : 80)
    : null;

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

  const isTopDiscardJoker = !!(gameState.topDiscard && isJoker(gameState.topDiscard, wildJoker));
  const openCardPickable = gameState.topDiscardPickable ?? !isTopDiscardJoker;

  const handleDraw = (source: 'CLOSED_DECK' | 'DISCARD_PILE') => {
    if (!drawPhase) {
      soundEngine.play('error');
      return;
    }
    if (source === 'DISCARD_PILE') {
      if (!openCardPickable) {
        soundEngine.play('error');
        return;
      }
      setJustDrawnFromDiscardId(gameState.topDiscard?.instanceId ?? null);
    } else {
      setJustDrawnFromDiscardId(null);
    }
    soundEngine.play('draw');
    clearSelection();
    socketClient.draw(source);
  };

  const threshold = gameState.eliminationThreshold || (isPool201 ? 201 : (gameState.rulesetId?.includes('101') ? 101 : 0));
  const isPool = threshold > 0;
  const viewerIsEliminated = gameState.viewerIsEliminated || gameState.viewerStatus === 'ELIMINATED';
  const viewerCumulative = gameState.viewerCumulativeScore ?? gameState.viewerScore ?? 0;
  const isDealer = gameState.viewerSeatIndex === gameState.dealerSeatIndex;
  const isDangerZone = isPool && !viewerIsEliminated && viewerCumulative >= threshold * 0.75;
  const isDeals = (gameState.totalDeals ?? 0) > 1 || (gameState.rulesetId ?? '').toUpperCase().includes('DEAL');
  const viewerChips = gameState.viewerChipBalance;

  const isWaitingForNextDeal =
    gameStatus === 'IN_PROGRESS' &&
    (gameState?.viewerStatus === 'READY' || (gameState?.viewerStatus !== 'ACTIVE' && (gameState?.hand?.length ?? 0) === 0));

  const droppedOut =
    (gameState?.viewerStatus === 'DROPPED' && gameStatus === 'IN_PROGRESS') || viewerIsEliminated || isWaitingForNextDeal;

  return (
    <div className="table-action-controls">
      <div className="bottom-control-bar">
        <div className="bcb-tools">
          {!droppedOut && (
            <>
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
            </>
          )}
        </div>

        <div className="bcb-player">
          <div className="bcb-avatar-wrap">
            {((isMyTurn && !dealInProgress && !viewerIsEliminated) || (gameStatus === 'SHOWDOWN' && !gameState.hasSubmittedMeld && gameState.winnerId !== gameState.viewerPlayerId)) && (
              <div className="bcb-timer">
                <TurnTimerRing
                  turnDeadline={gameStatus === 'SHOWDOWN' ? (gameState.showdownDeadline ?? null) : (gameState.turnDeadline ?? null)}
                  strokeWidth={4.5}
                  showBadge={false}
                  enableTickSound={true}
                  extraTime={!!gameState.inExtraTime && gameStatus !== 'SHOWDOWN'}
                />
              </div>
            )}
            <div className={`bcb-avatar${((isMyTurn && gameStatus === 'IN_PROGRESS') || (gameStatus === 'SHOWDOWN' && !gameState.hasSubmittedMeld && gameState.winnerId !== gameState.viewerPlayerId)) && !dealInProgress && !viewerIsEliminated ? ' on' : ''}${viewerIsEliminated ? ' eliminated' : ''}`}>
              <img className="bcb-avatar-photo" src={photoForCharacter(avatarId)} alt="" draggable={false} />
            </div>
            {isDealer && (
              <div className="dealer-puck" title="You are the dealer for this deal">
                D
              </div>
            )}
          </div>
          <div className="bcb-player-meta">
            <div className="bcb-player-name" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span>{displayName}</span>
              {isPool && (
                isDangerZone ? (
                  <span
                    className="danger-zone-pill"
                    style={{
                      fontSize: '9.5px',
                      fontWeight: 900,
                      padding: '1px 6px',
                      borderRadius: '4px',
                    }}
                    title="Danger Zone: One drop or loss will eliminate you!"
                  >
                    <span>🔥</span>
                    <span>DANGER: {viewerCumulative}/{threshold}</span>
                  </span>
                ) : (
                  <span
                    style={{
                      fontSize: '9.5px',
                      fontWeight: 900,
                      padding: '1px 6px',
                      borderRadius: '4px',
                      background: viewerIsEliminated
                        ? 'rgba(239, 68, 68, 0.25)'
                        : 'rgba(52, 211, 153, 0.15)',
                      color: viewerIsEliminated ? '#fca5a5' : '#34d399',
                      border: `1px solid ${viewerIsEliminated ? '#ef4444' : 'rgba(255,255,255,0.15)'}`,
                    }}
                  >
                    {viewerIsEliminated ? 'OUT' : `${viewerCumulative}/${threshold}`}
                  </span>
                )
              )}
              {isDeals && viewerChips !== undefined && (
                <span
                  style={{
                    fontSize: '10px',
                    fontWeight: 900,
                    padding: '2px 7px',
                    borderRadius: '5px',
                    background: 'rgba(251, 191, 36, 0.18)',
                    color: '#fbbf24',
                    border: '1px solid rgba(251, 191, 36, 0.45)',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '3px',
                  }}
                >
                  <span>🪙</span>
                  <span>{viewerChips} Chips</span>
                </span>
              )}
            </div>
            {gameStatus === 'IN_PROGRESS' && viewerIsEliminated && (
              <div className="bcb-player-stats">
                <span className="bcb-score" style={{ color: '#ef4444', fontWeight: 900 }}>ELIMINATED</span>
                <span className="bcb-dot" aria-hidden />
                <span className="bcb-pure" style={{ color: '#94a3b8' }}>Spectating remaining table</span>
              </div>
            )}
            {gameStatus === 'IN_PROGRESS' && !viewerIsEliminated && droppedOut && (
              <div className="bcb-player-stats">
                <span className="bcb-score">{gameState?.viewerScore ?? 0} pts</span>
                <span className="bcb-dot" aria-hidden />
                <span className="bcb-pure">Sitting out</span>
              </div>
            )}
            {gameStatus === 'IN_PROGRESS' && !droppedOut && (
              <div className="bcb-player-stats">
                <span className={`bcb-score${liveScore === 0 && hasPure ? ' ok' : ''}`}>
                  {liveScore} pts
                </span>
                <span className="bcb-dot" aria-hidden />
                <span className={`bcb-pure${hasPure ? ' ok' : ''}`}>
                  {hasPure
                    ? (isRummy21 ? '3 Pure runs ready' : 'Pure run ready')
                    : (isRummy21 ? `${pureCount}/3 Pure runs` : 'Need pure run')}
                </span>
              </div>
            )}
          </div>
        </div>

        <div className="bcb-actions">
          {viewerIsEliminated && (
            <div
              className="bcb-dropped"
              style={{
                background: 'linear-gradient(135deg, rgba(185, 28, 28, 0.25), rgba(69, 10, 10, 0.35))',
                border: '1px solid rgba(239, 68, 68, 0.6)',
                color: '#fca5a5',
              }}
            >
              You reached {threshold} points and are eliminated · Spectating remaining table
            </div>
          )}

          {!viewerIsEliminated && droppedOut && (
            <div className="bcb-dropped">Dropped · {gameState?.viewerScore ?? 0} pts · sitting out</div>
          )}

          {gameStatus === 'IN_PROGRESS' && dealInProgress && (
            <div
              className="bcb-dealing-pill"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px',
                padding: '6px 16px',
                borderRadius: '999px',
                background: 'rgba(251, 191, 36, 0.12)',
                border: '1px solid rgba(251, 191, 36, 0.4)',
                color: '#fef08a',
                fontSize: '12px',
                fontWeight: 700,
                letterSpacing: '0.4px',
              }}
            >
              <span
                style={{
                  width: '7px',
                  height: '7px',
                  borderRadius: '50%',
                  background: '#fbbf24',
                  boxShadow: '0 0 8px #fbbf24',
                  animation: 'pulse 1.2s infinite ease-in-out',
                }}
              />
              Dealing cards…
            </div>
          )}

          {isWaitingForNextDeal && !viewerIsEliminated && (
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px',
                padding: '6px 14px',
                borderRadius: '8px',
                background: 'rgba(16, 185, 129, 0.15)',
                border: '1px solid rgba(16, 185, 129, 0.4)',
                color: '#6ee7b7',
                fontSize: '12px',
                fontWeight: 700,
              }}
            >
              <Sparkles size={14} />
              Rejoined table · Waiting for Deal {(gameState.dealNumber ?? 1) + 1}
            </div>
          )}

          {viewerIsEliminated && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              {gameState.canRejoin && (
                <button
                  type="button"
                  id="btn-spectator-rejoin"
                  onClick={() => {
                    soundEngine.play('click');
                    setConfirmRejoinOpen(true);
                  }}
                  className="bcb-action bcb-action--primary"
                  style={{
                    background: 'linear-gradient(135deg, #10b981, #059669)',
                    color: '#fff',
                    fontWeight: 900,
                    boxShadow: '0 0 12px rgba(16, 185, 129, 0.45)',
                  }}
                >
                  <Sparkles size={16} />
                  Re-Join (₹{gameState.rejoinFee || gameState.stakeTier || lastGameConfig?.entryFee || 0})
                </button>
              )}
              <button
                type="button"
                onClick={() => {
                  soundEngine.play('click');
                  leaveTable();
                }}
                className="bcb-action bcb-action--danger"
              >
                <LogOut size={16} />
                Leave Table
              </button>
            </div>
          )}

          {gameStatus === 'IN_PROGRESS' && !droppedOut && !discardPhase && !dealInProgress && (
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
                disabled={!drawPhase || !gameState.topDiscard || !openCardPickable}
                title={!openCardPickable ? 'Cannot pick a joker from the open discard pile' : undefined}
              >
                <ArrowDownToLine size={18} />
                Open
              </button>
            </>
          )}

          {gameStatus === 'IN_PROGRESS' && !droppedOut && discardPhase && !dealInProgress && (
            <>
              <button
                id="btn-action-discard"
                type="button"
                className="bcb-action bcb-action--danger"
                onClick={handleDiscard}
                disabled={selectedCardIds.length !== 1 || isSelectedSameAsDrawnDiscard}
                title={isSelectedSameAsDrawnDiscard ? 'Cannot discard the card you just picked from discard pile' : undefined}
              >
                <Trash2 size={18} />
                {isSelectedSameAsDrawnDiscard ? 'Cannot Discard' : selectedCardIds.length === 1 ? 'Discard' : 'Select 1'}
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

          {gameStatus === 'IN_PROGRESS' && !droppedOut && !dealInProgress && (
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

          {gameStatus === 'SHOWDOWN' && !viewerIsEliminated && (
            <>
              {(() => {
                const isWinner = gameState?.winnerId === gameState?.viewerPlayerId;
                const declarePlayerName = isWinner
                  ? 'You'
                  : gameState?.opponents.find(o => o.playerId === gameState?.winnerId)?.displayName || 'A player';

                if (isWinner) {
                  return (
                    <div style={{ color: '#fff', fontSize: '14px', fontWeight: 600 }}>
                      You declared! Waiting for opponents to submit...
                    </div>
                  );
                }

                if (gameState?.hasSubmittedMeld) {
                  return (
                    <div style={{ color: '#fff', fontSize: '14px', fontWeight: 600 }}>
                      Meld Submitted. Waiting for {declarePlayerName}...
                    </div>
                  );
                }

                return (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                    <div style={{
                      color: '#ef4444', 
                      fontSize: '14px', 
                      fontWeight: 600,
                      background: 'rgba(239, 68, 68, 0.1)',
                      padding: '6px 12px',
                      borderRadius: '8px',
                      border: '1px solid rgba(239, 68, 68, 0.3)'
                    }}>
                      {declarePlayerName} declared! Group cards quickly!
                    </div>
                    <button
                      id="btn-action-submit-meld"
                      type="button"
                      className="bcb-action bcb-action--primary"
                      onClick={() => {
                        soundEngine.play('click');
                        socketClient.submitMeld(groups);
                      }}
                      style={{
                        background: 'linear-gradient(135deg, #3b82f6, #2563eb)',
                        boxShadow: '0 0 12px rgba(59, 130, 246, 0.45)',
                      }}
                    >
                      <Award size={18} />
                      Submit Meld
                    </button>
                  </div>
                );
              })()}
            </>
          )}
        </div>
      </div>

      {confirmDropOpen &&
        createPortal(
          <div className="royal-dialog-backdrop" role="presentation" onClick={() => setConfirmDropOpen(false)}>
            <div
              className="royal-dialog-card"
              role="dialog"
              aria-label="Confirm drop"
              onClick={(e) => e.stopPropagation()}
              onWheel={modalScroll.onWheel}
              onTouchStart={modalScroll.onTouchStart}
              onTouchMove={modalScroll.onTouchMove}
            >
              <button
                type="button"
                className="royal-dialog-close"
                onClick={() => setConfirmDropOpen(false)}
                aria-label="Close"
              >
                <XCircle size={18} />
              </button>

              <div className="royal-dialog-header">
                <div className="royal-dialog-crest-wrap">
                  <div className="royal-dialog-crest-glow royal-dialog-crest-glow--amber" />
                  <div className="royal-dialog-crest-badge">
                    <Flag size={26} color="#fbbf24" />
                  </div>
                </div>

                <h3 className="royal-dialog-title">Drop This Hand?</h3>
                <p className="royal-dialog-subtitle">
                  {isPointsTable
                    ? 'You give up this hand. The penalty is settled when the hand ends.'
                    : 'You will fold this hand and sit out until the next deal starts.'}
                </p>
              </div>

              <div
                className="royal-dialog-body"
                onWheel={modalScroll.onWheel}
                onTouchStart={modalScroll.onTouchStart}
                onTouchMove={modalScroll.onTouchMove}
              >
                <div className="royal-dialog-penalty-box">
                  <div className="royal-dialog-penalty-label">
                    <span className="royal-dialog-penalty-tag">
                      {isFirstTurn ? 'FIRST DROP PENALTY' : 'MIDDLE DROP PENALTY'}
                    </span>
                    <span className="royal-dialog-penalty-desc">
                      {dropRupees !== null
                        ? `You lose ₹${dropRupees.toFixed(2)}`
                        : 'Will be added to your score'}
                    </span>
                  </div>
                  <div className="royal-dialog-penalty-badge">
                    +{dropPenaltyPoints} PTS
                  </div>
                </div>
              </div>

              <div className="royal-dialog-actions">
                <button
                  type="button"
                  className="royal-btn-gold"
                  onClick={() => setConfirmDropOpen(false)}
                >
                  Keep Playing
                </button>
                <button
                  type="button"
                  className="royal-btn-danger"
                  onClick={handleConfirmDrop}
                >
                  Confirm Drop
                </button>
              </div>
            </div>
          </div>,
          document.getElementById('root') || document.body
        )}

      {confirmRejoinOpen &&
        createPortal(
          <div className="royal-dialog-backdrop" role="presentation" onClick={() => setConfirmRejoinOpen(false)}>
            <div
              className="royal-dialog-card"
              role="dialog"
              aria-label="Confirm Re-Join"
              onClick={(e) => e.stopPropagation()}
              onWheel={modalScroll.onWheel}
              onTouchStart={modalScroll.onTouchStart}
              onTouchMove={modalScroll.onTouchMove}
            >
              <button
                type="button"
                className="royal-dialog-close"
                onClick={() => setConfirmRejoinOpen(false)}
                aria-label="Close"
              >
                <XCircle size={18} />
              </button>

              <div className="royal-dialog-header">
                <div className="royal-dialog-crest-wrap">
                  <div className="royal-dialog-crest-glow" style={{ background: 'rgba(16, 185, 129, 0.4)' }} />
                  <div className="royal-dialog-crest-badge" style={{ border: '2px solid #34d399' }}>
                    <Sparkles size={26} color="#34d399" />
                  </div>
                </div>

                <h3 className="royal-dialog-title">Re-Join Table?</h3>
                <p className="royal-dialog-subtitle">
                  You are re-entering this Pool tournament. You will participate from the next deal.
                </p>
              </div>

              <div
                className="royal-dialog-body"
                onWheel={modalScroll.onWheel}
                onTouchStart={modalScroll.onTouchStart}
                onTouchMove={modalScroll.onTouchMove}
              >
                <div
                  className="royal-dialog-penalty-box"
                  style={{
                    background: 'rgba(16, 185, 129, 0.12)',
                    border: '1px solid rgba(52, 211, 153, 0.35)',
                  }}
                >
                  <div className="royal-dialog-penalty-label">
                    <span className="royal-dialog-penalty-tag" style={{ color: '#6ee7b7' }}>
                      ENTRY FEE: ₹{gameState.rejoinFee || gameState.stakeTier || lastGameConfig?.entryFee || 0}
                    </span>
                    <span className="royal-dialog-penalty-desc">
                      Starting Score: <strong>{gameState.rejoinScore} pts</strong> (Leader + 1)
                    </span>
                  </div>
                  <div
                    className="royal-dialog-penalty-badge"
                    style={{
                      color: '#34d399',
                      background: 'rgba(52, 211, 153, 0.15)',
                      border: '1px solid rgba(52, 211, 153, 0.4)',
                    }}
                  >
                    {gameState.rejoinScore} PTS
                  </div>
                </div>
              </div>

              <div className="royal-dialog-actions">
                <button
                  type="button"
                  className="royal-btn-gold"
                  onClick={() => setConfirmRejoinOpen(false)}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  style={{
                    padding: '10px 22px',
                    borderRadius: '12px',
                    border: '1.5px solid #6ee7b7',
                    background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
                    color: '#fff',
                    fontWeight: 900,
                    fontSize: '13px',
                    cursor: 'pointer',
                    boxShadow: '0 0 15px rgba(16, 185, 129, 0.4)',
                  }}
                  onClick={() => {
                    setConfirmRejoinOpen(false);
                    soundEngine.play('click');
                    socketClient.rejoinTable();
                  }}
                >
                  Confirm & Re-Join
                </button>
              </div>
            </div>
          </div>,
          document.getElementById('root') || document.body
        )}
    </div>
  );
};
