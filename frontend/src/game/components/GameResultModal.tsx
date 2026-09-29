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
  Trophy,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { clearActiveSessionRemote } from '../utils/sessionResume';
import { SoundToggle } from './SoundToggle';
import { FullscreenToggle } from './FullscreenToggle';
import { CardView } from './CardView';
import type { CardInstance, GroupValidationType } from '../types/game';
import { evaluateCardGroup, getCardScore } from '../rules/clientValidator';
import { photoForCharacter, getAvatarForPlayer } from '../utils/avatarUtils';

interface GameResultModalProps {
  isOpen: boolean;
  onOpenScoreboard?: () => void;
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

export const GameResultModal: React.FC<GameResultModalProps> = ({ isOpen, onOpenScoreboard }) => {
  const {
    gameState,
    gameSettlement,
    playerId,
    displayName,
    avatarId,
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

  const activeRulesetId = (gameSettlement?.rulesetId ?? gameState?.rulesetId ?? lastGameConfig?.rulesetId ?? 'POINTS_13').toUpperCase();
  const isPool = Boolean(gameState?.eliminationThreshold || activeRulesetId.includes('POOL'));
  const isDeals = Boolean((gameState?.totalDeals ?? 0) > 1 || activeRulesetId.includes('DEAL'));
  const totalDeals = gameState?.totalDeals ?? (activeRulesetId.includes('3') ? 3 : 2);
  const threshold = gameState?.eliminationThreshold || (activeRulesetId.includes('201') ? 201 : 101);
  const isMultiDealGame = isPool || isDeals;
  const isIntermediateDeal = isMultiDealGame && !gameState?.tournamentWinnerId;
  const isTournamentWinner = isMultiDealGame && Boolean(gameState?.tournamentWinnerId);

  const [dealCountdown, setDealCountdown] = useState<number>(gameState?.nextDealCountdown ?? 5);

  useEffect(() => {
    if (gameState?.nextDealCountdown !== undefined && gameState?.nextDealCountdown !== null) {
      setDealCountdown(gameState.nextDealCountdown);
    }
  }, [gameState?.nextDealCountdown]);

  useEffect(() => {
    if (!isOpen || !isIntermediateDeal) return;
    const interval = setInterval(() => {
      setDealCountdown((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [isOpen, isIntermediateDeal]);

  useEffect(() => {
    if (!isOpen || !isCompleted) return;
    if (isTournamentWinner) {
      if (gameState?.tournamentWinnerId === playerId) {
        confetti({
          particleCount: 160,
          spread: 90,
          origin: { y: 0.5 },
        });
        soundEngine.play('win');
      } else {
        soundEngine.play('lose');
      }
    } else if (isWinner) {
      confetti({
        particleCount: 130,
        spread: 75,
        origin: { y: 0.5 },
      });
      soundEngine.play('win');
    } else {
      soundEngine.play('lose');
    }
  }, [isOpen, isCompleted, isWinner, isTournamentWinner, gameState?.tournamentWinnerId, playerId]);

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

  const viewerCum = gameState.viewerCumulativeScore ?? (isWinner ? 0 : myScore);
  const viewerChips = gameState.viewerChipBalance ?? 0;
  const viewerEliminated = Boolean(
    gameState.viewerIsEliminated ||
    gameState.viewerStatus === 'ELIMINATED' ||
    (isPool && viewerCum >= threshold)
  );

  const unrankedPlayers = [
    {
      playerId,
      name: displayName || 'You',
      isMe: true,
      isWinner,
      status: myStatus,
      score: myScore,
      cumulativeScore: viewerCum,
      chipBalance: viewerChips,
      isEliminated: viewerEliminated,
      hand: isWinner && rawWinnerCards.length > 0 ? rawWinnerCards : myShowdownCards,
      isBot: false,
      avatarPhoto: photoForCharacter(avatarId),
    },
    ...opponents.map((opp) => {
      const isThisOpponentWinner = opp.playerId === winnerId;
      const oppHand = isThisOpponentWinner && rawWinnerCards.length > 0
        ? rawWinnerCards
        : (opp.hand || []);
      const oppCum = opp.cumulativeScore ?? opp.score ?? 0;
      const oppStanding = gameState.standings?.find((s) => s.playerId === opp.playerId);
      const oppChips = opp.chipBalance ?? oppStanding?.chipBalance ?? 0;
      const oppElim = Boolean(
        opp.isEliminated ||
        opp.status === 'ELIMINATED' ||
        (isPool && oppCum >= threshold)
      );
      const av = getAvatarForPlayer(opp.displayName || opp.playerId);
      return {
        playerId: opp.playerId,
        name: opp.displayName,
        isMe: false,
        isWinner: isThisOpponentWinner,
        status: isThisOpponentWinner ? 'WON' : opp.status,
        score: opp.score,
        cumulativeScore: oppCum,
        chipBalance: oppChips,
        isEliminated: oppElim,
        hand: oppHand,
        isBot: opp.isBot,
        avatarPhoto: av.photo,
      };
    }),
  ];

  // Winner always first, in Deals highest chip balance first, in pool active lowest cumulative score first
  const matchWinnerId = isTournamentWinner ? (gameState.tournamentWinnerId || winnerId) : winnerId;
  const allPlayers = [...unrankedPlayers].sort((a, b) => {
    if (matchWinnerId) {
      if (a.playerId === matchWinnerId) return -1;
      if (b.playerId === matchWinnerId) return 1;
    }
    if (isDeals) {
      return (b.chipBalance ?? 0) - (a.chipBalance ?? 0);
    }
    if (isPool) {
      if (a.isEliminated && !b.isEliminated) return 1;
      if (!a.isEliminated && b.isEliminated) return -1;
      return a.cumulativeScore - b.cumulativeScore;
    }
    return a.score - b.score;
  });

  const history = gameState.dealHistory ?? [];
  const maxRecordedDeals = Math.max(
    history.length,
    isDeals ? totalDeals : 1,
    gameState.dealNumber ?? 1
  );

  const getPlayerDealScore = (pId: string, dNum: number, pScore: number, pIsWinner: boolean): number | null => {
    const rec = history.find((h) => h.dealNumber === dNum);
    if (rec && rec.roundScores && rec.roundScores[pId] !== undefined) {
      return rec.roundScores[pId];
    }
    // If this deal is the current deal and record isn't in history yet
    if (dNum === (gameState.dealNumber ?? 1)) {
      return pIsWinner ? 0 : pScore;
    }
    return null;
  };

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
        : `${totalDeals} Deals Rummy`;

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
          <span className="result-top-title">
            {isIntermediateDeal
              ? `♠ DEAL ${gameState.dealNumber ?? 1} SHOWDOWN ♠`
              : isTournamentWinner
              ? (isDeals ? '🏆 DEALS CHAMPION 🏆' : '🏆 POOL TOURNAMENT CHAMPION 🏆')
              : '♠ GAME SHOWDOWN ♠'}
          </span>
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
        {/* Winner Hero Banner */}
        <section className="result-hero-banner">
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', alignItems: 'center' }}>
            <h1 className="result-hero-title">
              {isTournamentWinner ? (
                gameState.tournamentWinnerId === playerId ? (
                  <>
                    <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                    Tournament Champion!
                  </>
                ) : (
                  `🏆 ${winnerName} Won the ${isDeals ? 'Deals Match' : 'Pool Tournament'}!`
                )
              ) : isIntermediateDeal ? (
                isWinner ? (
                  <>
                    <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                    You Won Deal {gameState.dealNumber ?? 1}!
                  </>
                ) : (
                  `${winnerName} Won Deal ${gameState.dealNumber ?? 1}`
                )
              ) : isWinner ? (
                <>
                  <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                  You Won the Hand!
                </>
              ) : (
                `${winnerName} Won the Hand`
              )}
            </h1>
            <p className="result-hero-subtitle">
              {isTournamentWinner
                ? (gameState.tournamentWinnerId === playerId
                    ? (isDeals
                        ? `Congratulations! You had the most chips after ${totalDeals} deals and won the match!`
                        : 'Congratulations! You are the last surviving player standing and won the pool pot!')
                    : `Final standings after ${gameState.dealNumber ?? 1} deals. Review scores below.`)
                : isIntermediateDeal
                ? (isDeals
                    ? `Deal ${gameState.dealNumber ?? 1} of ${totalDeals} completed. Chips transferred to the deal winner!`
                    : `Deal completed. Penalties added to cumulative scores. Reach ${threshold} to get eliminated.`)
                : isWinner
                ? 'Valid declaration with 0 penalty points. All players’ cards and settlements are shown below.'
                : 'Hand completed. Review all players’ showdown cards and final settlement below.'}
            </p>

            {isIntermediateDeal && (
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '8px',
                  background: 'rgba(245, 158, 11, 0.15)',
                  border: '1px solid rgba(251, 191, 36, 0.4)',
                  borderRadius: '999px',
                  padding: '5px 16px',
                  fontSize: '12.5px',
                  fontWeight: 800,
                  color: '#fef08a',
                  marginTop: '8px',
                }}
              >
                <RotateCcw size={14} style={{ animation: 'spin 4s linear infinite' }} />
                <span>Next Deal starting in <strong>{dealCountdown}s</strong>...</span>
              </div>
            )}

            {isPool && viewerEliminated && (
              <div
                style={{
                  marginTop: '10px',
                  padding: '10px 18px',
                  borderRadius: '12px',
                  background: gameState?.canRejoin
                    ? 'linear-gradient(135deg, rgba(239, 68, 68, 0.22), rgba(16, 185, 129, 0.18))'
                    : 'rgba(239, 68, 68, 0.18)',
                  border: gameState?.canRejoin
                    ? '1.5px solid rgba(16, 185, 129, 0.5)'
                    : '1px solid rgba(239, 68, 68, 0.45)',
                  color: '#fef2f2',
                  fontSize: '12.5px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: '12px',
                  maxWidth: '620px',
                  width: '100%',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', textAlign: 'left' }}>
                  <AlertCircle size={16} color={gameState?.canRejoin ? '#34d399' : '#ef4444'} />
                  <div>
                    <div>
                      <strong>Eliminated ({viewerCum}/{threshold} pts).</strong>
                    </div>
                    <div style={{ fontSize: '11.5px', color: '#cbd5e1' }}>
                      {gameState?.canRejoin
                        ? `Eligible to Re-Join at ${gameState.rejoinScore} pts!`
                        : `Re-Join closed (highest active score > ${threshold === 201 ? 174 : 79} pts).`}
                    </div>
                  </div>
                </div>

                {gameState?.canRejoin && (
                  <button
                    type="button"
                    onClick={() => {
                      soundEngine.play('click');
                      socketClient.rejoinTable();
                    }}
                    style={{
                      background: 'linear-gradient(135deg, #10b981, #059669)',
                      color: '#fff',
                      border: '1px solid #6ee7b7',
                      borderRadius: '8px',
                      padding: '7px 14px',
                      fontWeight: 800,
                      fontSize: '12px',
                      cursor: 'pointer',
                      whiteSpace: 'nowrap',
                      boxShadow: '0 0 10px rgba(16, 185, 129, 0.4)',
                    }}
                  >
                    ✨ Re-Join (₹{gameState.rejoinFee || lastGameConfig?.entryFee || 8})
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Unified Pot Strip */}
          <div className="result-pot-strip">
            {isIntermediateDeal ? (
              isDeals ? (
                <>
                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Tournament Prize</span>
                    <span className="result-pot-strip-val" style={{ color: '#fbbf24' }}>
                      ₹{Number(displayPrize).toFixed(2)}
                    </span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Current Deal</span>
                    <span className="result-pot-strip-val" style={{ color: '#60a5fa' }}>
                      Deal {gameState.dealNumber ?? 1} of {totalDeals}
                    </span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item val-winner">
                    <span className="result-pot-strip-label" style={{ color: '#86efac' }}>Chip Leader</span>
                    <span className="result-pot-strip-val val-green">
                      {allPlayers[0]?.name ?? 'Leader'} ({allPlayers[0]?.chipBalance ?? 0} 🪙)
                    </span>
                  </div>
                </>
              ) : (
                <>
                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Pool Prize Pot</span>
                    <span className="result-pot-strip-val" style={{ color: '#fbbf24' }}>
                      ₹{Number(displayGrossPot).toFixed(2)}
                    </span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Elimination Limit</span>
                    <span className="result-pot-strip-val val-red">{threshold} pts</span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item val-winner">
                    <span className="result-pot-strip-label" style={{ color: '#86efac' }}>Survivors Remaining</span>
                    <span className="result-pot-strip-val val-green">
                      {allPlayers.filter((p) => !p.isEliminated).length} / {allPlayers.length}
                    </span>
                  </div>
                </>
              )
            ) : (
              <>
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
                  <span className="result-pot-strip-label" style={{ color: '#86efac' }}>
                    {isPool ? 'Pool Champion Prize' : isDeals ? 'Deals Champion Prize' : 'Winner Payout'}
                  </span>
                  <span className="result-pot-strip-val val-green">+₹{Number(displayPrize).toFixed(2)}</span>
                </div>
              </>
            )}
          </div>
        </section>

        {/* Multi-Deal Scoreboard Breakdown Table */}
        {isMultiDealGame && (
          <section className="result-multi-deal-card" aria-label="Tournament All Deals Scoreboard">
            <div className="result-multi-deal-header">
              <div className="result-multi-deal-title">
                <Trophy size={16} color="#fbbf24" />
                <span>
                  {isDeals
                    ? `${totalDeals} DEALS TOURNAMENT SCORECARD`
                    : `POOL ${threshold} ROUNDS BREAKDOWN`}
                </span>
              </div>
              <span className="result-multi-deal-badge">
                {isDeals
                  ? `🪙 Starting Chips: ${totalDeals * 80}`
                  : `Elimination Limit: ${threshold} pts`}
              </span>
            </div>

            <div className="result-multi-deal-table-wrap">
              <table className="royal-scoreboard-table">
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left', minWidth: '150px' }}>Player</th>
                    {Array.from({ length: maxRecordedDeals }).map((_, idx) => (
                      <th key={idx} style={{ textAlign: 'center', minWidth: '70px' }}>
                        Deal {idx + 1}
                      </th>
                    ))}
                    <th style={{ textAlign: 'center', minWidth: '110px' }}>
                      {isDeals ? 'Chips Balance' : 'Total Points'}
                    </th>
                    {!isIntermediateDeal && (
                      <th style={{ textAlign: 'center', minWidth: '100px' }}>Net Payout</th>
                    )}
                    <th style={{ textAlign: 'right', minWidth: '95px' }}>Rank & Status</th>
                  </tr>
                </thead>
                <tbody>
                  {allPlayers.map((p, rankIdx) => {
                    const isTournamentChamp =
                      Boolean(gameState.tournamentWinnerId && gameState.tournamentWinnerId === p.playerId) ||
                      (!gameState.tournamentWinnerId && p.isWinner);
                    const isDanger = isPool && !p.isEliminated && (p.cumulativeScore ?? 0) >= threshold * 0.75;
                    const percent = isPool ? Math.min(100, Math.round(((p.cumulativeScore ?? 0) / threshold) * 100)) : 0;

                    let scoreColor = '#34d399';
                    if (p.isEliminated) scoreColor = '#ef4444';
                    else if (isDanger) scoreColor = '#f87171';
                    else if ((p.cumulativeScore ?? 0) >= threshold * 0.5) scoreColor = '#fbbf24';

                    const rowClass = `${p.isMe ? 'royal-scoreboard-row--viewer' : ''} ${p.isEliminated ? 'royal-scoreboard-row--eliminated' : ''}`;

                    return (
                      <tr key={p.playerId} className={rowClass}>
                        {/* Player Info */}
                        <td>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <div
                              style={{
                                width: '28px',
                                height: '28px',
                                borderRadius: '50%',
                                overflow: 'hidden',
                                border: `1.5px solid ${isTournamentChamp ? '#fbbf24' : 'rgba(255,255,255,0.2)'}`,
                                background: '#1e293b',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                flexShrink: 0,
                              }}
                            >
                              {p.avatarPhoto ? (
                                <img src={p.avatarPhoto} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                              ) : (
                                <span style={{ fontSize: '11px', color: '#94a3b8' }}>#</span>
                              )}
                            </div>

                            <div style={{ textAlign: 'left' }}>
                              <div style={{ fontWeight: 800, color: p.isMe ? '#fde047' : '#f8fafc', fontSize: '12.5px' }}>
                                {p.name}
                                {p.isMe && <span style={{ color: '#fbbf24', marginLeft: '4px', fontSize: '10.5px' }}>(You)</span>}
                              </div>
                              <div style={{ fontSize: '10.5px', color: '#94a3b8', marginTop: '1px' }}>
                                {isTournamentChamp && <span style={{ color: '#fbbf24', marginRight: '6px' }}>👑 Champion</span>}
                                {!isDeals && isDanger && <span style={{ color: '#f87171', marginRight: '6px' }}>Danger</span>}
                                {p.isBot && <span style={{ color: '#64748b' }}>AI</span>}
                              </div>
                            </div>
                          </div>
                        </td>

                        {/* Deal Columns */}
                        {Array.from({ length: maxRecordedDeals }).map((_, dIdx) => {
                          const dNum = dIdx + 1;
                          const pts = getPlayerDealScore(p.playerId, dNum, p.score, p.isWinner);
                          return (
                            <td key={dIdx} style={{ textAlign: 'center' }}>
                              {pts === 0 ? (
                                <span className="royal-score-pill royal-score-pill--win">0 pts</span>
                              ) : pts !== null ? (
                                <span className="royal-score-pill royal-score-pill--penalty">+{pts} pts</span>
                              ) : (
                                <span style={{ color: '#64748b' }}>—</span>
                              )}
                            </td>
                          );
                        })}

                        {/* Chips / Total Points */}
                        <td style={{ textAlign: 'center' }}>
                          {isDeals ? (
                            <div style={{ fontWeight: 900, fontSize: '13.5px', color: '#fbbf24', display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                              <span>🪙</span>
                              <span>{p.chipBalance ?? 0}</span>
                            </div>
                          ) : (
                            <>
                              <div style={{ fontWeight: 800, fontSize: '13px', color: scoreColor }}>
                                {p.cumulativeScore ?? 0} <span style={{ fontSize: '10.5px', color: '#64748b' }}>/ {threshold}</span>
                              </div>
                              <div className="royal-score-bar-bg">
                                <div
                                  className={`royal-score-bar-fill ${isDanger || p.isEliminated ? 'royal-score-bar-fill--danger' : ''}`}
                                  style={{ width: `${percent}%` }}
                                />
                              </div>
                            </>
                          )}
                        </td>

                        {/* Net Payout */}
                        {!isIntermediateDeal && (
                          <td style={{ textAlign: 'center' }}>
                            {isTournamentChamp ? (
                              <span style={{ color: '#34d399', fontWeight: 900, fontSize: '13px' }}>
                                +₹{Number(displayPrize).toFixed(2)}
                              </span>
                            ) : (
                              <span style={{ color: '#f87171', fontWeight: 800, fontSize: '12.5px' }}>
                                -₹{Number(stakeTier).toFixed(2)}
                              </span>
                            )}
                          </td>
                        )}

                        {/* Rank & Status */}
                        <td style={{ textAlign: 'right' }}>
                          {isTournamentChamp ? (
                            <span style={{ color: '#fbbf24', fontWeight: 900, fontSize: '11px', background: 'rgba(251, 191, 36, 0.15)', border: '1px solid rgba(251, 191, 36, 0.4)', borderRadius: '6px', padding: '2px 7px' }}>
                              👑 {isDeals ? 'CHAMPION' : 'WINNER'}
                            </span>
                          ) : p.isEliminated ? (
                            <span style={{ color: '#ef4444', fontWeight: 800, fontSize: '11px' }}>
                              ELIMINATED
                            </span>
                          ) : (
                            <span style={{ color: '#94a3b8', fontWeight: 800, fontSize: '11.5px' }}>
                              Rank #{rankIdx + 1}
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {/* Scoreboard Sheet - Single Unified Sheet (No individual cards per player) */}
        <section className="result-scoreboard-sheet">
          <div className="result-sheet-header">
            <div className="result-sheet-title">
              <Layers size={15} />
              <span>{isMultiDealGame ? `DEAL ${gameState.dealNumber ?? 1} SHOWDOWN CARDS` : 'SHOWDOWN SCOREBOARD'}</span>
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
                              {isPool || isDeals ? 'Deal Winner (0 pts)' : 'Winner (0 pts)'}
                            </span>
                          ) : p.isEliminated ? (
                            <span style={{ color: '#ef4444', fontWeight: 800, fontSize: '11px' }}>
                              <AlertCircle size={12} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '4px' }} />
                              ELIMINATED ({p.score} penalty)
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
                      {isIntermediateDeal ? (
                        <>
                          <div
                            className={`result-row-delta ${
                              p.isEliminated ? 'result-row-delta--loss' : won ? 'result-row-delta--win' : 'result-row-delta--loss'
                            }`}
                            style={p.isEliminated ? { color: '#ef4444' } : undefined}
                          >
                            {isDeals
                              ? (won ? 'Won Deal' : `-${p.score} chips`)
                              : (p.isEliminated ? 'OUT' : won ? '0 pts' : `+${p.score} pts`)}
                          </div>

                          <div className="result-row-score-sub">
                            {isDeals ? (
                              <span style={{ fontWeight: 800, color: '#fbbf24' }}>
                                🪙 {p.chipBalance ?? 0} chips
                              </span>
                            ) : (
                              <span style={{ fontWeight: 800, color: p.isEliminated ? '#ef4444' : '#fbbf24' }}>
                                {p.cumulativeScore}/{threshold}
                              </span>
                            )}
                          </div>
                        </>
                      ) : (
                        <>
                          <div
                            className={`result-row-delta ${
                              won ? 'result-row-delta--win' : 'result-row-delta--loss'
                            }`}
                          >
                            {won ? `+₹${Number(displayPrize).toFixed(2)}` : `-₹${Number(pLoss).toFixed(2)}`}
                          </div>

                          <div className="result-row-score-sub">
                            {isDeals ? (
                              <span style={{ color: '#fbbf24', fontWeight: 800 }}>
                                🪙 {p.chipBalance ?? 0} chips ({p.score} pts)
                              </span>
                            ) : (
                              <>
                                <span>{p.score} pts</span>
                                {isPool && (
                                  <span style={{ color: '#fbbf24', marginLeft: '6px' }}>
                                    (Total: {p.cumulativeScore})
                                  </span>
                                )}
                              </>
                            )}
                            {pRefund !== undefined && pRefund > 0 && !isPool && !isDeals && (
                              <span style={{ color: '#34d399', marginLeft: '6px' }}>
                                (+₹{Number(pRefund).toFixed(2)} refund)
                              </span>
                            )}
                          </div>
                        </>
                      )}
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

          {isPool && gameState?.canRejoin && (
            <button
              type="button"
              id="btn-bottom-rejoin"
              onClick={() => {
                soundEngine.play('click');
                socketClient.rejoinTable();
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                padding: '10px 20px',
                borderRadius: '12px',
                border: '1.5px solid #6ee7b7',
                background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
                color: '#fff',
                fontWeight: 900,
                fontSize: '13px',
                cursor: 'pointer',
                boxShadow: '0 0 18px rgba(16, 185, 129, 0.4)',
              }}
            >
              <Sparkles size={16} />
              Re-Join Table (₹{gameState.rejoinFee || lastGameConfig?.entryFee || 8})
            </button>
          )}

          {(isPool || isDeals) && onOpenScoreboard && (
            <button
              type="button"
              onClick={() => {
                soundEngine.play('click');
                onOpenScoreboard();
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
                padding: '10px 18px',
                borderRadius: '12px',
                border: '1px solid rgba(251, 191, 36, 0.4)',
                background: 'rgba(251, 191, 36, 0.15)',
                color: '#fef08a',
                fontWeight: 800,
                fontSize: '13px',
                cursor: 'pointer',
              }}
            >
              <Layers size={16} />
              View Full Scoreboard
            </button>
          )}

          {isIntermediateDeal ? (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                padding: '10px 20px',
                borderRadius: '12px',
                background: 'linear-gradient(135deg, #f59e0b, #d97706)',
                color: '#111',
                fontWeight: 900,
                fontSize: '13px',
                boxShadow: '0 0 15px rgba(245, 158, 11, 0.4)',
              }}
            >
              <RotateCcw size={16} style={{ animation: 'spin 3s linear infinite' }} />
              Next Deal Starting ({dealCountdown}s)
            </div>
          ) : (
            <button
              type="button"
              className="result-btn-rematch"
              onClick={handleRematch}
            >
              <RotateCcw size={17} />
              Rematch Same Stake
            </button>
          )}
        </footer>
      </main>
    </div>
  );
};
