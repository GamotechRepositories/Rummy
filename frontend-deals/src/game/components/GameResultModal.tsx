import React, { useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import confetti from 'canvas-confetti';
import {
  ArrowLeft,
  RotateCcw,
  LogOut,
  CheckCircle2,
  AlertCircle,
  Layers,
  Sparkles,
  Trophy,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
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

function formatAmount(value: number): string {
  const rounded = Math.round(value * 100) / 100;
  return rounded.toLocaleString('en-IN', {
    minimumFractionDigits: Number.isInteger(rounded) ? 0 : 2,
    maximumFractionDigits: 2,
  });
}

function shortTableRef(tableId?: string | null): string {
  if (!tableId) return 'T1';
  const clean = tableId.replace(/[^a-zA-Z0-9]/g, '');
  return clean.length > 8 ? clean.slice(-8).toUpperCase() : clean.toUpperCase();
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
  const gameState = useGameStore((s) => s.gameState);
  if (!isOpen || !gameState || gameState.gameStatus !== 'COMPLETED') return null;

  const modalKey = `${gameState.tableId ?? ''}-${gameState.gameId ?? ''}-${gameState.dealNumber ?? 1}`;
  return <GameResultView key={modalKey} isOpen={isOpen} onOpenScoreboard={onOpenScoreboard} />;
};

const GameResultView: React.FC<GameResultModalProps> = ({ isOpen, onOpenScoreboard }) => {
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
    setSession,
    setWalletBalance,
    setAutoMatchmakePending,
    setLastGameConfig,
    clearSelection,
  } = useGameStore();

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
  const isPrivateRoom = Boolean(tableId && tableId.startsWith('TBL_PRV_'));
  const privateRoomCode = isPrivateRoom && tableId ? tableId.slice('TBL_PRV_'.length) : '';

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
    void socketClient.leaveTableAndDisconnect();
  };

  const handleLeave = () => {
    soundEngine.play('click');
    void socketClient.leaveTableAndDisconnect();
  };

  const computePlayerSettlement = (p: (typeof allPlayers)[number]) => {
    const playerDetail = gameSettlement?.playerDetails?.[p.playerId];
    const isSettledWinner = playerDetail
      ? playerDetail.isWinner
      : (isTournamentWinner ? p.playerId === matchWinnerId : p.isWinner);
    let pLoss = playerDetail?.lossAmount;
    let pRefund = playerDetail?.refundAmount;

    if (pLoss === undefined) {
      if (isSettledWinner) {
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

    const netRs = playerDetail?.netWalletDelta != null
      ? Number(playerDetail.netWalletDelta)
      : splitPayouts?.[p.playerId] != null
        ? Number(splitPayouts[p.playerId]) - Number(stakeTier)
        : isSettledWinner
          ? (isPointsBased ? Number(displayPrize) : Number(displayPrize) - Number(stakeTier))
          : -Number(pLoss ?? 0);

    const totalCredit = splitPayouts?.[p.playerId] != null
      ? Number(splitPayouts[p.playerId])
      : isSettledWinner
        ? (isPointsBased
            ? Number(playerDetail?.initialStake ?? stakeTier) + Number(playerDetail?.winAmount ?? displayPrize)
            : Number(playerDetail?.winAmount ?? displayPrize))
        : Number(pRefund ?? 0);

    return { pLoss: Number(pLoss ?? 0), pRefund, netRs, totalCredit, isSettledWinner };
  };

  const viewerPlayer = allPlayers.find((p) => p.isMe) ?? allPlayers[0];
  const viewerSettlement = viewerPlayer ? computePlayerSettlement(viewerPlayer) : null;
  const isFreePrivateRoom = isPrivateRoom && stakeTier === 0 && displayGrossPot === 0;

  const dealNum = gameState.dealNumber ?? 1;
  const ribbonTitle = isScheduledTournament
    ? 'TOURNAMENT RESULT'
    : isPrivateRoom
      ? 'PRIVATE ROOM RESULT'
      : isIntermediateDeal
        ? (isTieBreaker ? `PLAYOFF DEAL ${dealNum} RESULT` : `DEAL ${dealNum} RESULT`)
        : isTournamentWinner
          ? (isDeals ? (isTieBreaker ? 'PLAYOFF CHAMPION' : 'DEALS CHAMPION') : 'POOL CHAMPION')
          : 'GAME RESULT';

  const ribbonSubtitle = isScheduledTournament
    ? `${(trnResult?.title || variantDisplayName).toUpperCase()} · ${trnResult?.status === 'COMPLETED' ? 'FINAL STANDINGS' : `ROUND ${trnResult?.roundNumber ?? 1}`}`
    : isPrivateRoom
      ? `ROOM #${privateRoomCode || 'PRIVATE'} · ${variantDisplayName.toUpperCase()} · ${allPlayers.length} PLAYERS`
      : `${variantDisplayName.toUpperCase()} · ${allPlayers.length} PLAYERS`;

  const heroPositive = Boolean(
    (isScheduledTournament && trnResult && !trnResult.isFinalTable && trnResult.status !== 'COMPLETED' && (gameState.tournamentWinnerId === playerId || trnResult.qualified)) ||
    (isScheduledTournament && trnResult?.status === 'COMPLETED' && (trnResult.myPrizeRs ?? 0) > 0) ||
    (isTournamentWinner && !isScheduledTournament && gameState.tournamentWinnerId === playerId) ||
    (!isTournamentWinner && isWinner)
  );

  const trnFinalHeroTitle = isDeals
    ? (gameState.tournamentWinnerId === playerId
      ? (isTieBreaker ? 'Playoff Champion!' : 'Tournament Champion!')
      : `${winnerName} Won the ${isTieBreaker ? 'Playoff' : 'Deals Match'}`)
    : isPool
      ? (gameState.tournamentWinnerId === playerId ? 'Tournament Champion!' : `${winnerName} Won the Pool Tournament`)
      : isScheduledTournament
        ? (gameState.tournamentWinnerId === playerId ? 'Table Winner!' : `${winnerName} Won the Table`)
        : (isWinner ? 'You Won the Hand!' : `${winnerName} Won the Hand`);

  const trnFinalHeroSub = gameState.tournamentWinnerId === playerId
    ? (isDeals
      ? (isTieBreaker
        ? `Outstanding victory! You won the sudden-death tie-breaker playoff after ${totalDeals} deals!`
        : `Congratulations! You had the most chips after ${totalDeals} deals and won the match!`)
      : isPool
        ? 'Congratulations! You are the last surviving player standing and won the pool pot!'
        : isPoints13
          ? 'Congratulations! You won your Point Rummy tournament table!'
          : 'Congratulations! You won your tournament table!')
    : (isDeals || isPool
      ? (isTieBreaker
        ? `Playoff finished after ${dealNum} deals. Review scores below.`
        : `Final standings after ${dealNum} deals. Review scores below.`)
      : isPoints13
        ? 'Point Rummy tournament hand completed. Review scores below.'
        : 'Tournament hand completed. Review scores below.');

  const heroTitle = isScheduledTournament && isTournamentWinner && trnResult && !trnResult.isFinalTable && trnResult.status !== 'COMPLETED'
    ? (gameState.tournamentWinnerId === playerId || trnResult.qualified
      ? 'Qualified for Next Round!'
      : trnResult.roundDone
        ? 'Eliminated from Tournament'
        : `Round ${trnResult.roundNumber} Complete`)
    : isScheduledTournament && trnResult?.status === 'COMPLETED'
      ? ((trnResult.myPrizeRs ?? 0) > 0 ? `You Won ${trnResult.title}!` : `${trnResult.title} — Tournament Ended`)
      : isTournamentWinner
        ? trnFinalHeroTitle
        : isIntermediateDeal
          ? (isWinner ? `You Won Deal ${dealNum}!` : `${winnerName} Won Deal ${dealNum}`)
          : (isWinner ? 'You Won the Hand!' : `${winnerName} Won the Hand`);

  const heroSubtitle = isScheduledTournament && isTournamentWinner && trnResult && !trnResult.isFinalTable && trnResult.status !== 'COMPLETED'
    ? (gameState.tournamentWinnerId === playerId || trnResult.qualified
      ? `You won your table! Waiting for other tables to finish (${trnResult.finishedTables ?? 1}/${trnResult.totalTablesInRound ?? 1})…`
      : trnResult.roundDone
        ? 'You are not eligible for the next round. Better luck next time!'
        : `Checking qualification across remaining tables (${trnResult.finishedTables ?? 1}/${trnResult.totalTablesInRound ?? 1})…`)
    : isScheduledTournament && trnResult?.status === 'COMPLETED'
      ? ((trnResult.myPrizeRs ?? 0) > 0
        ? `Congratulations! You are the Tournament Champion — ₹ ${formatAmount(Number(trnResult.myPrizeRs))} has been credited to your wallet!`
        : `You finished Rank #${trnResult.myRank ?? '-'} in ${trnResult.title}. Better luck next time!`)
      : isTournamentWinner
        ? trnFinalHeroSub
        : isIntermediateDeal
          ? (isDeals
            ? (isTieBreaker
              ? `Sudden-death playoff deal ${dealNum} completed! Playoff chips transferred.`
              : `Deal ${dealNum} of ${totalDeals} completed. Chips transferred to the deal winner!`)
            : `Deal completed. Penalties added to cumulative scores. Reach ${threshold} to get eliminated.`)
          : isWinner
            ? 'Valid declaration with 0 penalty points. All players’ cards and settlements are shown below.'
            : 'Hand completed. Review all players’ showdown cards and final settlement below.';

  const useThreeColumnStrip = isIntermediateDeal && !isPrivateRoom;
  const viewerNetAmount = viewerSettlement?.netRs ?? 0;
  const viewerNetFormatted = isFreePrivateRoom
    ? 'Free'
    : viewerNetAmount > 0
      ? `+₹ ${formatAmount(viewerNetAmount)}`
      : viewerNetAmount < 0
        ? `-₹ ${formatAmount(Math.abs(viewerNetAmount))}`
        : '₹ 0';
  const viewerNetToneClass = !isFreePrivateRoom && viewerNetAmount > 0
    ? 'his-tx-amount--credit'
    : !isFreePrivateRoom && viewerNetAmount < 0
      ? 'his-tx-amount--debit'
      : '';

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
      <div className="res-panel">
        {/* Top Row — matching HistoryPage .his-topbar-row */}
        <header className="his-topbar-row res-topbar-row">
          <button
            type="button"
            className="stake-back"
            onClick={handleLeave}
            title="Leave table and return to lobby"
          >
            <span className="stake-back-icon" aria-hidden="true">
              <ArrowLeft size={14} strokeWidth={2.75} />
            </span>
            Leave Table
          </button>

          <div className="res-topbar-right">
            <SoundToggle compact />
            <FullscreenToggle compact />
            <span className="his-refresh-btn res-table-pill" title={`Table ${gameState.tableId ?? 'T1'}`}>
              {isPrivateRoom && privateRoomCode
                ? `Room #${privateRoomCode}`
                : isScheduledTournament
                  ? `Tournament · R${trnResult?.roundNumber ?? 1}`
                  : `Table #${shortTableRef(gameState.tableId)}`}
            </span>
          </div>
        </header>

        {/* Main Flat Royal Rummy Ledger Card — matching .stake-card.his-card */}
        <main className="stake-card his-card res-card">
          <span className="stake-suit stake-suit-tl" aria-hidden="true">♠</span>
          <span className="stake-suit stake-suit-tr" aria-hidden="true">♦</span>
          <span className="stake-suit stake-suit-bl" aria-hidden="true">♣</span>
          <span className="stake-suit stake-suit-br" aria-hidden="true">♥</span>

          <div className="stake-emblem res-emblem">
            <div className="stake-ribbon">
              <h2>{ribbonTitle}</h2>
              <p>{ribbonSubtitle}</p>
            </div>
          </div>

          {/* Outcome Headline & Contextual Banners */}
          <section className="res-hero">
            <h1 className={`res-hero-title${heroPositive ? ' res-hero-title--win' : ''}`}>
              {heroPositive && <Sparkles size={20} color="#f6dc8a" aria-hidden="true" />}
              <span>{heroTitle}</span>
            </h1>
            <p className="res-hero-subtitle">{heroSubtitle}</p>

            {isIntermediateDeal && (
              <div className="res-countdown-pill">
                <RotateCcw size={13} className="his-spin" />
                {split?.requestedBy ? (
                  <span>Split decision in <strong>{dealCountdown}s</strong>...</span>
                ) : (
                  <span>Next Deal starting in <strong>{dealCountdown}s</strong>...</span>
                )}
              </div>
            )}
          </section>

          {isPool && viewerEliminated && (
            <div className="res-notice res-notice--row res-notice--danger">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <AlertCircle size={16} color={gameState?.canRejoin ? '#6ee7b7' : '#fda4af'} />
                <div>
                  <div className="res-notice-head">Eliminated ({viewerCum}/{threshold} pts)</div>
                  <div className="res-notice-sub">
                    {gameState?.canRejoin
                      ? `Eligible to Re-Join at ${gameState.rejoinScore} pts!`
                      : 'Re-Join is not available now.'}
                  </div>
                </div>
              </div>

              {gameState?.canRejoin && (
                <button
                  type="button"
                  className="res-inline-btn res-inline-btn--accept"
                  onClick={() => {
                    soundEngine.play('click');
                    socketClient.rejoinTable();
                  }}
                >
                  Re-Join (₹{rejoinFee})
                </button>
              )}
            </div>
          )}

          {split && (
            <div className="res-notice res-notice--info">
              <div className="res-notice-head">
                {split.requestedBy
                  ? `${nameOf(split.requestedBy)} asked to split the prize`
                  : 'Split the prize and end the game?'}
              </div>
              <div className="res-split-grid">
                {Object.entries(split.payouts).map(([pid, amount]) => (
                  <React.Fragment key={pid}>
                    <span>{nameOf(pid)}</span>
                    <strong className="his-tx-amount--credit">₹ {formatAmount(Number(amount))}</strong>
                    <span className="res-notice-sub">
                      {split.requestedBy ? (split.acceptedBy.includes(pid) ? '✓ Accepted' : 'Waiting…') : ''}
                    </span>
                  </React.Fragment>
                ))}
              </div>
              <div className="res-notice-sub">
                Shares follow drops left: each extra drop is worth one entry fee (₹{stakeTier}), the rest is shared equally. Everyone must accept.
              </div>
              <div className="res-notice-actions">
                {split.canRequest && (
                  <button
                    type="button"
                    className="res-inline-btn"
                    onClick={() => {
                      soundEngine.play('click');
                      socketClient.requestSplit();
                    }}
                  >
                    Request Split
                  </button>
                )}
                {split.awaitingMyAnswer && (
                  <>
                    <button
                      type="button"
                      className="res-inline-btn res-inline-btn--accept"
                      onClick={() => {
                        soundEngine.play('click');
                        socketClient.respondSplit(true);
                      }}
                    >
                      Accept
                    </button>
                    <button
                      type="button"
                      className="res-inline-btn res-inline-btn--decline"
                      onClick={() => {
                        soundEngine.play('click');
                        socketClient.respondSplit(false);
                      }}
                    >
                      Decline
                    </button>
                  </>
                )}
                {split.requestedBy && !split.awaitingMyAnswer && (
                  <span className="res-notice-sub">Waiting for the other players to answer…</span>
                )}
              </div>
            </div>
          )}

          {splitPayouts && (
            <div className="res-notice res-notice--success">
              <div className="res-notice-head">
                {isDeals ? 'Still level on chips after the tie-breakers: prize shared' : 'Prize split agreed'}
              </div>
              <div className="res-split-grid res-split-grid--done">
                {Object.entries(splitPayouts).map(([pid, amount]) => (
                  <React.Fragment key={pid}>
                    <span>{nameOf(pid)}</span>
                    <strong className="his-tx-amount--credit">+₹ {formatAmount(Number(amount))}</strong>
                  </React.Fragment>
                ))}
              </div>
            </div>
          )}

          {/* Flat 3- or 4-Column Summary Strip (Exact HistoryPage .his-summary-strip layout) */}
          <div className={`his-summary-strip res-summary-strip${useThreeColumnStrip ? ' res-summary-strip--three' : ''}`}>
            {isIntermediateDeal ? (
              isDeals ? (
                <>
                  {isPrivateRoom && (
                    <div className="his-stat">
                      <span className="his-stat-label">Room Code</span>
                      <strong className="his-stat-value his-stat-value--gold">#{privateRoomCode || 'PRV'}</strong>
                    </div>
                  )}
                  <div className="his-stat">
                    <span className="his-stat-label">{isPrivateRoom ? 'Prize Pot' : 'Tournament Prize'}</span>
                    <strong className="his-stat-value his-stat-value--gold">₹ {formatAmount(Number(displayPrize))}</strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Current Deal</span>
                    <strong className="his-stat-value">
                      {isTieBreaker ? `Deal ${dealNum} (Playoff)` : `Deal ${dealNum} of ${totalDeals}`}
                    </strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Chip Leader</span>
                    <strong className="his-stat-value his-tx-amount--credit">
                      {allPlayers[0]?.name ?? 'Leader'} ({allPlayers[0]?.chipBalance ?? 0})
                    </strong>
                  </div>
                </>
              ) : (
                <>
                  {isPrivateRoom && (
                    <div className="his-stat">
                      <span className="his-stat-label">Room Code</span>
                      <strong className="his-stat-value his-stat-value--gold">#{privateRoomCode || 'PRV'}</strong>
                    </div>
                  )}
                  <div className="his-stat">
                    <span className="his-stat-label">Pool Prize Pot</span>
                    <strong className="his-stat-value his-stat-value--gold">₹ {formatAmount(Number(displayPrize))}</strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Elimination Limit</span>
                    <strong className="his-stat-value his-tx-amount--debit">{threshold} pts</strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Survivors</span>
                    <strong className="his-stat-value his-tx-amount--credit">
                      {allPlayers.filter((p) => !p.isEliminated).length} / {allPlayers.length}
                    </strong>
                  </div>
                </>
              )
            ) : isScheduledTournament ? (
              trnResult?.status === 'COMPLETED' ? (
                <>
                  <div className="his-stat">
                    <span className="his-stat-label">Fixed Prize Pool</span>
                    <strong className="his-stat-value his-stat-value--gold">
                      ₹ {formatAmount(Number(trnResult.fixedPrizeRs ?? 0))}
                    </strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Your Final Rank</span>
                    <strong className="his-stat-value">{trnResult.myRank ? `#${trnResult.myRank}` : '—'}</strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">{(trnResult.myPrizeRs ?? 0) > 0 ? 'Prize Credited' : 'Your Prize'}</span>
                    <strong className={`his-stat-value ${(trnResult.myPrizeRs ?? 0) > 0 ? 'his-tx-amount--credit' : ''}`}>
                      {(trnResult.myPrizeRs ?? 0) > 0
                        ? `+₹ ${formatAmount(Number(trnResult.myPrizeRs))}`
                        : '₹ 0'}
                    </strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Result</span>
                    <strong className={`his-stat-value ${(trnResult.myPrizeRs ?? 0) > 0 ? 'his-tx-amount--credit' : 'his-tx-amount--debit'}`}>
                      {(trnResult.myPrizeRs ?? 0) > 0 ? 'Champion' : 'Completed'}
                    </strong>
                  </div>
                </>
              ) : (
                <>
                  <div className="his-stat">
                    <span className="his-stat-label">Tournament</span>
                    <strong className="his-stat-value his-stat-value--gold">{trnResult?.title || 'Tournament'}</strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Round</span>
                    <strong className="his-stat-value">
                      {trnResult ? `Round ${trnResult.roundNumber}${trnResult.totalRounds > 1 ? ` / ${trnResult.totalRounds}` : ''}` : 'Round 1'}
                    </strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Tables Done</span>
                    <strong className="his-stat-value">
                      {trnResult ? `${trnResult.finishedTables ?? 1} / ${trnResult.totalTablesInRound ?? 1}` : '1 / 1'}
                    </strong>
                  </div>
                  <div className="his-stat">
                    <span className="his-stat-label">Round Status</span>
                    <strong
                      className={`his-stat-value ${trnResult?.roundDone
                        ? (trnResult.qualified ? 'his-tx-amount--credit' : 'his-tx-amount--debit')
                        : 'his-stat-value--gold'
                        }`}
                    >
                      {trnResult?.roundDone
                        ? (trnResult.qualified ? 'Qualified' : 'Eliminated')
                        : 'Checking…'}
                    </strong>
                  </div>
                </>
              )
            ) : isFreePrivateRoom ? (
              <>
                <div className="his-stat">
                  <span className="his-stat-label">Room Code</span>
                  <strong className="his-stat-value his-stat-value--gold">#{privateRoomCode || 'PRV'}</strong>
                </div>
                <div className="his-stat">
                  <span className="his-stat-label">Room Mode</span>
                  <strong className="his-stat-value">Free Practice</strong>
                </div>
                <div className="his-stat">
                  <span className="his-stat-label">Winner</span>
                  <strong className="his-stat-value his-tx-amount--credit">{winnerName}</strong>
                </div>
                <div className="his-stat">
                  <span className="his-stat-label">Your Result</span>
                  <strong className={`his-stat-value ${isWinner ? 'his-tx-amount--credit' : 'his-tx-amount--debit'}`}>
                    {isWinner ? 'Won' : myStatus === 'DROPPED' ? 'Dropped' : 'Lost'}
                  </strong>
                </div>
              </>
            ) : (
              <>
                <div className="his-stat">
                  <span className="his-stat-label">Gross Pot</span>
                  <strong className="his-stat-value">₹ {formatAmount(Number(displayGrossPot))}</strong>
                </div>
                <div className="his-stat">
                  <span className="his-stat-label">Fee (15%)</span>
                  <strong className="his-stat-value his-stat-value--gold">₹ {formatAmount(Number(displayRake))}</strong>
                </div>
                <div className="his-stat">
                  <span className="his-stat-label">
                    {splitPayouts ? 'Prize (Split)' : isPool ? 'Pool Prize' : isDeals ? 'Deals Prize' : 'Winner Payout'}
                  </span>
                  <strong className="his-stat-value his-tx-amount--credit">+₹ {formatAmount(Number(displayPrize))}</strong>
                  {(isPointsBased || isPool || isDeals) && (
                    <span className="res-stat-sub">
                      Total Credit: ₹ {formatAmount(isPointsBased ? Number(stakeTier) + Number(displayPrize) : Number(displayPrize))}
                    </span>
                  )}
                </div>
                <div className="his-stat">
                  <span className="his-stat-label">Your Net</span>
                  <strong className={`his-stat-value ${viewerNetToneClass}`}>{viewerNetFormatted}</strong>
                </div>
              </>
            )}
          </div>

          {/* Final Tournament Standings & Payouts — Flat History Row List */}
          {isScheduledTournament && trnResult?.status === 'COMPLETED' && trnResult.finalRankings.length > 0 && (
            <section className="res-section" aria-label="Tournament Final Payouts">
              <div className="res-section-head">
                <div className="res-section-title">
                  <Trophy size={14} />
                  <span>Final Tournament Payouts ({trnResult.title})</span>
                </div>
                <span className="his-row-tag">
                  Fixed Prize: ₹ {formatAmount(Number(trnResult.fixedPrizeRs ?? 0))}
                </span>
              </div>

              <div className="his-list">
                {trnResult.finalRankings.map((fr) => {
                  const isChamp = fr.prizeRs > 0;
                  const isMe = fr.playerId === playerId;
                  return (
                    <div
                      key={fr.rank}
                      className={`his-row${isChamp ? ' res-row--winner' : ''}`}
                    >
                      <div className="res-row-player">
                        <span className={`res-rank-pill${fr.rank === 1 || isChamp ? ' res-rank-pill--winner' : ''}`}>
                          #{fr.rank}
                        </span>
                        <div className="his-row-main">
                          <div className="his-row-title-line">
                            <span className="his-row-variant">{fr.displayName || fr.playerId}</span>
                            {isMe && <span className="his-row-tag">YOU</span>}
                          </div>
                          <div className="his-row-date">Rank #{fr.rank}</div>
                        </div>
                      </div>

                      <div className="his-row-meta">
                        <span className={isChamp ? 'his-tx-amount his-tx-amount--credit' : 'his-row-score'}>
                          {isChamp ? `+₹ ${formatAmount(Number(fr.prizeRs))}` : '₹ 0'}
                        </span>
                        <span className={`his-badge ${isChamp ? 'his-badge--won' : 'his-badge--neutral'}`}>
                          {isChamp ? 'Tournament Winner' : 'Runner-up'}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* Multi-Deal Scoreboard Breakdown */}
          {isMultiDealGame && (
            <section className="res-section" aria-label="Tournament All Deals Scoreboard">
              <div className="res-section-head">
                <div className="res-section-title">
                  <Trophy size={14} />
                  <span>
                    {isDeals
                      ? (isTieBreaker ? 'Deals Sudden-Death Playoff Scorecard' : `${totalDeals} Deals Scorecard`)
                      : `Pool ${threshold} Rounds Breakdown`}
                  </span>
                </div>
                <span className="his-row-tag">
                  {isDeals
                    ? (isTieBreaker ? 'Sudden-Death Tie-Breaker' : `Starting Chips: ${totalDeals * 80}`)
                    : `Elimination Limit: ${threshold} pts`}
                </span>
              </div>

              <div className="res-ledger-scroll">
                <table className="royal-scoreboard-table res-ledger-table">
                  <thead>
                    <tr>
                      <th style={{ textAlign: 'left', minWidth: '140px' }}>Player</th>
                      {Array.from({ length: maxRecordedDeals }).map((_, idx) => (
                        <th key={idx} style={{ textAlign: 'center', minWidth: '68px' }}>
                          {isDeals && idx + 1 > scheduledDeals ? (
                            <span className="res-sub-gold">Playoff {idx + 1}</span>
                          ) : (
                            `Deal ${idx + 1}`
                          )}
                        </th>
                      ))}
                      <th style={{ textAlign: 'center', minWidth: '105px' }}>
                        {isDeals ? 'Chips Balance' : 'Total Points'}
                      </th>
                      {!isIntermediateDeal && !isScheduledTournament && (
                        <th style={{ textAlign: 'center', minWidth: '100px' }}>Net Payout</th>
                      )}
                      <th style={{ textAlign: 'right', minWidth: '90px' }}>Rank &amp; Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {allPlayers.map((p, rankIdx) => {
                      const isTournamentChamp =
                        Boolean(gameState.tournamentWinnerId && gameState.tournamentWinnerId === p.playerId) ||
                        (!gameState.tournamentWinnerId && p.isWinner);
                      const isDanger = isPool && !p.isEliminated && (p.cumulativeScore ?? 0) >= threshold * 0.75;
                      const percent = isPool ? Math.min(100, Math.round(((p.cumulativeScore ?? 0) / threshold) * 100)) : 0;

                      let scoreColor = '#6ee7b7';
                      if (p.isEliminated || isDanger) scoreColor = '#fda4af';
                      else if ((p.cumulativeScore ?? 0) >= threshold * 0.5) scoreColor = '#f6dc8a';

                      const rowClass = `${p.isMe ? 'royal-scoreboard-row--viewer' : ''} ${p.isEliminated ? 'royal-scoreboard-row--eliminated' : ''}`;

                      return (
                        <tr key={p.playerId} className={rowClass}>
                          <td>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                              <div
                                style={{
                                  width: '26px',
                                  height: '26px',
                                  borderRadius: '50%',
                                  overflow: 'hidden',
                                  border: `1.5px solid ${isTournamentChamp ? '#f6dc8a' : 'rgba(212, 175, 55, 0.25)'}`,
                                  background: '#1a0a0c',
                                  display: 'flex',
                                  alignItems: 'center',
                                  justifyContent: 'center',
                                  flexShrink: 0,
                                }}
                              >
                                {p.avatarPhoto ? (
                                  <img src={p.avatarPhoto} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                                ) : (
                                  <span style={{ fontSize: '11px', color: '#f6dc8a' }}>#</span>
                                )}
                              </div>

                              <div style={{ textAlign: 'left' }}>
                                <div className="his-row-variant" style={{ color: p.isMe ? '#f6dc8a' : '#fff8ea', fontSize: '12.5px' }}>
                                  {p.name}
                                  {p.isMe && <span className="his-row-tag" style={{ marginLeft: '6px' }}>YOU</span>}
                                </div>
                                <div className="his-row-date">
                                  {isTournamentChamp && <span className="res-sub-gold" style={{ marginRight: '6px' }}>Champion</span>}
                                  {!isDeals && isDanger && <span className="his-tx-amount--debit" style={{ marginRight: '6px' }}>Danger</span>}
                                  {p.isBot && <span>AI</span>}
                                </div>
                              </div>
                            </div>
                          </td>

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
                                  <span style={{ color: 'rgba(231, 222, 203, 0.45)' }}>—</span>
                                )}
                              </td>
                            );
                          })}

                          <td style={{ textAlign: 'center' }}>
                            {isDeals ? (
                              <span className="res-sub-gold" style={{ fontSize: '13px', fontWeight: 800 }}>
                                {p.chipBalance ?? 0} chips
                              </span>
                            ) : (
                              <>
                                <div style={{ fontWeight: 800, fontSize: '12.5px', color: scoreColor }}>
                                  {p.cumulativeScore ?? 0}{' '}
                                  <span style={{ fontSize: '10.5px', color: 'rgba(231, 222, 203, 0.55)' }}>/ {threshold}</span>
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

                          {!isIntermediateDeal && !isScheduledTournament && (() => {
                            const tblSettlement = computePlayerSettlement(p);
                            const netVal = tblSettlement.netRs;
                            return (
                              <td style={{ textAlign: 'center' }}>
                                {isFreePrivateRoom ? (
                                  <span className="his-row-score">Free</span>
                                ) : netVal > 0 ? (
                                  <span className="his-tx-amount his-tx-amount--credit">+₹ {formatAmount(netVal)}</span>
                                ) : netVal < 0 ? (
                                  <span className="his-tx-amount his-tx-amount--debit">-₹ {formatAmount(Math.abs(netVal))}</span>
                                ) : (
                                  <span className="his-row-score">₹ 0</span>
                                )}
                              </td>
                            );
                          })()}

                          <td style={{ textAlign: 'right' }}>
                            {isTournamentChamp ? (
                              <span className="his-badge his-badge--won">{isDeals ? 'Champion' : 'Winner'}</span>
                            ) : p.isEliminated ? (
                              <span className="his-badge his-badge--lost">Eliminated</span>
                            ) : (
                              <span className="his-row-date">Rank #{rankIdx + 1}</span>
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

          {/* Showdown Scoreboard — Flat History Ledger Rows + Showdown Cards */}
          <section className="res-section" aria-label="Showdown Scoreboard">
            <div className="res-section-head">
              <div className="res-section-title">
                <Layers size={14} />
                <span>{isMultiDealGame ? `Deal ${dealNum} Showdown Cards` : 'Showdown Scoreboard'}</span>
              </div>
            </div>

            <div className="res-table-subhead">
              <span>Player &amp; Status</span>
              <span>{isScheduledTournament ? 'Points & Chips' : 'Points & Net Payout'}</span>
            </div>

            <div className="his-list res-showdown-list">
              {allPlayers.map((p, pRankIdx) => {
                const won = p.isWinner;

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

                const { pLoss, pRefund, netRs, totalCredit, isSettledWinner } = computePlayerSettlement(p);

                const rowNetText = isScheduledTournament
                  ? (isDeals ? (won ? 'Won Table' : `-${p.score} chips`) : won ? '0 pts' : `+${p.score} pts`)
                  : isFreePrivateRoom
                    ? (won ? 'Won' : 'Free')
                    : netRs > 0
                      ? `+₹ ${formatAmount(netRs)}`
                      : netRs < 0
                        ? `-₹ ${formatAmount(Math.abs(netRs))}`
                        : pLoss > 0
                          ? `-₹ ${formatAmount(pLoss)}`
                          : '₹ 0';

                const rowNetClass = isScheduledTournament
                  ? (won ? 'his-tx-amount his-tx-amount--credit' : 'his-tx-amount his-tx-amount--debit')
                  : isFreePrivateRoom || netRs === 0
                    ? (won && isFreePrivateRoom ? 'his-badge his-badge--won' : 'his-row-score')
                    : netRs > 0
                      ? 'his-tx-amount his-tx-amount--credit'
                      : 'his-tx-amount his-tx-amount--debit';

                return (
                  <div
                    key={p.playerId}
                    className={`his-row res-row${won ? ' res-row--winner' : ''}`}
                  >
                    <div className="res-row-top">
                      <div className="res-row-player">
                        <span className={`res-rank-pill${won ? ' res-rank-pill--winner' : ''}`}>
                          #{pRankIdx + 1}
                        </span>

                        <div className="his-row-main">
                          <div className="his-row-title-line">
                            <span className="his-row-variant">{p.name}</span>
                            {p.isMe && <span className="his-row-tag">YOU</span>}
                          </div>

                          <div
                            className={`his-badge res-status-inline ${won
                              ? 'his-badge--won'
                              : p.status === 'DROPPED' && !p.isEliminated
                                ? 'his-badge--neutral'
                                : 'his-badge--lost'
                              }`}
                          >
                            {won ? (
                              <>
                                <CheckCircle2 size={11} />
                                <span>
                                  {isIntermediateDeal
                                    ? 'Deal Winner'
                                    : isPool
                                      ? 'Pool Champion'
                                      : isDeals
                                        ? 'Deals Champion'
                                        : 'Winner'}
                                </span>
                              </>
                            ) : p.isEliminated ? (
                              <>
                                <AlertCircle size={11} />
                                <span>Eliminated</span>
                              </>
                            ) : p.status === 'DROPPED' ? (
                              <>
                                <AlertCircle size={11} />
                                <span>Dropped</span>
                              </>
                            ) : (
                              <>
                                <AlertCircle size={11} />
                                <span>Lost</span>
                              </>
                            )}
                          </div>
                        </div>
                      </div>

                      <div className="his-row-meta">
                        {isIntermediateDeal ? (
                          <>
                            <span
                              className={`his-tx-amount ${won && !p.isEliminated ? 'his-tx-amount--credit' : 'his-tx-amount--debit'
                                }`}
                            >
                              {isDeals
                                ? (won ? 'Won Deal' : `-${p.score} chips`)
                                : (p.isEliminated ? 'OUT' : won ? '0 pts' : `+${p.score} pts`)}
                            </span>
                            <span className="his-row-date">
                              {isDeals ? (
                                <span className="res-sub-gold">{p.chipBalance ?? 0} chips</span>
                              ) : (
                                <span className={p.isEliminated ? 'his-tx-amount--debit' : 'res-sub-gold'}>
                                  {p.cumulativeScore}/{threshold}
                                </span>
                              )}
                            </span>
                          </>
                        ) : (
                          <>
                            <span className={rowNetClass}>{rowNetText}</span>
                            {(!isScheduledTournament || isDeals) && (
                              <span className="his-row-date">
                                {isDeals ? (
                                  <span className="res-sub-gold">
                                    {p.chipBalance ?? 0} chips · {p.score} pts
                                  </span>
                                ) : (
                                  <>
                                    <span>{p.score} pts</span>
                                    {isPool && (
                                      <span className="res-sub-gold" style={{ marginLeft: '6px' }}>
                                        (Total: {p.cumulativeScore})
                                      </span>
                                    )}
                                  </>
                                )}
                                {!isScheduledTournament && !isFreePrivateRoom && pRefund !== undefined && pRefund > 0 && !isPool && !isDeals && (
                                  <span className="res-sub-credit">
                                    (+₹ {formatAmount(pRefund)} refund)
                                  </span>
                                )}
                                {!isScheduledTournament && !isFreePrivateRoom && (isSettledWinner || splitPayouts?.[p.playerId] != null) && totalCredit > 0 && (
                                  <span className="res-sub-credit">
                                    (Total Credit: ₹ {formatAmount(totalCredit)})
                                  </span>
                                )}
                              </span>
                            )}
                          </>
                        )}
                      </div>
                    </div>

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
                  </div>
                );
              })}
            </div>
          </section>
        </main>

        {/* Bottom Actions Bar */}
        <footer className="res-actions-bar">
          <button
            type="button"
            className="res-btn-leave"
            onClick={handleLeave}
          >
            <LogOut size={15} />
            Leave Table
          </button>

          {isPrivateRoom && !isIntermediateDeal && (
            <button
              type="button"
              className="res-btn-secondary"
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
              className="res-btn-rejoin"
              onClick={() => {
                soundEngine.play('click');
                socketClient.rejoinTable();
              }}
            >
              <Sparkles size={15} />
              Re-Join Table (₹{rejoinFee})
            </button>
          )}

          {(isPool || isDeals) && onOpenScoreboard && (
            <button
              type="button"
              className="res-btn-secondary"
              onClick={() => {
                soundEngine.play('click');
                onOpenScoreboard();
              }}
            >
              <Layers size={15} />
              View Full Scoreboard
            </button>
          )}

          {isIntermediateDeal ? (
            <div className="res-btn-waiting">
              <RotateCcw size={15} className="his-spin" />
              Next Deal Starting ({dealCountdown}s)
            </div>
          ) : !isScheduledTournament ? (
            <button
              type="button"
              className="stake-play-btn res-btn-primary"
              onClick={handleRematch}
            >
              <RotateCcw size={16} />
              Rematch Same Stake
            </button>
          ) : null}
        </footer>
      </div>
    </div>
  );
};
