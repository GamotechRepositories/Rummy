import { create } from 'zustand';
import type { CardInstance, PlayerGameView, VisualCardGroup } from '../types/game';
import { evaluateCardGroup, getCardScore } from '../rules/clientValidator';
import {
  clearActiveSessionLocal,
  getOrCreatePlayerId,
  persistActiveSession,
  readPersistedDisplayName,
  readPersistedLastConfig,
} from '../utils/sessionResume';
import { persistAvatarId, readOrCreateAvatarId } from '../utils/avatarUtils';

interface GameStoreState {
  tableId: string;
  playerId: string;
  displayName: string;
  avatarId: string;
  connectionStatus: 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING';
  hasJoinedTable: boolean;
  gameState: PlayerGameView | null;
  groups: VisualCardGroup[];
  selectedCardIds: string[];
  isDeclareModalOpen: boolean;
  errorMessage: string | null;
  lastEventMessage: string | null;
  lastGameConfig: {
    rulesetId: string;
    entryFee: number;
    maxPlayers: number;
  } | null;
  autoMatchmakePending: boolean;
  lastKnownHand: CardInstance[];
  /** Soft-reconnect in progress (waiting for first GAME_VIEW). */
  resumePending: boolean;

  // Actions
  setConnectionStatus: (status: 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING') => void;
  setSession: (tableId: string, playerId: string, displayName: string) => void;
  setDisplayName: (displayName: string) => void;
  setAvatarId: (avatarId: string) => void;
  setHasJoinedTable: (joined: boolean) => void;
  leaveTable: () => void;
  setResumePending: (pending: boolean) => void;
  clearResumeOnGameOver: () => void;
  setLastGameConfig: (config: { rulesetId: string; entryFee: number; maxPlayers: number } | null) => void;
  setAutoMatchmakePending: (pending: boolean) => void;
  updateGameState: (view: PlayerGameView) => void;
  toggleSelectCard: (cardId: string) => void;
  clearSelection: () => void;
  groupSelectedCards: () => void;
  moveCardsToGroup: (cardIds: string[], targetGroupId: string | 'NEW') => void;
  ungroupCard: (cardId: string) => void;
  autoSortHand: () => void;
  setDeclareModalOpen: (open: boolean) => void;
  setErrorMessage: (msg: string | null) => void;
  setLastEventMessage: (msg: string | null) => void;
}

const SUIT_ORDER: Record<string, number> = {
  SPADES: 0,
  HEARTS: 1,
  CLUBS: 2,
  DIAMONDS: 3,
  NONE: 4,
};

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

function organizeHandIntoGroups(
  existingGroups: VisualCardGroup[],
  hand: CardInstance[] | undefined | null,
  wildJoker: CardInstance | null
): VisualCardGroup[] {
  if (!hand || hand.length === 0) {
    return [];
  }

  const handMap = new Map<string, CardInstance>();
  hand.forEach(c => handMap.set(c.instanceId, c));

  const newGroups: VisualCardGroup[] = [];
  const claimed = new Set<string>();

  // Preserve existing user groups where possible
  for (const g of existingGroups) {
    const validCards: CardInstance[] = [];
    for (const c of g.cards) {
      if (handMap.has(c.instanceId)) {
        validCards.push(handMap.get(c.instanceId)!);
        claimed.add(c.instanceId);
      }
    }
    if (validCards.length > 0) {
      newGroups.push({
        id: g.id,
        cards: validCards,
        groupType: evaluateCardGroup(validCards, wildJoker),
        deadwoodPoints: validCards.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
      });
    }
  }

  // Any newly drawn or unclaimed cards go into a new group
  const unassigned: CardInstance[] = [];
  for (const c of hand) {
    if (!claimed.has(c.instanceId)) {
      unassigned.push(c);
    }
  }

  if (unassigned.length > 0) {
    newGroups.push({
      id: 'grp_' + Date.now() + '_' + Math.random().toString(36).substring(2, 5),
      cards: unassigned,
      groupType: evaluateCardGroup(unassigned, wildJoker),
      deadwoodPoints: unassigned.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
    });
  }

  return newGroups.length > 0
    ? newGroups
    : [
        {
          id: 'grp_default',
          cards: hand,
          groupType: evaluateCardGroup(hand, wildJoker),
          deadwoodPoints: hand.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
        },
      ];
}

const getStoredPlayerId = (): string => getOrCreatePlayerId();

const initialDisplayName = readPersistedDisplayName() || 'RoyalAce';
const initialLastConfig = readPersistedLastConfig();

/** Backend CardInstance may nest suit/rank under `card` and use `deckNumber`. */
function normalizeCard(raw: unknown): CardInstance {
  if (!raw || typeof raw !== 'object') {
    return {
      instanceId: '',
      suit: 'NONE',
      rank: 'JOKER',
      deckIndex: 1,
      printedJoker: false,
    };
  }
  const c = raw as Record<string, unknown>;
  const nested = (c.card as Record<string, unknown> | undefined) ?? {};
  return {
    instanceId: String(c.instanceId ?? ''),
    suit: (c.suit ?? nested.suit ?? 'NONE') as CardInstance['suit'],
    rank: (c.rank ?? nested.rank ?? 'JOKER') as CardInstance['rank'],
    deckIndex: Number(c.deckIndex ?? c.deckNumber ?? 1),
    printedJoker: Boolean(c.printedJoker ?? nested.printedJoker ?? false),
  };
}

function normalizeHand(hand: unknown[] | undefined | null): CardInstance[] {
  if (!hand || !Array.isArray(hand)) return [];
  return hand.map(normalizeCard).filter((c) => !!c.instanceId);
}

export const useGameStore = create<GameStoreState>((set, get) => ({
  tableId: 'TBL_ROYAL_01',
  playerId: getStoredPlayerId(),
  displayName: initialDisplayName,
  avatarId: readOrCreateAvatarId(),
  connectionStatus: 'DISCONNECTED',
  hasJoinedTable: false,
  gameState: null,
  groups: [],
  selectedCardIds: [],
  isDeclareModalOpen: false,
  errorMessage: null,
  lastEventMessage: null,
  lastGameConfig: initialLastConfig,
  autoMatchmakePending: false,
  lastKnownHand: [],
  resumePending: false,

  setConnectionStatus: (status) => set({ connectionStatus: status }),

  setSession: (tableId, playerId, displayName) => {
    const { lastGameConfig } = get();
    persistActiveSession({ tableId, playerId, displayName, lastGameConfig });
    set({ tableId, playerId, displayName });
  },

  setDisplayName: (displayName) => {
    try {
      localStorage.setItem('rummy_display_name', displayName);
      sessionStorage.setItem('rummy_display_name', displayName);
    } catch {
      // ignore
    }
    set({ displayName });
  },

  setAvatarId: (avatarId) => {
    persistAvatarId(avatarId);
    set({ avatarId });
  },

  setHasJoinedTable: (joined) => set({ hasJoinedTable: joined }),

  setResumePending: (pending) => set({ resumePending: pending }),

  clearResumeOnGameOver: () => {
    clearActiveSessionLocal();
  },

  setLastGameConfig: (config) => {
    if (config) {
      try {
        localStorage.setItem('rummy_last_game_config', JSON.stringify(config));
        sessionStorage.setItem('rummy_last_game_config', JSON.stringify(config));
      } catch {
        // ignore
      }
    }
    set({ lastGameConfig: config });
  },

  setAutoMatchmakePending: (pending) => set({ autoMatchmakePending: pending }),

  leaveTable: () => {
    clearActiveSessionLocal();
    set({
      hasJoinedTable: false,
      resumePending: false,
      gameState: null,
      groups: [],
      selectedCardIds: [],
      isDeclareModalOpen: false,
      errorMessage: null,
      lastEventMessage: null,
      lastKnownHand: [],
    });
  },

  updateGameState: (view) => {
    const { groups, selectedCardIds, lastKnownHand } = get();
    const hand = normalizeHand(view.hand || []);
    const cutJoker = view.cutJoker ? normalizeCard(view.cutJoker) : null;
    const topDiscard = view.topDiscard ? normalizeCard(view.topDiscard) : null;
    const discardHistory = (view.discardHistory || []).map(normalizeCard);
    const opponents = (view.opponents || []).map((opp) => ({
      ...opp,
      hand: normalizeHand(opp.hand || []),
    }));
    const winningGroups = (view.winningGroups || []).map((wg) => ({
      ...wg,
      cards: normalizeHand(wg.cards || []),
    }));

    // Cache the most recent valid hand (>= 10 cards) so drops/showdown never lose cards
    const currentLastKnown = lastKnownHand || [];
    const nextLastKnown = hand.length >= 10 ? hand : currentLastKnown;

    // If viewer hand is cleared (e.g. dropped), recover cards at showdown
    const effectiveHand =
      hand.length === 0 && view.gameStatus === 'COMPLETED' && nextLastKnown.length > 0
        ? nextLastKnown
        : hand;

    const normalizedView: PlayerGameView = {
      ...view,
      hand: effectiveHand,
      opponents,
      winningGroups,
      cutJoker,
      topDiscard,
      discardHistory,
      turnPhase: view.turnPhase,
    };

    const updatedGroups =
      hand.length === 0
        ? (groups.length > 0 ? groups : organizeHandIntoGroups([], nextLastKnown, cutJoker))
        : organizeHandIntoGroups(groups, hand, cutJoker);

    const handIds = new Set(effectiveHand.map((c) => c.instanceId));
    const viewerDropped = view.viewerStatus === 'DROPPED' && view.gameStatus === 'IN_PROGRESS';
    const nextSelected = viewerDropped ? [] : selectedCardIds.filter((id) => handIds.has(id));

    if (view.gameStatus === 'COMPLETED' || view.gameStatus === 'ABORTED') {
      clearActiveSessionLocal();
    }

    set({
      gameState: normalizedView,
      groups: updatedGroups,
      selectedCardIds: nextSelected,
      isDeclareModalOpen: viewerDropped ? false : get().isDeclareModalOpen,
      lastKnownHand: nextLastKnown,
      resumePending: false,
    });
  },

  toggleSelectCard: (cardId) => {
    const { selectedCardIds, gameState } = get();
    if (gameState?.viewerStatus === 'DROPPED') return;
    if (selectedCardIds.includes(cardId)) {
      set({ selectedCardIds: selectedCardIds.filter(id => id !== cardId) });
    } else {
      set({ selectedCardIds: [...selectedCardIds, cardId] });
    }
  },

  clearSelection: () => set({ selectedCardIds: [] }),

  groupSelectedCards: () => {
    const { selectedCardIds, groups, gameState } = get();
    if (gameState?.viewerStatus === 'DROPPED') return;
    if (selectedCardIds.length === 0) return;

    const selectedSet = new Set(selectedCardIds);
    const selectedCards: CardInstance[] = [];
    const remainingGroups: VisualCardGroup[] = [];

    for (const g of groups) {
      const keptCards: CardInstance[] = [];
      for (const c of g.cards) {
        if (selectedSet.has(c.instanceId)) {
          selectedCards.push(c);
        } else {
          keptCards.push(c);
        }
      }
      if (keptCards.length > 0) {
        remainingGroups.push({
          ...g,
          cards: keptCards,
          groupType: evaluateCardGroup(keptCards, gameState?.cutJoker ?? null),
          deadwoodPoints: keptCards.reduce(
            (acc, c) => acc + getCardScore(c, gameState?.cutJoker ?? null),
            0
          ),
        });
      }
    }

    if (selectedCards.length > 0) {
      remainingGroups.push({
        id: 'grp_' + Date.now(),
        cards: selectedCards,
        groupType: evaluateCardGroup(selectedCards, gameState?.cutJoker ?? null),
        deadwoodPoints: selectedCards.reduce(
          (acc, c) => acc + getCardScore(c, gameState?.cutJoker ?? null),
          0
        ),
      });
    }

    set({ groups: remainingGroups, selectedCardIds: [] });
  },

  moveCardsToGroup: (cardIds, targetGroupId) => {
    const { groups, gameState } = get();
    if (gameState?.viewerStatus === 'DROPPED') return;
    if (!cardIds.length) return;

    const movingSet = new Set(cardIds);
    const movingCards: CardInstance[] = [];
    const remainingGroups: VisualCardGroup[] = [];
    const wildJoker = gameState?.cutJoker ?? null;

    for (const g of groups) {
      const keptCards: CardInstance[] = [];
      for (const c of g.cards) {
        if (movingSet.has(c.instanceId)) {
          movingCards.push(c);
        } else {
          keptCards.push(c);
        }
      }
      if (keptCards.length > 0) {
        remainingGroups.push({
          ...g,
          cards: keptCards,
          groupType: evaluateCardGroup(keptCards, wildJoker),
          deadwoodPoints: keptCards.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
        });
      }
    }

    if (movingCards.length === 0) return;

    if (targetGroupId === 'NEW') {
      remainingGroups.push({
        id: 'grp_' + Date.now() + '_' + Math.random().toString(36).substring(2, 5),
        cards: movingCards,
        groupType: evaluateCardGroup(movingCards, wildJoker),
        deadwoodPoints: movingCards.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
      });
    } else {
      let found = false;
      for (let i = 0; i < remainingGroups.length; i++) {
        if (remainingGroups[i].id === targetGroupId) {
          const nextCards = [...remainingGroups[i].cards, ...movingCards];
          remainingGroups[i] = {
            ...remainingGroups[i],
            cards: nextCards,
            groupType: evaluateCardGroup(nextCards, wildJoker),
            deadwoodPoints: nextCards.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
          };
          found = true;
          break;
        }
      }
      // Target vanished (e.g. emptied while dragging) — create new group
      if (!found) {
        remainingGroups.push({
          id: targetGroupId.startsWith('grp_') ? targetGroupId : 'grp_' + Date.now(),
          cards: movingCards,
          groupType: evaluateCardGroup(movingCards, wildJoker),
          deadwoodPoints: movingCards.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0),
        });
      }
    }

    set({ groups: remainingGroups, selectedCardIds: [] });
  },

  ungroupCard: (cardId) => {
    const { groups, gameState } = get();
    let targetCard: CardInstance | null = null;
    const nextGroups: VisualCardGroup[] = [];

    for (const g of groups) {
      const kept = g.cards.filter(c => {
        if (c.instanceId === cardId) {
          targetCard = c;
          return false;
        }
        return true;
      });
      if (kept.length > 0) {
        nextGroups.push({
          ...g,
          cards: kept,
          groupType: evaluateCardGroup(kept, gameState?.cutJoker ?? null),
          deadwoodPoints: kept.reduce(
            (acc, c) => acc + getCardScore(c, gameState?.cutJoker ?? null),
            0
          ),
        });
      }
    }

    if (targetCard) {
      nextGroups.push({
        id: 'grp_' + Date.now(),
        cards: [targetCard],
        groupType: 'INVALID',
        deadwoodPoints: getCardScore(targetCard, gameState?.cutJoker ?? null),
      });
    }

    set({ groups: nextGroups });
  },

  autoSortHand: () => {
    const { gameState } = get();
    if (gameState?.viewerStatus === 'DROPPED') return;
    const hand = gameState?.hand;
    if (!hand || !hand.length) return;

    // Sort all cards by Suit and Rank
    const sorted = [...hand].sort((a, b) => {
      const sComp = (SUIT_ORDER[a.suit] ?? 99) - (SUIT_ORDER[b.suit] ?? 99);
      if (sComp !== 0) return sComp;
      return (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
    });

    // Bucket cards by suit into initial groups
    const suitBuckets: Record<string, CardInstance[]> = {};
    for (const card of sorted) {
      const key = card.printedJoker ? 'JOKER' : card.suit;
      if (!suitBuckets[key]) suitBuckets[key] = [];
      suitBuckets[key].push(card);
    }

    const newGroups: VisualCardGroup[] = Object.entries(suitBuckets).map(
      ([key, cards]) => ({
        id: 'grp_sort_' + key,
        cards,
        groupType: evaluateCardGroup(cards, gameState.cutJoker),
        deadwoodPoints: cards.reduce(
          (acc, c) => acc + getCardScore(c, gameState.cutJoker),
          0
        ),
      })
    );

    set({ groups: newGroups, selectedCardIds: [] });
  },

  setDeclareModalOpen: (open) => set({ isDeclareModalOpen: open }),
  setErrorMessage: (msg) => set({ errorMessage: msg }),
  setLastEventMessage: (msg) => set({ lastEventMessage: msg }),
}));
