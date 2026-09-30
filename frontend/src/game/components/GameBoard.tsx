import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { OpponentSeat, type SeatPosition } from './OpponentSeat';
import { TableCenter } from './TableCenter';
import { PlayerHand } from './PlayerHand';
import { ActionControls } from './ActionControls';
import { DealAnimation, type DealTarget } from './DealAnimation';
import { DeclareModal } from './DeclareModal';
import { LogOut, Wifi, AlertCircle, Menu, X, ShieldAlert } from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { FullscreenToggle } from './FullscreenToggle';
import { soundEngine } from '../audio/soundEngine';
import { GameResultModal } from './GameResultModal';
import { PoolScoreboardModal } from './PoolScoreboardModal';
import { clearActiveSessionRemote } from '../utils/sessionResume';

function getPerimeterPosition(index: number, total: number): SeatPosition {
  // Clockwise from the viewer: first seat is on the left, last seat is the lower right.
  if (total === 1) return 'left';
  if (total === 2) return index === 0 ? 'left' : 'right';
  if (total === 3) {
    const seats: SeatPosition[] = ['left', 'top-left', 'top-right'];
    return seats[index] ?? 'left';
  }
  if (total === 4) {
    const seats: SeatPosition[] = ['left', 'top-left', 'top-right', 'right'];
    return seats[index] ?? 'left';
  }
  const seats: SeatPosition[] = ['left', 'top-left', 'top-right', 'right', 'bottom-right'];
  return seats[index % seats.length];
}

function orderBySeat<T extends { seatIndex: number }>(
  opponents: T[],
  viewerSeat: number,
  seatCount: number,
): T[] {
  const n = Math.max(seatCount, opponents.length + 1, 2);
  const offset = (seatIndex: number) => (seatIndex - viewerSeat - 1 + n) % n;
  return [...opponents].sort((a, b) => offset(a.seatIndex) - offset(b.seatIndex));
}

