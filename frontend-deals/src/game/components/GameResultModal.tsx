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
import { getApiBaseUrl } from '../utils/apiConfig';
import { authFetch } from '../utils/authClient';
import { SoundToggle } from './SoundToggle';
import { FullscreenToggle } from './FullscreenToggle';
import { CardView } from './CardView';
import type { CardInstance, GroupValidationType } from '../types/game';
import { evaluateCardGroup, getCardScore, isJoker } from '../rules/clientValidator';
import { photoForCharacter, getAvatarForPlayer } from '../utils/avatarUtils';
import { useModalScroll } from '../hooks/useModalScroll';

interface GameResultModalProps {
  isOpen: boolean;
  onOpenScoreboard?: () => void;
}

const splitButtonStyle = (color: string): React.CSSProperties => ({
  background: color,
  color: '#fff',
  border: 'none',
  borderRadius: '8px',
  padding: '7px 14px',
  fontWeight: 800,
  fontSize: '12px',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
});

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
 * Orders cards inside a showdown group sequentially for clear, professional visual display.
 */
function sortGroupCardsForDisplay(
  cards: CardInstance[],
  type: GroupValidationType,
  wildJoker: CardInstance | null
): CardInstance[] {
  if (!cards || cards.length <= 1) return cards;

  if (type === 'PURE_SEQUENCE') {
    // Pure sequence: all cards share the same suit. Determine if Ace is low (A-2-3) or high (Q-K-A).
    const hasAce = cards.some((c) => c.rank === 'ACE');
    const hasLow = cards.some((c) => c.rank === 'TWO' || c.rank === 'THREE');
    return [...cards].sort((a, b) => {
      const rankA = hasAce && hasLow && a.rank === 'ACE' ? 1 : (RANK_ORDER[a.rank] ?? 99);
      const rankB = hasAce && hasLow && b.rank === 'ACE' ? 1 : (RANK_ORDER[b.rank] ?? 99);
      return rankA - rankB;
    });
  }

  if (type === 'IMPURE_SEQUENCE') {
    const naturals: CardInstance[] = [];
    const jokers: CardInstance[] = [];
    for (const c of cards) {
      if (isJoker(c, wildJoker)) {
        jokers.push(c);
      } else {
        naturals.push(c);
      }
    }

    if (naturals.length === 0) return cards;

    const hasAce = naturals.some((c) => c.rank === 'ACE');
    const hasLow = naturals.some((c) => c.rank === 'TWO' || c.rank === 'THREE');
    const getVal = (c: CardInstance) => (hasAce && hasLow && c.rank === 'ACE' ? 1 : (RANK_ORDER[c.rank] ?? 99));

    naturals.sort((a, b) => getVal(a) - getVal(b));

    const minVal = getVal(naturals[0]);
    const maxVal = getVal(naturals[naturals.length - 1]);

    const result: CardInstance[] = [];
    const naturalMap = new Map<number, CardInstance>();
    for (const n of naturals) {
      naturalMap.set(getVal(n), n);
    }

    const jokerQueue = [...jokers];
    for (let r = minVal; r <= maxVal; r++) {
      if (naturalMap.has(r)) {
        result.push(naturalMap.get(r)!);
      } else if (jokerQueue.length > 0) {
        result.push(jokerQueue.shift()!);
      }
    }

    // Place remaining jokers at end
    while (jokerQueue.length > 0) {
      result.push(jokerQueue.shift()!);
    }

    return result;
  }

  if (type === 'SET') {
    // Set: Natural cards grouped together by suit, jokers at the end
    const naturals: CardInstance[] = [];
    const jokers: CardInstance[] = [];
    for (const c of cards) {
      if (isJoker(c, wildJoker)) {
        jokers.push(c);
      } else {
        naturals.push(c);
      }
    }
    naturals.sort((a, b) => (SUIT_ORDER[a.suit] ?? 99) - (SUIT_ORDER[b.suit] ?? 99));
    return [...naturals, ...jokers];
  }

  // INVALID: Sort by suit and rank for clean display
  return [...cards].sort((a, b) => {
    const s = (SUIT_ORDER[a.suit] ?? 99) - (SUIT_ORDER[b.suit] ?? 99);
    if (s !== 0) return s;
    return (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
  });
}

/**
 * Organizes revealed player cards into simple Rummy groups matching the game table style.
 */
function autoGroupShowdownCards(
  cards: CardInstance[],
  wildJoker: CardInstance | null,
  isWinner: boolean,
  declaredGroups?: { cards: CardInstance[] }[],
  isRummy21 = false
): ShowdownGroup[] {
  // 1. If winner declared groups, use their exact declaration
  if (
    isWinner &&
    declaredGroups &&
    declaredGroups.length >= 2 &&
    declaredGroups.every((g) => g.cards && g.cards.length >= 2)
  ) {
    return declaredGroups.map((g) => {
      const type = evaluateCardGroup(g.cards, wildJoker);
      return {
        type,
        cards: sortGroupCardsForDisplay(g.cards, type, wildJoker),
        pts: 0,
      };
    });
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
        result.push({ type, cards: sortGroupCardsForDisplay(chunk, type, wildJoker), pts });
      });
    } else {
      const type = evaluateCardGroup(groupCards, wildJoker);
      const pts = isWinner ? 0 : groupCards.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
      result.push({ type, cards: sortGroupCardsForDisplay(groupCards, type, wildJoker), pts });
    }
  }

  return applyRummyGroupPenalties(result, wildJoker, isWinner, isRummy21);
}

