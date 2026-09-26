import React, { useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import confetti from 'canvas-confetti';
import {
  RotateCcw,
  LogOut,
  CheckCircle2,
  AlertCircle,
  Eye,
  EyeOff,
  Layers,
  Sparkles,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { clearActiveSessionRemote } from '../utils/sessionResume';
import { SoundToggle } from './SoundToggle';
import { FullscreenToggle } from './FullscreenToggle';
import { CardView } from './CardView';
import type { CardInstance, GroupValidationType } from '../types/game';
import { evaluateCardGroup, getCardScore } from '../rules/clientValidator';

interface GameResultModalProps {
  isOpen: boolean;
}

const GROUP_CONFIG: Record<GroupValidationType, { label: string; color: string; bg: string }> = {
  PURE_SEQUENCE: { label: 'Pure', color: '#34d399', bg: 'rgba(52, 211, 153, 0.15)' },
  IMPURE_SEQUENCE: { label: 'Sequence', color: '#fbbf24', bg: 'rgba(251, 191, 36, 0.15)' },
  SET: { label: 'Set', color: '#60a5fa', bg: 'rgba(96, 165, 250, 0.15)' },
  INVALID: { label: 'Cards', color: '#f87171', bg: 'rgba(248, 113, 113, 0.12)' },
};

const SUIT_ORDER: Record<string, number> = { SPADES: 0, HEARTS: 1, CLUBS: 2, DIAMONDS: 3, NONE: 4 };
const RANK_ORDER: Record<string, number> = {
  ACE: 1,
  TWO: 2,
  THREE: 3,
  FOUR: 4,
  FIVE: 5,
  SIX: 6,
  SEVEN: 7,
  EIGHT: 8,
  NINE: 9,
  TEN: 10,
  JACK: 11,
  QUEEN: 12,
  KING: 13,
  JOKER: 14,
};

interface ShowdownGroup {
  type: GroupValidationType;
  cards: CardInstance[];
  pts: number;
}

/**
 * Organizes revealed player cards into simple Rummy groups matching the game table style.
 */
function autoGroupShowdownCards(
  cards: CardInstance[],
  wildJoker: CardInstance | null,
  isWinner: boolean,
  declaredGroups?: { cards: CardInstance[] }[]
): ShowdownGroup[] {
  // 1. If winner declared groups, use their exact declaration
  if (
    isWinner &&
    declaredGroups &&
    declaredGroups.length >= 2 &&
    declaredGroups.every((g) => g.cards && g.cards.length >= 2)
  ) {
    return declaredGroups.map((g) => ({
      type: evaluateCardGroup(g.cards, wildJoker),
      cards: g.cards,
      pts: 0,
    }));
  }

  if (!cards || cards.length === 0) return [];

  // 2. Sort cards by suit and rank
  const sorted = [...cards].sort((a, b) => {
    const s = (SUIT_ORDER[a.suit] ?? 99) - (SUIT_ORDER[b.suit] ?? 99);
    if (s !== 0) return s;
    return (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
  });

  // 3. Bucket into natural groups
  const suitBuckets: Record<string, CardInstance[]> = {};
  for (const c of sorted) {
    const key = c.printedJoker ? 'JOKER' : c.suit;
    if (!suitBuckets[key]) suitBuckets[key] = [];
    suitBuckets[key].push(c);
  }

  const result: ShowdownGroup[] = [];

  for (const [, groupCards] of Object.entries(suitBuckets)) {
    if (groupCards.length > 5) {
      const mid = Math.ceil(groupCards.length / 2);
      const c1 = groupCards.slice(0, mid);
      const c2 = groupCards.slice(mid);
      [c1, c2].forEach((chunk) => {
        const type = evaluateCardGroup(chunk, wildJoker);
        const pts = isWinner ? 0 : chunk.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
        result.push({ type, cards: chunk, pts });
      });
    } else {
      const type = evaluateCardGroup(groupCards, wildJoker);
      const pts = isWinner ? 0 : groupCards.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
      result.push({ type, cards: groupCards, pts });
    }
  }

  return result;
}

export const GameResultModal: React.FC<GameResultModalProps> = ({ isOpen }) => {
  const {
    gameState,
    gameSettlement,
    playerId,
    displayName,
    groups: myVisualGroups,
    lastKnownHand,
    lastGameConfig,
    leaveTable,
    setAutoMatchmakePending,
    setLastGameConfig,
    clearSelection,
  } = useGameStore();

  const [expandedPlayerIds, setExpandedPlayerIds] = useState<Record<string, boolean>>({});
  const [showAllCards, setShowAllCards] = useState<boolean>(true);

  const isCompleted = gameState?.gameStatus === 'COMPLETED';
  const winnerId = gameState?.winnerId;
  const isWinner = winnerId === playerId;

  useEffect(() => {
    if (isOpen && isCompleted && isWinner) {
      confetti({
        particleCount: 140,
        spread: 80,
        origin: { y: 0.5 },
      });
      soundEngine.play('win');
    } else if (isOpen && isCompleted && !isWinner) {
      soundEngine.play('lose');
    }
  }, [isOpen, isCompleted, isWinner]);

  if (!isOpen || !isCompleted || !gameState) return null;

  const opponents = gameState.opponents ?? [];
  const winnerName = isWinner
    ? (displayName || 'You')
    : opponents.find((p) => p.playerId === winnerId)?.displayName ?? 'Opponent';

  const myScore = isWinner ? 0 : (gameState.viewerScore ?? (opponents.length > 0 ? opponents[0].score : 80));
  const myStatus = isWinner ? 'WON' : (gameState.viewerStatus ?? 'LOST');

  // Viewer's cards
  const myShowdownCards: CardInstance[] =
    gameState.hand && gameState.hand.length > 0
      ? gameState.hand
      : lastKnownHand && lastKnownHand.length > 0
        ? lastKnownHand
        : myVisualGroups && myVisualGroups.length > 0
          ? myVisualGroups.flatMap((g) => g.cards)
          : [];

  // Winner's cards
  const winnerOpponent = opponents.find((p) => p.playerId === winnerId);
  const rawWinnerCards: CardInstance[] = isWinner
    ? myShowdownCards
    : winnerOpponent?.hand && winnerOpponent.hand.length > 0
      ? winnerOpponent.hand
      : gameState.winningGroups && gameState.winningGroups.length > 0
        ? gameState.winningGroups.flatMap((wg) => wg.cards)
        : [];

  const unrankedPlayers = [
    {
      playerId,
      name: displayName || 'You',
      isMe: true,
      isWinner,
      status: myStatus,
      score: myScore,
      hand: isWinner && rawWinnerCards.length > 0 ? rawWinnerCards : myShowdownCards,
      isBot: false,
    },
    ...opponents.map((opp) => {
      const isThisOpponentWinner = opp.playerId === winnerId;
      const oppHand = isThisOpponentWinner && rawWinnerCards.length > 0
        ? rawWinnerCards
        : (opp.hand || []);
      return {
        playerId: opp.playerId,
        name: opp.displayName,
        isMe: false,
        isWinner: isThisOpponentWinner,
        status: isThisOpponentWinner ? 'WON' : opp.status,
        score: opp.score,
        hand: oppHand,
        isBot: opp.isBot,
      };
    }),
  ];

  // Winner always first
  const allPlayers = [...unrankedPlayers].sort((a, b) => {
    if (a.isWinner) return -1;
    if (b.isWinner) return 1;
    return a.score - b.score;
  });

  const activeRulesetId = (gameSettlement?.rulesetId ?? lastGameConfig?.rulesetId ?? 'POINTS_13').toUpperCase();
  const isRummy21 = activeRulesetId.includes('21') || activeRulesetId === 'RUMMY_21';
  const isPoints13 = activeRulesetId.includes('POINT') || activeRulesetId === 'POINTS_13';
  const isPointsBased = isPoints13 || isRummy21;
  const maxPenaltyCap = isRummy21 ? 120 : 80;

  const variantDisplayName = isRummy21
    ? '21-Card Marriage Rummy'
    : isPoints13
      ? 'Points Rummy (13-Card)'
      : activeRulesetId.includes('POOL')
        ? (activeRulesetId.includes('201') ? 'Pool 201 Rummy' : 'Pool 101 Rummy')
        : 'Deals Rummy';

  const stakeTier = gameSettlement?.stakeTier ?? lastGameConfig?.entryFee ?? 100;
  let displayGrossPot = gameSettlement?.totalGrossPot ?? 0;
  let displayRake = gameSettlement?.platformRakeAmount ?? 0;
  let displayPrize = gameSettlement?.netWinnerPrize ?? 0;

  if (!gameSettlement) {
    if (isPointsBased) {
      const ptValue = stakeTier / maxPenaltyCap;
      let calculatedPot = 0;
      unrankedPlayers.forEach((p) => {
        if (!p.isWinner) {
          const penalty = Math.min(maxPenaltyCap, Math.max(0, p.score));
          calculatedPot += Math.min(stakeTier, penalty * ptValue);
        }
      });
      displayGrossPot = calculatedPot;
      displayRake = calculatedPot * 0.15;
      displayPrize = calculatedPot - displayRake;
    } else {
      displayGrossPot = stakeTier * unrankedPlayers.length;
      displayRake = displayGrossPot * 0.15;
      displayPrize = displayGrossPot - displayRake;
    }
  }

  const togglePlayerExpanded = (pId: string) => {
    setExpandedPlayerIds((prev) => ({
      ...prev,
      [pId]: prev[pId] !== undefined ? !prev[pId] : false,
    }));
  };

  const isPlayerExpanded = (pId: string): boolean => {
    if (expandedPlayerIds[pId] !== undefined) {
      return expandedPlayerIds[pId];
    }
    return showAllCards;
  };

  const handleRematch = () => {
    soundEngine.play('match');
    const seated = (gameState?.opponents?.length ?? 0) + 1;
    const maxPlayers = Math.max(2, lastGameConfig?.maxPlayers ?? 0, seated);
    setLastGameConfig({
      rulesetId: lastGameConfig?.rulesetId ?? 'POINTS_13',
      entryFee: lastGameConfig?.entryFee ?? 8,
      maxPlayers,
    });

    clearSelection();
    setAutoMatchmakePending(true);
    void clearActiveSessionRemote(playerId);
    socketClient.disconnect();
    leaveTable();
  };

  const handleLeave = () => {
    soundEngine.play('click');
    void clearActiveSessionRemote(playerId);
    socketClient.disconnect();
    leaveTable();
  };

  return (
    <div className="result-page-screen" role="region" aria-label="Game Showdown Result Page">
      {/* Top Navigation Bar */}
      <header className="result-top-bar">
        <div className="result-top-left">
          <button
            type="button"
            className="result-back-btn"
            onClick={handleLeave}
            title="Leave table and return to lobby"
          >
            <LogOut size={15} />
            Leave Table
          </button>
        </div>

        <div className="result-top-center">
          <span className="result-top-title">♠ GAME SHOWDOWN ♠</span>
          <span className="result-top-pill">
            {variantDisplayName} • {allPlayers.length} Players
          </span>
        </div>

        <div className="result-top-right">
          <SoundToggle compact />
          <FullscreenToggle compact />
          <span className="result-table-id-pill">
            Table {gameState.tableId ?? 'T1'}
          </span>
        </div>
      </header>

      {/* Main Page Scrollable Content */}
      <main className="result-page-content">
        {/* Winner Hero Banner (No card box - seamless header) */}
        <section className="result-hero-banner">
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', alignItems: 'center' }}>
            <h1 className="result-hero-title">
              {isWinner ? (
                <>
                  <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                  You Won the Hand!
                </>
              ) : (
                `${winnerName} Won the Hand`
              )}
            </h1>
            <p className="result-hero-subtitle">
              {isWinner
                ? 'Valid declaration with 0 penalty points. All players’ cards and settlements are shown below.'
                : 'Hand completed. Review all players’ showdown cards and final settlement below.'}
            </p>
          </div>

          {/* Unified Pot Strip (Single sleek bar, no separate nested chips) */}
          <div className="result-pot-strip">
            <div className="result-pot-strip-item">
              <span className="result-pot-strip-label">Gross Pot</span>
              <span className="result-pot-strip-val">₹{Number(displayGrossPot).toFixed(2)}</span>
            </div>

            <div className="result-pot-strip-divider" />

            <div className="result-pot-strip-item">
              <span className="result-pot-strip-label">Platform Fee (15%)</span>
              <span className="result-pot-strip-val val-red">-₹{Number(displayRake).toFixed(2)}</span>
            </div>

            <div className="result-pot-strip-divider" />

            <div className="result-pot-strip-item val-winner">
              <span className="result-pot-strip-label" style={{ color: '#86efac' }}>Winner Payout</span>
              <span className="result-pot-strip-val val-green">+₹{Number(displayPrize).toFixed(2)}</span>
            </div>
          </div>
        </section>

        {/* Scoreboard Sheet - Single Unified Sheet (No individual cards per player) */}
        <section className="result-scoreboard-sheet">
          <div className="result-sheet-header">
            <div className="result-sheet-title">
              <Layers size={15} />
              <span>SHOWDOWN SCOREBOARD</span>
            </div>

            <button
              type="button"
              className="result-toggle-btn"
              onClick={() => {
                const next = !showAllCards;
                setShowAllCards(next);
                setExpandedPlayerIds(
                  allPlayers.reduce((acc, p) => ({ ...acc, [p.playerId]: next }), {})
                );
              }}
            >
              {showAllCards ? (
                <>
                  <EyeOff size={13} /> Collapse Cards
                </>
              ) : (
                <>
                  <Eye size={13} /> Expand All Cards
                </>
              )}
            </button>
          </div>

          <div className="result-sheet-table-head">
            <span>PLAYER & STATUS</span>
            <span style={{ textAlign: 'right' }}>POINTS & NET PAYOUT</span>
          </div>

          <div className="result-sheet-rows-list">
            {allPlayers.map((p, pRankIdx) => {
              const won = p.isWinner;
              const expanded = isPlayerExpanded(p.playerId);

              // Group calculation matching the game table style
              let playerGroups: ShowdownGroup[] = [];
              if (won) {
                playerGroups = autoGroupShowdownCards(
                  p.hand,
                  gameState.cutJoker,
                  true,
                  gameState.winningGroups
                );
              } else if (
                p.isMe &&
                myVisualGroups &&
                myVisualGroups.length > 0 &&
                myVisualGroups.some((g) => g.cards.length > 0)
              ) {
                playerGroups = myVisualGroups.map((g) => ({
                  type: g.groupType,
                  cards: g.cards,
                  pts: g.deadwoodPoints,
                }));
              } else {
                playerGroups = autoGroupShowdownCards(
                  p.hand,
                  gameState.cutJoker,
                  false
                );
              }

              const playerDetail = gameSettlement?.playerDetails?.[p.playerId];
              let pLoss = playerDetail?.lossAmount;
              let pRefund = playerDetail?.refundAmount;

              if (pLoss === undefined) {
                if (p.isWinner) {
                  pLoss = 0;
                  pRefund = 0;
                } else if (isPointsBased) {
                  const ptVal = stakeTier / maxPenaltyCap;
                  const penalty = Math.min(maxPenaltyCap, Math.max(0, p.score));
                  pLoss = Math.min(stakeTier, penalty * ptVal);
                  pRefund = Math.max(0, stakeTier - pLoss);
                } else {
                  pLoss = stakeTier;
                  pRefund = 0;
                }
              }

              return (
                <div
                  key={p.playerId}
                  className={`result-sheet-row ${won ? 'winner-row' : ''}`}
                >
                  {/* Player Summary Line */}
                  <div
                    className="result-row-summary"
                    onClick={() => togglePlayerExpanded(p.playerId)}
                  >
                    <div className="result-row-player">
                      <div
                        className={`result-row-rank ${
                          won ? 'result-row-rank--winner' : 'result-row-rank--normal'
                        }`}
                      >
                        #{pRankIdx + 1}
                      </div>

                      <div className="result-row-meta">
                        <div className="result-row-name-line">
                          <span className="result-row-name">{p.name}</span>
                          {p.isMe && <span className="result-you-badge">YOU</span>}
                        </div>

                        <div className="result-row-status">
                          {won ? (
                            <span className="result-row-status--winner">
                              <CheckCircle2 size={12} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '4px' }} />
                              Winner (0 pts)
                            </span>
                          ) : p.status === 'DROPPED' ? (
                            <span className="result-row-status--dropped">
                              <AlertCircle size={12} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '4px' }} />
                              Dropped ({p.score} penalty)
                            </span>
                          ) : (
                            <span className="result-row-status--lost">
                              <AlertCircle size={12} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '4px' }} />
                              Lost ({p.score} penalty)
                            </span>
                          )}
                        </div>
                      </div>
                    </div>

                    <div className="result-row-finances">
                      <div
                        className={`result-row-delta ${
                          won ? 'result-row-delta--win' : 'result-row-delta--loss'
                        }`}
                      >
                        {won ? `+₹${Number(displayPrize).toFixed(2)}` : `-₹${Number(pLoss).toFixed(2)}`}
                      </div>

                      <div className="result-row-score-sub">
                        <span>{p.score} pts</span>
                        {pRefund !== undefined && pRefund > 0 && (
                          <span style={{ color: '#34d399', marginLeft: '6px' }}>
                            (+₹{Number(pRefund).toFixed(2)} refund)
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* Hand Groups - Clean table-like card fans, seamlessly in the row */}
                  {expanded && (
                    <div className="result-row-cards-tray">
                      {playerGroups && playerGroups.length > 0 ? (
                        playerGroups.map((group, gIdx) => {
                          const config = GROUP_CONFIG[group.type] ?? GROUP_CONFIG.INVALID;
                          return (
                            <div key={gIdx} className="result-card-group">
                              <div className="result-group-header">
                                <span
                                  className="result-group-badge"
                                  style={{ color: config.color, background: config.bg }}
                                >
                                  {config.label}
                                </span>
                                {group.pts > 0 && (
                                  <span className="result-group-pts">{group.pts} pts</span>
                                )}
                              </div>

                              <div className="result-group-fan">
                                {group.cards.map((c, cIdx) => (
                                  <div
                                    key={c.instanceId || `${gIdx}-${cIdx}`}
                                    className="result-card-slot"
                                    style={{ zIndex: cIdx + 1 }}
                                  >
                                    <CardView card={c} wildJoker={gameState.cutJoker} />
                                  </div>
                                ))}
                              </div>
                            </div>
                          );
                        })
                      ) : (
                        <div className="result-cards-concealed">
                          Cards concealed (Player dropped or sitting out)
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </section>

        {/* Action Bar below Scoreboard */}
        <footer className="result-bottom-bar">
          <button
            type="button"
            className="result-btn-leave"
            onClick={handleLeave}
          >
            <LogOut size={16} />
            Leave Table
          </button>

          <button
            type="button"
            className="result-btn-rematch"
            onClick={handleRematch}
          >
            <RotateCcw size={17} />
            Rematch Same Stake
          </button>
        </footer>
      </main>
    </div>
  );
};