export const GameBoard: React.FC = () => {
  const {
    gameState,
    connectionStatus,
    errorMessage,
    leaveTable,
    lastGameConfig,
    resumePending,
  } = useGameStore();

  const [menuOpen, setMenuOpen] = React.useState(false);
  const [scoreboardOpen, setScoreboardOpen] = React.useState(false);
  const [resumeFailed, setResumeFailed] = React.useState(false);
  const [dealPlaying, setDealPlaying] = React.useState(false);
  const [dealTargets, setDealTargets] = React.useState<DealTarget[]>([]);
  const [dealtCounts, setDealtCounts] = React.useState<Record<string, number> | null>(null);
  const dealPlayedKeyRef = React.useRef<string | null>(null);
  const prevStatusRef = React.useRef<string | null>(null);
  const prevDealNumberRef = React.useRef<number | null>(null);

  React.useEffect(() => {
    useGameStore.getState().setDealInProgress(dealPlaying);
  }, [dealPlaying]);

  React.useEffect(() => {
    return () => {
      useGameStore.getState().setDealInProgress(false);
    };
  }, []);

  React.useEffect(() => {
    if (!resumePending || gameState) return;
    socketClient.ensureTableJoined();
    const t = window.setTimeout(() => {
      if (!useGameStore.getState().gameState) {
        setResumeFailed(true);
      }
    }, 8000);
    return () => window.clearTimeout(t);
  }, [resumePending, gameState]);

  // Fresh deal → play dealer flight only on lobby→table transition or deal transitions (never on refresh/resume)
  useLayoutEffect(() => {
    if (!gameState) {
      // Keep prevStatus during soft-reconnect so refresh does not look like a new deal
      return;
    }

    const status = gameState.gameStatus;
    const dealNum = gameState.dealNumber ?? 1;
    const prev = prevStatusRef.current;
    const prevDeal = prevDealNumberRef.current;

    prevStatusRef.current = status;
    prevDealNumberRef.current = dealNum;

    if (status === 'WAITING_FOR_PLAYERS') {
      dealPlayedKeyRef.current = null;
      setDealPlaying(false);
      useGameStore.getState().setDealInProgress(false);
      setDealTargets([]);
      setDealtCounts(null);
      return;
    }

    const key = `${gameState.tableId}:${gameState.gameId ?? ''}:deal${dealNum}`;
    if (dealPlayedKeyRef.current === key) return;

    const handLen = gameState.hand?.length ?? 0;
    const freshPile = (gameState.discardHistory?.length ?? 0) <= 1;

    // Only animate when we watched the lobby/deal transition in this tab session:
    // 1. Initial deal from lobby (prev === 'WAITING_FOR_PLAYERS' || prev === 'DEALING')
    // 2. Subsequent deals (prev === 'COMPLETED' or deal number changed)
    // (prev == null means first paint after browser refresh/resume — skip animation)
    const watchedDealStart =
      prev === 'WAITING_FOR_PLAYERS' ||
      prev === 'DEALING' ||
      prev === 'COMPLETED' ||
      (prevDeal != null && prevDeal !== dealNum);

    if (status === 'IN_PROGRESS' && handLen > 0 && freshPile && watchedDealStart) {
      dealPlayedKeyRef.current = key;
      const viewerSeat = gameState.viewerSeatIndex ?? 0;
      const seatCount = Math.max(
        (gameState.opponents ?? []).length + 1,
        2,
      );
      const ordered = orderBySeat(gameState.opponents ?? [], viewerSeat, seatCount);
      // Viewer first, then seats in order, so the last seat receives the last card.
      const seats: DealTarget[] = [{ id: 'self', selector: '#seat-self' }];
      for (const opponent of ordered) {
        seats.push({ id: opponent.playerId });
      }
      setDealTargets(seats);
      setDealtCounts({});
      setDealPlaying(true);
      useGameStore.getState().setDealInProgress(true);
      return;
    }

    // Already mid-hand (refresh / reconnect / discard history moved on)
    if (status === 'IN_PROGRESS' && handLen > 0) {
      dealPlayedKeyRef.current = key;
      setDealPlaying(false);
      useGameStore.getState().setDealInProgress(false);
      setDealtCounts(null);
    }
  }, [gameState]);

  React.useEffect(() => {
    if (!gameState?.tableId) {
      dealPlayedKeyRef.current = null;
      prevStatusRef.current = null;
      prevDealNumberRef.current = null;
      setDealPlaying(false);
      useGameStore.getState().setDealInProgress(false);
      setDealTargets([]);
      setDealtCounts(null);
    }
  }, [gameState?.tableId]);

  const handleLeaveTable = () => {
    const { playerId: pid } = useGameStore.getState();
    try {
      socketClient.leaveTable();
    } catch {
      // ignore
    }
    void clearActiveSessionRemote(pid);
    socketClient.disconnect();
    leaveTable();
  };

  const opponents = gameState?.opponents ?? [];
  const waitingForPlayers = gameState?.gameStatus === 'WAITING_FOR_PLAYERS';
  const [arrivalNotice, setArrivalNotice] = useState<string | null>(null);
  const [waitSeconds, setWaitSeconds] = useState(15);
  const seenOpponents = useRef<Set<string> | null>(null);

  useEffect(() => {
    if (!waitingForPlayers) return;
    setWaitSeconds(15);
    const timer = window.setInterval(() => {
      setWaitSeconds((seconds) => (seconds > 1 ? seconds - 1 : 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [waitingForPlayers, gameState?.tableId]);

  useEffect(() => {
    if (!gameState || gameState.gameStatus !== 'WAITING_FOR_PLAYERS') {
      seenOpponents.current = null;
      setArrivalNotice(null);
      return;
    }
    const current = gameState.opponents ?? [];
    if (seenOpponents.current === null) {
      seenOpponents.current = new Set(current.map((opp) => opp.playerId));
      return;
    }
    const fresh = current.filter((opp) => !seenOpponents.current!.has(opp.playerId));
    for (const opp of fresh) seenOpponents.current.add(opp.playerId);
    if (fresh.length === 0) return;
    const name = fresh[fresh.length - 1].displayName || 'A player';
    soundEngine.play('join');
    setArrivalNotice(`${name} is added`);
  }, [gameState]);

  useEffect(() => {
    if (!arrivalNotice) return;
    const timer = window.setTimeout(() => setArrivalNotice(null), 1000);
    return () => window.clearTimeout(timer);
  }, [arrivalNotice]);

  const [eliminationNotice, setEliminationNotice] = useState<string | null>(null);
  const seenEliminatedRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    const freshly = gameState?.freshlyEliminatedNames;
    if (!freshly || freshly.length === 0) return;
    const newlyEliminated = freshly.filter((name) => !seenEliminatedRef.current.has(name));
    if (newlyEliminated.length > 0) {
      for (const name of newlyEliminated) {
        seenEliminatedRef.current.add(name);
      }
      soundEngine.play('lose');
      setEliminationNotice(newlyEliminated.join(', '));
      const timer = window.setTimeout(() => setEliminationNotice(null), 4500);
      return () => window.clearTimeout(timer);
    }
  }, [gameState?.freshlyEliminatedNames]);

  const [dealNotice, setDealNotice] = useState<string | null>(null);
  const prevDealRef = useRef<number | null>(null);

  const rawRulesetId = (gameState?.rulesetId ?? lastGameConfig?.rulesetId ?? 'POINTS_13').toUpperCase();
  const entryFee = lastGameConfig?.entryFee ?? 8;
  const maxSeats = lastGameConfig?.maxPlayers ?? Math.max(2, opponents.length + 1);
  const isPointsRummy = rawRulesetId.includes('POINT');

  useEffect(() => {
    const curDeal = gameState?.dealNumber;
    if (!curDeal) return;
    if (prevDealRef.current !== null && curDeal > prevDealRef.current) {
      soundEngine.play('deal');
      const scheduled = rawRulesetId.includes('3') ? 3 : 2;
      const isPlayoff = rawRulesetId.includes('DEAL') && curDeal > scheduled;
      setDealNotice(
        isPlayoff
          ? `⚡ Sudden-Death Tie-Breaker Deal ${curDeal} Starting! ⚡`
          : `Deal ${curDeal} Starting`
      );
      const timer = window.setTimeout(() => setDealNotice(null), 3500);
      prevDealRef.current = curDeal;
      return () => window.clearTimeout(timer);
    }
    prevDealRef.current = curDeal;
  }, [gameState?.dealNumber, rawRulesetId]);

  const isRummy21 = rawRulesetId.includes('21');
  const cardsPerPlayer = isRummy21 ? 21 : 13;

  let variantName = 'Point Rummy';
  if (rawRulesetId.includes('POOL')) {
    variantName = rawRulesetId.includes('201') ? 'Pool 201' : 'Pool 101';
  } else if (rawRulesetId.includes('DEAL')) {
    const scheduled = rawRulesetId.includes('3') ? 3 : 2;
    const dealsCount = gameState?.totalDeals ?? scheduled;
    const isTieBreaker = (gameState?.dealNumber ?? 1) > scheduled;
    variantName = isTieBreaker ? '⚡ Deals Playoff' : `${dealsCount} Deals`;
  } else if (isRummy21) {
    variantName = '21-Card Rummy';
  }

  const isMultiDeal = Boolean(
    gameState?.eliminationThreshold ||
    rawRulesetId.includes('POOL') ||
    rawRulesetId.includes('DEAL') ||
    (gameState?.totalDeals && gameState.totalDeals > 1)
  );

  const pointValue = entryFee / 80;
  const stakeLabel = isPointsRummy
    ? `₹${pointValue >= 1 ? pointValue.toFixed(0) : pointValue.toFixed(2)}/pt`
    : `Entry ₹${entryFee}`;

  const totalPot = ((opponents.length + 1) * entryFee).toFixed(2);

  if (!gameState) {
    return (
      <div className="game-frame" style={{ display: 'grid', placeItems: 'center' }}>
        <div style={{ textAlign: 'center', color: '#e2e8f0', maxWidth: 360, padding: 16 }}>
          <div style={{ fontWeight: 800, fontSize: 18 }}>
            {resumeFailed ? 'Could not rejoin table' : resumePending ? 'Rejoining your table…' : 'Loading table…'}
          </div>
          <div style={{ marginTop: 8, fontSize: 13, color: '#94a3b8' }}>
            {resumeFailed
              ? 'The table may have ended, or the connection dropped. Return to lobby to play again.'
              : 'Restoring your active hand — hang tight.'}
          </div>
          {resumeFailed && (
            <button
              type="button"
              onClick={handleLeaveTable}
              style={{
                marginTop: 16,
                padding: '10px 18px',
                borderRadius: 12,
                border: '1px solid rgba(251,191,36,0.45)',
                background: 'linear-gradient(135deg,#f59e0b,#d97706)',
                color: '#111',
                fontWeight: 800,
                cursor: 'pointer',
              }}
            >
              Back to lobby
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="game-frame game-frame--board-only">
      {errorMessage && (
        <div className="board-error-toast">
          <AlertCircle size={16} />
          <span>{errorMessage}</span>
        </div>
      )}

      {waitingForPlayers && arrivalNotice && (
        <div className="board-arrival-toast" role="status">
          {arrivalNotice}
        </div>
      )}

      {dealNotice && (
        <div className="board-arrival-toast" style={{ borderColor: 'rgba(251, 191, 36, 0.7)', color: '#fef08a' }} role="status">
          🃏 {dealNotice}
        </div>
      )}

      <main className={`casino-table${dealPlaying ? ' deal-in-progress' : ''}`}>
        <div className="casino-table-stage" id="game-felt-table">
          {eliminationNotice && (
            <div className="board-elimination-toast" role="status">
              <span className="board-elimination-icon">💀</span>
              <div className="board-elimination-content">
                <strong>PLAYER ELIMINATED!</strong>
                <span>{eliminationNotice} crossed point threshold!</span>
              </div>
            </div>
          )}

          {/* Top table plate — stake / pot / live (no turn pill, no joker here) */}
          <div className="board-hud" aria-label="Table status">
            <div className="board-info-plate">
              <div className="board-info-row">
                <span className="board-info-variant">
                  <span className="board-info-variant-title">{variantName}</span>
                  <span className="board-info-variant-stake"> · {stakeLabel}</span>
                  <span className="board-info-variant-seats"> · {maxSeats} Players</span>
                </span>
                {isMultiDeal && (
                  <>
                    <span className="board-info-sep" aria-hidden />
                    <span className="board-info-deal">
                      <span className="board-info-deal-full">
                        {rawRulesetId.includes('DEAL') && (gameState?.dealNumber ?? 1) > (gameState?.totalDeals ?? 2) ? (
                          <span style={{ color: '#fbbf24', fontWeight: 900 }}>⚡ TIE-BREAKER DEAL ⚡</span>
                        ) : (
                          `DEAL ${gameState?.dealNumber ?? 1}${gameState?.totalDeals ? ` OF ${gameState.totalDeals}` : ''}`
                        )}
                      </span>
                      <span className="board-info-deal-short">
                        {rawRulesetId.includes('DEAL') && (gameState?.dealNumber ?? 1) > (gameState?.totalDeals ?? 2) ? (
                          <span style={{ color: '#fbbf24', fontWeight: 900 }}>⚡ PLAYOFF</span>
                        ) : (
                          `D${gameState?.dealNumber ?? 1}${gameState?.totalDeals ? `/${gameState.totalDeals}` : ''}`
                        )}
                      </span>
                    </span>
                  </>
                )}
                <span className="board-info-sep" aria-hidden />
                <span className="board-info-pot">
                  <span className="board-info-pot-label">POT </span>₹{totalPot}
                </span>
                <span
                  className={`board-info-live${
                    connectionStatus === 'CONNECTED' ? ' ok' : ''
                  }`}
                >
                  <Wifi size={10} />
                  <span className="board-info-live-text">{connectionStatus === 'CONNECTED' ? 'Live' : '…'}</span>
                </span>
              </div>
            </div>
            <div className="board-hud-right">
              {isMultiDeal && (
                <button
                  id="btn-pool-scoreboard"
                  type="button"
                  onClick={() => {
                    soundEngine.play('click');
                    setScoreboardOpen(true);
                  }}
                  className="board-hud-scores-btn"
                  title="Scoreboard"
                >
                  Scores
                </button>
              )}
              <SoundToggle compact />
              <FullscreenToggle compact />
              <button
                id="btn-table-menu"
                type="button"
                onClick={() => {
                  soundEngine.play('click');
                  setMenuOpen(true);
                }}
                className="board-hud-menu"
                title="Table Menu"
              >
                <Menu size={16} />
              </button>
            </div>
          </div>

          {/* Perimeter Seating (Distributed around the oval table rail) */}
          <div className="table-perimeter-seats" aria-label="Opponents">
            {(() => {
              const waiting = gameState.gameStatus === 'WAITING_FOR_PLAYERS';
              const viewerSeat = gameState.viewerSeatIndex ?? 0;
              const slotCount = waiting ? Math.max(1, maxSeats - 1) : opponents.length;
              const slots = waiting
                ? Array.from({ length: slotCount }, (_, index) => {
                    const seatIndex = (viewerSeat + 1 + index) % Math.max(maxSeats, 2);
                    return {
                      key: `seat-${seatIndex}`,
                      seatIndex,
                      player: opponents.find((opp) => opp.seatIndex === seatIndex),
                      position: getPerimeterPosition(index, slotCount),
                    };
                  })
                : orderBySeat(opponents, viewerSeat, Math.max(maxSeats, opponents.length + 1)).map(
                    (opp, index, ordered) => ({
                      key: opp.playerId,
                      seatIndex: opp.seatIndex,
                      player: opp,
                      position: getPerimeterPosition(index, ordered.length),
                    }),
                  );
              if (!waiting && slots.length === 0) {
                return (
                  <div className="opponent-waiting-pod">
                    <div className="opponent-waiting-dot" />
                    Waiting for opponents…
                  </div>
                );
              }
              return slots.map((slot) => (
                <OpponentSeat
                  key={slot.key}
                  player={slot.player}
                  activePlayerId={gameState.activePlayerId}
                  turnDeadline={gameState.turnDeadline}
                  seatNumber={slot.seatIndex}
                  gameStatus={gameState.gameStatus}
                  position={slot.position}
                  displayCount={
                    dealPlaying && slot.player
                      ? (dealtCounts?.[slot.player.playerId] ?? 0)
                      : undefined
                  }
                />
              ));
            })()}
          </div>

          {/* Table Center (Closed Deck, Wild Joker, Discard Pile, Finish Slot) */}
          <section className="table-center-wrap" aria-label="Table Center">
            <TableCenter />
            {waitingForPlayers && (
              <div className="join-wait" role="status">
                <div className="join-wait-sec">
                  {waitSeconds}
                  <span>s</span>
                </div>
                <div className="join-wait-copy">
                  <p className="join-wait-kicker">Players are joining</p>
                  <p className="join-wait-line">Game starts when the table is full</p>
                </div>
              </div>
            )}
          </section>

          {/* Bottom Station: Player Hand & Action Controls */}
          <section className="table-player-station" aria-label="Player Station">
            <PlayerHand arrivingCount={dealPlaying ? (dealtCounts?.self ?? 0) : undefined} />
            <ActionControls />
          </section>

          <DealAnimation
            active={dealPlaying}
            targets={dealTargets}
            cardsPerPlayer={cardsPerPlayer}
            onCardLanded={(targetId) => {
              setDealtCounts((prev) => {
                if (prev == null) return prev;
                return { ...prev, [targetId]: (prev[targetId] ?? 0) + 1 };
              });
            }}
            onComplete={() => {
              setDealPlaying(false);
              useGameStore.getState().setDealInProgress(false);
              setDealtCounts(null);
              if (useGameStore.getState().gameState?.isMyTurn) {
                soundEngine.play('turn');
              }
            }}
          />
        </div>
      </main>

      <DeclareModal />

      <GameResultModal
        isOpen={gameState?.gameStatus === 'COMPLETED'}
        onOpenScoreboard={() => setScoreboardOpen(true)}
      />

      <PoolScoreboardModal
        isOpen={scoreboardOpen}
        onClose={() => setScoreboardOpen(false)}
      />

      {/* Combined Table Details & Safe Leave Modal */}
      {menuOpen && (
        <div
          className="royal-dialog-backdrop"
          role="presentation"
          onClick={() => setMenuOpen(false)}
        >
          <div
            className="royal-dialog-card"
            role="dialog"
            aria-label="Table Details & Leave Confirmation"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              className="royal-dialog-close"
              onClick={() => {
                soundEngine.play('click');
                setMenuOpen(false);
              }}
              aria-label="Close"
            >
              <X size={18} />
            </button>

            <div className="royal-dialog-crest-wrap">
              <div className="royal-dialog-crest-glow" />
              <div className="royal-dialog-crest-badge">
                <LogOut size={26} color="#fbbf24" style={{ transform: 'translateX(-1px)' }} />
              </div>
            </div>

            <h3 className="royal-dialog-title">
              {gameState?.gameStatus === 'IN_PROGRESS' && !gameState?.viewerDropped
                ? 'Leave Active Table?'
                : 'Table Details'}
            </h3>
            <p className="royal-dialog-subtitle">
              {gameState?.gameStatus === 'IN_PROGRESS' && !gameState?.viewerDropped
                ? 'Your hand is currently live. Leaving the table mid-game will forfeit the round.'
                : gameState?.viewerDropped
                ? 'You have dropped this hand. You can safely leave the table now.'
                : 'Game has not started yet. You can leave now without any penalty.'}
            </p>

            <div className="table-menu-badge-wrap" style={{ padding: '0 0 12px', display: 'flex', justifyContent: 'center' }}>
              <span className="table-menu-name">
                ♠ {variantName} · {maxSeats} Players
              </span>
            </div>

            <div className="table-menu-rows" style={{ margin: '0 0 16px', textAlign: 'left' }}>
              {isPointsRummy ? (
                <div className="table-menu-row">
                  <span>Point value</span>
                  <span className="table-menu-value--gold">{stakeLabel}</span>
                </div>
              ) : (
                <div className="table-menu-row">
                  <span>Entry fee</span>
                  <span className="table-menu-value--gold">₹{entryFee.toFixed(2)}</span>
                </div>
              )}
              <div className="table-menu-row">
                <span>Table ID</span>
                <span className="table-menu-id">{gameState?.tableId ?? 'T1'}</span>
              </div>
              <div className="table-menu-row">
                <span>Display mode</span>
                <FullscreenToggle compact showLabel style={{ padding: '3px 8px', fontSize: '11px' }} />
              </div>
            </div>

            {gameState?.gameStatus === 'IN_PROGRESS' && !gameState?.viewerDropped && (
              <div className="royal-dialog-penalty-box">
                <div className="royal-dialog-penalty-label">
                  <span className="royal-dialog-penalty-tag">
                    <ShieldAlert size={13} />
                    Forfeit Penalty
                  </span>
                  <span className="royal-dialog-penalty-desc">
                    Max penalty points will apply
                  </span>
                </div>
                <div className="royal-dialog-penalty-badge">
                  +{gameState?.rulesetId?.includes('21') || gameState?.rulesetId === 'RUMMY_21' ? 120 : 80} PTS
                </div>
              </div>
            )}

            <div className="royal-dialog-actions">
              <button
                type="button"
                className="royal-btn-gold"
                onClick={() => {
                  soundEngine.play('click');
                  setMenuOpen(false);
                }}
              >
                Resume Game
              </button>
              <button
                type="button"
                className="royal-btn-danger"
                onClick={() => {
                  setMenuOpen(false);
                  handleLeaveTable();
                }}
              >
                <LogOut size={16} />
                Leave Table
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