/**
 * Ensures valid sequences and sets are exempt (0 pts) according to official Indian Rummy rules.
 * Only unmelded/invalid cards show penalty points when sufficient sequences exist.
 */
function applyRummyGroupPenalties(
  groups: ShowdownGroup[],
  wildJoker: CardInstance | null,
  isWinner: boolean,
  isRummy21 = false
): ShowdownGroup[] {
  if (isWinner || !groups || groups.length === 0) {
    return groups.map((g) => ({ ...g, pts: 0 }));
  }

  let pureCount = 0;
  let totalSeqCount = 0;
  for (const g of groups) {
    if (g.type === 'PURE_SEQUENCE') {
      pureCount++;
      totalSeqCount++;
    } else if (g.type === 'IMPURE_SEQUENCE') {
      totalSeqCount++;
    }
  }

  // In 21-Card Rummy, at least 3 pure sequences or tunnelas are required before other valid melds become exempt.
  // In 13-Card Rummy, at least 1 pure sequence + at least 2 total sequences are required.
  const hasMeldExemption = isRummy21 ? pureCount >= 3 : (pureCount >= 1 && totalSeqCount >= 2);

  return groups.map((g) => {
    // 1. If player has met the required pure sequence requirement:
    // All valid sequences and sets are EXEMPT (0 pts); only INVALID groups count!
    if (hasMeldExemption) {
      if (g.type === 'PURE_SEQUENCE' || g.type === 'IMPURE_SEQUENCE' || g.type === 'SET') {
        return { ...g, pts: 0 };
      }
      const rawPts = g.cards.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
      return { ...g, pts: rawPts };
    }

    // 2. If player has at least 1 pure sequence:
    // Pure sequences are EXEMPT (0 pts); all other groups (impure, sets, invalid) count!
    if (pureCount >= 1) {
      if (g.type === 'PURE_SEQUENCE') {
        return { ...g, pts: 0 };
      }
      const rawPts = g.cards.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
      return { ...g, pts: rawPts };
    }

    // 3. If player has NO pure sequence:
    // ALL groups count their card points (excluding jokers = 0 pts)
    const rawPts = g.cards.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
    return { ...g, pts: rawPts };
  });
}

export const GameResultModal: React.FC<GameResultModalProps> = ({ isOpen, onOpenScoreboard }) => {
  const { containerRef, onWheel, onTouchStart, onTouchMove, onTouchEnd } = useModalScroll();
  const {
    gameState,
    gameSettlement,
    tableId,
    playerId,
    displayName,
    avatarId,
    groups: myVisualGroups,
    lastKnownHand,
    lastGameConfig,
    leaveTable,
    setSession,
    setWalletBalance,
    setAutoMatchmakePending,
    setLastGameConfig,
    clearSelection,
  } = useGameStore();

  const [expandedPlayerIds, setExpandedPlayerIds] = useState<Record<string, boolean>>({});
  const [showAllCards, setShowAllCards] = useState<boolean>(true);
  const [trnResult, setTrnResult] = useState<{
    tournamentId: string;
    title: string;
    status: string;
    fixedPrizeRs: number;
    roundNumber: number;
    totalRounds: number;
    isFinalTable?: boolean;
    finishedTables?: number;
    totalTablesInRound?: number;
    roundDone: boolean;
    qualified: boolean;
    nextTableId: string | null;
    finalRankings: Array<{ rank: number; playerId: string; displayName: string; prizeRs: number }>;
    myRank: number | null;
    myPrizeRs: number;
  } | null>(null);

  const isScheduledTournament = Boolean(tableId && tableId.startsWith('TBL_TRN_'));

  const isCompleted = gameState?.gameStatus === 'COMPLETED';
  const winnerId = gameState?.winnerId;
  const isWinner = winnerId === playerId;

  const activeRulesetId = (gameSettlement?.rulesetId ?? gameState?.rulesetId ?? lastGameConfig?.rulesetId ?? 'POINTS_13').toUpperCase();
  const isPool = Boolean(gameState?.eliminationThreshold || activeRulesetId.includes('POOL'));
  const isDeals = Boolean((gameState?.totalDeals ?? 0) > 1 || activeRulesetId.includes('DEAL'));
  const scheduledDeals = activeRulesetId.includes('3') ? 3 : 2;
  const totalDeals = gameState?.totalDeals ?? scheduledDeals;
  const isTieBreaker = isDeals && (totalDeals > scheduledDeals || (gameState?.dealNumber ?? 1) > scheduledDeals);
  const threshold = gameState?.eliminationThreshold || (activeRulesetId.includes('201') ? 201 : 101);
  const isMultiDealGame = isPool || isDeals;
  const isIntermediateDeal = isMultiDealGame && !gameState?.tournamentWinnerId;
  const isTournamentWinner = (isMultiDealGame || isScheduledTournament) && Boolean(gameState?.tournamentWinnerId);

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

  // Poll tournament status & prize payouts when playing a scheduled tournament table
  useEffect(() => {
    if (!isOpen || !isScheduledTournament || isIntermediateDeal || !tableId || !playerId) return;
    let cancelled = false;

    const pollTournament = async () => {
      try {
        const res = await authFetch(
          `${getApiBaseUrl()}/api/tournaments/by-table/${encodeURIComponent(tableId)}?playerId=${encodeURIComponent(playerId)}`
        );
        if (!res.ok) return;
        const data = await res.json();
        if (cancelled || !data.success) return;
        setTrnResult(data);

        // If player qualified and next round table is ready, auto-seat into next round
        if (data.nextTableId && data.nextTableId !== tableId) {
          soundEngine.play('deal');
          setSession(data.nextTableId, playerId, displayName || playerId);
          socketClient.joinTable(0);
          return;
        }

        // Refresh wallet balance once tournament completes & prizes are credited
        if (data.status === 'COMPLETED' && Number(data.myPrizeRs ?? 0) > 0) {
          const balRes = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${encodeURIComponent(playerId)}`);
          if (balRes.ok) {
            const balData = await balRes.json();
            setWalletBalance(Number(balData.balance ?? 0));
          }
        }
      } catch {
        // silent retry
      }
    };

    pollTournament();
    const id = setInterval(pollTournament, 2500);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [isOpen, isScheduledTournament, isIntermediateDeal, tableId, playerId, displayName, setSession, setWalletBalance]);

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
      const avPhoto = opp.avatarId ? photoForCharacter(opp.avatarId) : av.photo;
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
        avatarPhoto: avPhoto,
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
      return (b.chipBalance ?? 0) - (a.chipBalance ?? 0) || (a.cumulativeScore ?? 0) - (b.cumulativeScore ?? 0);
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

  const stakeTier = gameState?.stakeTier ?? gameSettlement?.stakeTier ?? lastGameConfig?.entryFee ?? 8;
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
    } else if (gameState.prizePool != null) {
      displayPrize = gameState.prizePool;
      displayGrossPot = Math.round((gameState.prizePool / 0.85) * 100) / 100;
      displayRake = displayGrossPot - displayPrize;
    } else {
      displayGrossPot = stakeTier * unrankedPlayers.length;
      displayRake = displayGrossPot * 0.15;
      displayPrize = displayGrossPot - displayRake;
    }
  }

  const split = isPool && isIntermediateDeal ? gameState.split ?? null : null;
  const splitPayouts = gameSettlement?.splitPayouts ?? null;
  const nameOf = (id: string) =>
    id === playerId ? 'You' : unrankedPlayers.find((p) => p.playerId === id)?.name ?? id;
  const rejoinFee = gameState.rejoinFee || stakeTier;

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
      rulesetId: gameState?.rulesetId ?? lastGameConfig?.rulesetId ?? 'POINTS_13',
      entryFee: stakeTier,
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
    <div
      ref={containerRef}
      className="result-page-screen"
      role="region"
      aria-label="Game Showdown Result Page"
      onWheel={onWheel}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
    >
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
              ? (isTieBreaker
                  ? `⚡ TIE-BREAKER DEAL ${gameState.dealNumber ?? 1} SHOWDOWN ⚡`
                  : `♠ DEAL ${gameState.dealNumber ?? 1} SHOWDOWN ♠`)
              : isTournamentWinner
              ? (isDeals ? (isTieBreaker ? '🏆 PLAYOFF CHAMPION 🏆' : '🏆 DEALS CHAMPION 🏆') : '🏆 POOL TOURNAMENT CHAMPION 🏆')
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
              {isScheduledTournament && isTournamentWinner && trnResult && !trnResult.isFinalTable ? (
                gameState.tournamentWinnerId === playerId || trnResult.qualified ? (
                  <>
                    <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                    Qualified for Next Round!
                  </>
                ) : trnResult.roundDone ? (
                  'Eliminated from Tournament'
                ) : (
                  `Round ${trnResult.roundNumber} Complete`
                )
              ) : isScheduledTournament && trnResult?.status === 'COMPLETED' ? (
                (trnResult.myPrizeRs ?? 0) > 0 ? (
                  <>
                    <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                    You Won {trnResult.title}!
                  </>
                ) : (
                  `${trnResult.title} — Tournament Ended`
                )
              ) : isTournamentWinner ? (
                gameState.tournamentWinnerId === playerId ? (
                  <>
                    <Sparkles size={22} color="#fbbf24" style={{ display: 'inline', verticalAlign: 'middle', marginRight: '6px' }} />
                    {isTieBreaker ? 'Playoff Champion!' : 'Tournament Champion!'}
                  </>
                ) : (
                  `🏆 ${winnerName} Won the ${isDeals ? (isTieBreaker ? 'Playoff' : 'Deals Match') : 'Pool Tournament'}!`
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
              {isScheduledTournament && isTournamentWinner && trnResult && !trnResult.isFinalTable
                ? (gameState.tournamentWinnerId === playerId || trnResult.qualified
                    ? `You won your table! Waiting for other tables to finish (${trnResult.finishedTables ?? 1}/${trnResult.totalTablesInRound ?? 1})…`
                    : trnResult.roundDone
                    ? 'You are not eligible for the next round. Better luck next time!'
                    : `Checking qualification across remaining tables (${trnResult.finishedTables ?? 1}/${trnResult.totalTablesInRound ?? 1})…`)
                : isScheduledTournament && trnResult?.status === 'COMPLETED'
                ? ((trnResult.myPrizeRs ?? 0) > 0
                    ? `🏆 Congratulations! You are the Tournament Champion — ₹${Number(trnResult.myPrizeRs).toLocaleString()} has been credited to your wallet!`
                    : `You finished Rank #${trnResult.myRank ?? '-'} in ${trnResult.title}. Better luck next time!`)
                : isTournamentWinner
                ? (gameState.tournamentWinnerId === playerId
                    ? (isDeals
                        ? (isTieBreaker
                            ? `Outstanding victory! You won the sudden-death tie-breaker playoff after ${totalDeals} deals!`
                            : `Congratulations! You had the most chips after ${totalDeals} deals and won the match!`)
                        : isPool
                        ? 'Congratulations! You are the last surviving player standing and won the pool pot!'
                        : 'Congratulations! You won your Point Rummy tournament table!')
                    : (isDeals || isPool
                        ? (isTieBreaker
                            ? `Playoff finished after ${gameState.dealNumber ?? 1} deals. Review scores below.`
                            : `Final standings after ${gameState.dealNumber ?? 1} deals. Review scores below.`)
                        : 'Point Rummy tournament hand completed. Review scores below.'))
                : isIntermediateDeal
                ? (isDeals
                    ? (isTieBreaker
                        ? `⚡ Sudden-death playoff deal ${gameState.dealNumber ?? 1} completed! Playoff chips transferred.`
                        : `Deal ${gameState.dealNumber ?? 1} of ${totalDeals} completed. Chips transferred to the deal winner!`)
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
                {split?.requestedBy ? (
                  <span>Split decision in <strong>{dealCountdown}s</strong>...</span>
                ) : (
                  <span>Next Deal starting in <strong>{dealCountdown}s</strong>...</span>
                )}
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
                        : 'Re-Join is not available now.'}
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
                    ✨ Re-Join (₹{rejoinFee})
                  </button>
                )}
              </div>
            )}

            {split && (
              <div
                style={{
                  marginTop: '10px',
                  padding: '12px 16px',
                  borderRadius: '12px',
                  background: 'rgba(59, 130, 246, 0.14)',
                  border: '1px solid rgba(96, 165, 250, 0.45)',
                  color: '#e0f2fe',
                  fontSize: '12.5px',
                  maxWidth: '620px',
                  width: '100%',
                  textAlign: 'left',
                }}
              >
                <div style={{ fontWeight: 800, marginBottom: '8px' }}>
                  {split.requestedBy
                    ? `${nameOf(split.requestedBy)} asked to split the prize`
                    : 'Split the prize and end the game?'}
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr auto auto', gap: '4px 14px', marginBottom: '8px' }}>
                  {Object.entries(split.payouts).map(([pid, amount]) => (
                    <React.Fragment key={pid}>
                      <span>{nameOf(pid)}</span>
                      <strong style={{ color: '#86efac' }}>₹{Number(amount).toFixed(2)}</strong>
                      <span style={{ color: '#cbd5e1' }}>
                        {split.requestedBy ? (split.acceptedBy.includes(pid) ? '✓ Accepted' : 'Waiting…') : ''}
                      </span>
                    </React.Fragment>
                  ))}
                </div>
                <div style={{ fontSize: '11px', color: '#94a3b8', marginBottom: '8px' }}>
                  Shares follow drops left: each extra drop is worth one entry fee (₹{stakeTier}), the rest is shared
                  equally. Everyone must accept.
                </div>
                <div style={{ display: 'flex', gap: '8px' }}>
                  {split.canRequest && (
                    <button
                      type="button"
                      onClick={() => {
                        soundEngine.play('click');
                        socketClient.requestSplit();
                      }}
                      style={splitButtonStyle('#2563eb')}
                    >
                      Request Split
                    </button>
                  )}
                  {split.awaitingMyAnswer && (
                    <>
                      <button
                        type="button"
                        onClick={() => {
                          soundEngine.play('click');
                          socketClient.respondSplit(true);
                        }}
                        style={splitButtonStyle('#059669')}
                      >
                        Accept
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          soundEngine.play('click');
                          socketClient.respondSplit(false);
                        }}
                        style={splitButtonStyle('#dc2626')}
                      >
                        Decline
                      </button>
                    </>
                  )}
                  {split.requestedBy && !split.awaitingMyAnswer && (
                    <span style={{ color: '#cbd5e1' }}>Waiting for the other players to answer…</span>
                  )}
                </div>
              </div>
            )}

            {splitPayouts && (
              <div
                style={{
                  marginTop: '10px',
                  padding: '12px 16px',
                  borderRadius: '12px',
                  background: 'rgba(16, 185, 129, 0.14)',
                  border: '1px solid rgba(52, 211, 153, 0.45)',
                  color: '#ecfdf5',
                  fontSize: '12.5px',
                  maxWidth: '620px',
                  width: '100%',
                  textAlign: 'left',
                }}
              >
                <div style={{ fontWeight: 800, marginBottom: '8px' }}>
                  {isDeals ? 'Still level on chips after the tie-breakers: prize shared' : 'Prize split agreed'}
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: '4px 14px' }}>
                  {Object.entries(splitPayouts).map(([pid, amount]) => (
                    <React.Fragment key={pid}>
                      <span>{nameOf(pid)}</span>
                      <strong style={{ color: '#86efac' }}>+₹{Number(amount).toFixed(2)}</strong>
                    </React.Fragment>
                  ))}
                </div>
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
                    <span className="result-pot-strip-val" style={{ color: isTieBreaker ? '#fbbf24' : '#60a5fa' }}>
                      {isTieBreaker
                        ? `Deal ${gameState.dealNumber ?? 1} (⚡ Sudden-Death)`
                        : `Deal ${gameState.dealNumber ?? 1} of ${totalDeals}`}
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
                      ₹{Number(displayPrize).toFixed(2)}
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
            ) : isScheduledTournament ? (
              trnResult?.status === 'COMPLETED' ? (
                <>
                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Fixed Prize Pool</span>
                    <span className="result-pot-strip-val" style={{ color: '#fbbf24' }}>
                      ₹{Number(trnResult.fixedPrizeRs ?? 0).toLocaleString()}
                    </span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Your Final Rank</span>
                    <span className="result-pot-strip-val">
                      {trnResult.myRank ? `#${trnResult.myRank}` : '—'}
                    </span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item val-winner">
                    <span className="result-pot-strip-label" style={{ color: (trnResult.myPrizeRs ?? 0) > 0 ? '#86efac' : '#94a3b8' }}>
                      {(trnResult.myPrizeRs ?? 0) > 0 ? 'Prize Credited' : 'Your Prize'}
                    </span>
                    <span className={`result-pot-strip-val ${(trnResult.myPrizeRs ?? 0) > 0 ? 'val-green' : ''}`}>
                      {(trnResult.myPrizeRs ?? 0) > 0
                        ? `+₹${Number(trnResult.myPrizeRs).toLocaleString()}`
                        : '₹0.00'}
                    </span>
                  </div>
                </>
              ) : (
                <>
                  <div className="result-pot-strip-item">
                    <span className="result-pot-strip-label">Tournament</span>
                    <span className="result-pot-strip-val" style={{ color: '#fbbf24' }}>
                      {trnResult?.title || 'Tournament'}
                    </span>
                  </div>

                  <div className="result-pot-strip-divider" />

                  <div className="result-pot-strip-item val-winner">
                    <span className="result-pot-strip-label" style={{ color: '#86efac' }}>Round Status</span>
                    <span className="result-pot-strip-val" style={{ fontSize: '13px', color: '#fde047' }}>
                      {trnResult?.roundDone
                        ? (trnResult.qualified ? '🎉 Qualified! Moving to Next Round…' : '❌ Eliminated')
                        : '⏳ Waiting for other tables to finish…'}
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
                  <span className="result-pot-strip-val" style={{ color: '#fbbf24' }}>₹{Number(displayRake).toFixed(2)}</span>
                </div>

                <div className="result-pot-strip-divider" />

                <div className="result-pot-strip-item val-winner">
                  <span className="result-pot-strip-label" style={{ color: '#86efac' }}>
                    {splitPayouts ? 'Prize (Split)' : isPool ? 'Pool Champion Prize' : isDeals ? 'Deals Champion Prize' : 'Net Winnings'}
                  </span>
                  <span className="result-pot-strip-val val-green">+₹{Number(displayPrize).toFixed(2)}</span>
                  {isPointsBased && (
                    <span style={{ fontSize: '10.5px', color: '#86efac', fontWeight: 700, marginTop: '1px' }}>
                      (Total Credit: ₹{(Number(stakeTier) + Number(displayPrize)).toFixed(2)})
                    </span>
                  )}
                  {(isPool || isDeals) && (
                    <span style={{ fontSize: '10.5px', color: '#86efac', fontWeight: 700, marginTop: '1px' }}>
                      (Total Credit: ₹{Number(displayPrize).toFixed(2)})
                    </span>
                  )}
                </div>
              </>
            )}
          </div>
        </section>

        {/* Final Tournament Rankings & Payouts */}
        {isScheduledTournament && trnResult?.status === 'COMPLETED' && trnResult.finalRankings.length > 0 && (
          <section className="result-multi-deal-card" aria-label="Tournament Final Payouts">
            <div className="result-multi-deal-header">
              <div className="result-multi-deal-title">
                <Trophy size={16} color="#fbbf24" />
                <span>FINAL TOURNAMENT PAYOUTS ({trnResult.title})</span>
              </div>
              <span className="result-multi-deal-chip-tag">
                Fixed Prize: ₹{Number(trnResult.fixedPrizeRs).toLocaleString()}
              </span>
            </div>
            <div className="result-multi-deal-scroll">
              <table className="result-multi-deal-table" style={{ width: '100%', tableLayout: 'auto' }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left', padding: '10px 16px', width: '70px' }}>RANK</th>
                    <th style={{ textAlign: 'left', padding: '10px 16px' }}>PLAYER</th>
                    <th style={{ textAlign: 'center', padding: '10px 16px' }}>STATUS</th>
                    <th style={{ textAlign: 'right', padding: '10px 16px', width: '150px' }}>PRIZE CREDITED</th>
                  </tr>
                </thead>
                <tbody>
                  {trnResult.finalRankings.map((fr) => {
                    const isChamp = fr.prizeRs > 0;
                    const isMe = fr.playerId === playerId;
                    return (
                      <tr
                        key={fr.rank}
                        className={isMe ? 'is-me' : ''}
                        style={isChamp ? { background: 'rgba(16, 185, 129, 0.12)' } : undefined}
                      >
                        <td style={{ padding: '11px 16px', fontWeight: 900, color: fr.rank === 1 ? '#fbbf24' : '#94a3b8' }}>
                          {fr.rank === 1 ? '🥇 #1' : `#${fr.rank}`}
                        </td>
                        <td style={{ padding: '11px 16px', fontWeight: 700, color: '#fff' }}>
                          {fr.displayName || fr.playerId} {isMe ? '(You)' : ''}
                        </td>
                        <td style={{ padding: '11px 16px', textAlign: 'center' }}>
                          {isChamp ? (
                            <span style={{ color: '#fbbf24', fontWeight: 900, fontSize: '11px', background: 'rgba(251, 191, 36, 0.16)', border: '1px solid rgba(251, 191, 36, 0.45)', borderRadius: '999px', padding: '3px 10px', whiteSpace: 'nowrap' }}>
                              👑 TOURNAMENT WINNER
                            </span>
                          ) : (
                            <span style={{ color: '#94a3b8', fontSize: '11.5px', fontWeight: 600 }}>
                              Runner-up
                            </span>
                          )}
                        </td>
                        <td style={{ padding: '11px 16px', textAlign: 'right', fontWeight: 900, fontSize: '14px', color: isChamp ? '#4ade80' : '#64748b' }}>
                          {isChamp ? `+₹${Number(fr.prizeRs).toLocaleString()}` : '₹0.00'}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {/* Multi-Deal Scoreboard Breakdown Table */}
        {isMultiDealGame && (
          <section className="result-multi-deal-card" aria-label="Tournament All Deals Scoreboard">
            <div className="result-multi-deal-header">
              <div className="result-multi-deal-title">
                <Trophy size={16} color="#fbbf24" />
                <span>
                  {isDeals
                    ? (isTieBreaker
                        ? 'DEALS SUDDEN-DEATH PLAYOFF SCORECARD'
                        : `${totalDeals} DEALS TOURNAMENT SCORECARD`)
                    : `POOL ${threshold} ROUNDS BREAKDOWN`}
                </span>
              </div>
              <span className="result-multi-deal-badge">
                {isDeals
                  ? (isTieBreaker
                      ? '⚡ Sudden-Death Tie-Breaker Active'
                      : `🪙 Starting Chips: ${totalDeals * 80}`)
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
                        {isDeals && idx + 1 > scheduledDeals ? (
                          <span style={{ color: '#fbbf24' }}>Playoff {idx + 1}</span>
                        ) : (
                          `Deal ${idx + 1}`
                        )}
                      </th>
                    ))}
                    <th style={{ textAlign: 'center', minWidth: '110px' }}>
                      {isDeals ? 'Chips Balance' : 'Total Points'}
                    </th>
                    {!isIntermediateDeal && !isScheduledTournament && (
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

                        {/* Net Payout (hidden for scheduled tournaments — prize is fixed winner-takes-all) */}
                        {!isIntermediateDeal && !isScheduledTournament && (
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
            <span style={{ textAlign: 'right' }}>{isScheduledTournament ? 'POINTS & CHIPS' : 'POINTS & NET PAYOUT'}</span>
          </div>

          <div className="result-sheet-rows-list">
            {allPlayers.map((p, pRankIdx) => {
              const won = p.isWinner;
              const expanded = isPlayerExpanded(p.playerId);

              // Group calculation matching the game table style
              let playerGroups: ShowdownGroup[] = [];
              const submittedMeld = gameState.submittedMelds?.[p.playerId];

              if (won) {
                playerGroups = autoGroupShowdownCards(
                  p.hand,
                  gameState.cutJoker,
                  true,
                  gameState.winningGroups,
                  isRummy21
                );
              } else if (submittedMeld && submittedMeld.length > 0) {
                const mapped = submittedMeld.map((g) => {
                  const groupType = evaluateCardGroup(g.cards, gameState.cutJoker);
                  return {
                    type: groupType,
                    cards: sortGroupCardsForDisplay(g.cards, groupType, gameState.cutJoker),
                    pts: 0,
                  };
                });
                playerGroups = applyRummyGroupPenalties(mapped, gameState.cutJoker, false, isRummy21);
              } else if (
                p.isMe &&
                myVisualGroups &&
                myVisualGroups.length > 0 &&
                myVisualGroups.some((g) => g.cards.length > 0)
              ) {
                const mapped = myVisualGroups.map((g) => ({
                  type: g.groupType,
                  cards: sortGroupCardsForDisplay(g.cards, g.groupType, gameState.cutJoker),
                  pts: 0,
                }));
                playerGroups = applyRummyGroupPenalties(mapped, gameState.cutJoker, false, isRummy21);
              } else {
                playerGroups = autoGroupShowdownCards(
                  p.hand,
                  gameState.cutJoker,
                  false,
                  undefined,
                  isRummy21
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
                  const penalty = p.score > 0
                    ? Math.min(maxPenaltyCap, p.score)
                    : (p.status === 'DROPPED' ? (isRummy21 ? 30 : 20) : maxPenaltyCap);
                  pLoss = Math.min(stakeTier, penalty * ptVal);
                  pRefund = Math.max(0, stakeTier - pLoss);
                } else {
                  pLoss = isIntermediateDeal ? 0 : stakeTier;
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
                              {isIntermediateDeal
                                ? 'Deal Winner (0 pts)'
                                : isPool
                                ? 'Pool Champion (0 pts)'
                                : isDeals
                                ? 'Deals Champion (0 pts)'
                                : 'Winner (0 pts)'}
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
                            {isIntermediateDeal ? (
                              isDeals ? (
                                won ? `+${p.score || 0} chips` : `-${p.score || 0} chips`
                              ) : (
                                won ? `0 pts (Deal Won)` : `+${p.score || 0} pts`
                              )
                            ) : (
                              splitPayouts?.[p.playerId] != null
                                ? `+₹${Number(splitPayouts[p.playerId]).toFixed(2)}`
                                : won ? `+₹${Number(displayPrize).toFixed(2)}` : `-₹${Number(pLoss).toFixed(2)}`
                            )}
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
                            {pRefund !== undefined && pRefund > 0 && !isPool && !isDeals && !isIntermediateDeal && (
                              <span style={{ color: '#34d399', marginLeft: '6px' }}>
                                (+₹{Number(pRefund).toFixed(2)} refund)
                              </span>
                            )}
                            {won && !isIntermediateDeal && (
                              <span style={{ color: '#34d399', marginLeft: '6px' }}>
                                (Total Credit: ₹{isPointsBased
                                  ? (Number(playerDetail?.initialStake ?? stakeTier) + Number(displayPrize)).toFixed(2)
                                  : Number(displayPrize).toFixed(2)})
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

          {tableId?.startsWith('TBL_PRV_') && !isIntermediateDeal && (
            <button
              type="button"
              className="result-btn-next"
              onClick={() => {
                try { sessionStorage.setItem('rummy_open_private_tab', '1'); } catch { /* ignore */ }
                handleLeave();
              }}
            >
              Rematch / Private Room
            </button>
          )}

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
              Re-Join Table (₹{rejoinFee})
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
          ) : !isScheduledTournament ? (
            <button
              type="button"
              className="result-btn-rematch"
              onClick={handleRematch}
            >
              <RotateCcw size={17} />
              Rematch Same Stake
            </button>
          ) : null}
        </footer>
      </main>
    </div>
  );
};
