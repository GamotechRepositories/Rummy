import { describe, it, expect, beforeEach } from 'vitest';
import { useGameStore } from './useGameStore';
import {
  persistCardGroups,
  readPersistedCardGroups,
  clearPersistedCardGroups,
} from '../utils/sessionResume';
import type { CardInstance, PlayerGameView, VisualCardGroup } from '../types/game';

const mockHand: CardInstance[] = [
  { instanceId: 'c1', suit: 'SPADES', rank: 'ACE', deckIndex: 1, printedJoker: false },
  { instanceId: 'c2', suit: 'SPADES', rank: 'TWO', deckIndex: 1, printedJoker: false },
  { instanceId: 'c3', suit: 'SPADES', rank: 'THREE', deckIndex: 1, printedJoker: false },
  { instanceId: 'c4', suit: 'HEARTS', rank: 'FIVE', deckIndex: 1, printedJoker: false },
  { instanceId: 'c5', suit: 'HEARTS', rank: 'SIX', deckIndex: 1, printedJoker: false },
  { instanceId: 'c6', suit: 'HEARTS', rank: 'SEVEN', deckIndex: 1, printedJoker: false },
  { instanceId: 'c7', suit: 'DIAMONDS', rank: 'TEN', deckIndex: 1, printedJoker: false },
  { instanceId: 'c8', suit: 'DIAMONDS', rank: 'JACK', deckIndex: 1, printedJoker: false },
  { instanceId: 'c9', suit: 'DIAMONDS', rank: 'QUEEN', deckIndex: 1, printedJoker: false },
  { instanceId: 'c10', suit: 'CLUBS', rank: 'TWO', deckIndex: 1, printedJoker: false },
  { instanceId: 'c11', suit: 'CLUBS', rank: 'THREE', deckIndex: 1, printedJoker: false },
  { instanceId: 'c12', suit: 'CLUBS', rank: 'FOUR', deckIndex: 1, printedJoker: false },
  { instanceId: 'c13', suit: 'CLUBS', rank: 'FIVE', deckIndex: 1, printedJoker: false },
];

const mockGameView: PlayerGameView = {
  tableId: 'TBL_TEST_01',
  gameId: 'GAME_TEST_01',
  viewerPlayerId: 'P1',
  gameStatus: 'IN_PROGRESS',
  sequence: 1,
  hand: mockHand,
  opponents: [],
  topDiscard: null,
  cutJoker: { instanceId: 'cj', suit: 'HEARTS', rank: 'FOUR', deckIndex: 1, printedJoker: false },
  closedDeckRemaining: 30,
  activePlayerId: 'P1',
  turnPhase: 'DRAW',
  turnDeadline: null,
  isMyTurn: true,
};

class MockStorage implements Storage {
  private store: Record<string, string> = {};
  get length() {
    return Object.keys(this.store).length;
  }
  clear() {
    this.store = {};
  }
  getItem(key: string) {
    return this.store[key] ?? null;
  }
  setItem(key: string, value: string) {
    this.store[key] = String(value);
  }
  removeItem(key: string) {
    delete this.store[key];
  }
  key(index: number) {
    return Object.keys(this.store)[index] ?? null;
  }
}

if (typeof globalThis.localStorage === 'undefined') {
  (globalThis as unknown as { localStorage: Storage }).localStorage = new MockStorage();
}
if (typeof globalThis.sessionStorage === 'undefined') {
  (globalThis as unknown as { sessionStorage: Storage }).sessionStorage = new MockStorage();
}

describe('Card Groups Persistence Across Page Refresh', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearPersistedCardGroups();
    useGameStore.setState({
      tableId: 'TBL_TEST_01',
      gameState: null,
      groups: [],
      selectedCardIds: [],
    });
  });

  it('persists and reads card groups correctly', () => {
    const dummyGroups: VisualCardGroup[] = [
      {
        id: 'grp_1',
        cards: [mockHand[0], mockHand[1]],
        groupType: 'INVALID',
        deadwoodPoints: 3,
      },
    ];

    persistCardGroups('TBL_TEST_01', 'GAME_TEST_01', dummyGroups);
    const read = readPersistedCardGroups('TBL_TEST_01', 'GAME_TEST_01');
    expect(read).toHaveLength(1);
    expect(read![0].cards).toHaveLength(2);

    // Mismatched tableId returns null
    expect(readPersistedCardGroups('TBL_OTHER', 'GAME_TEST_01')).toBeNull();
    // Mismatched gameId returns null
    expect(readPersistedCardGroups('TBL_TEST_01', 'GAME_OTHER')).toBeNull();
  });

  it('keeps sorted groups intact when page refreshes and state reconnects', () => {
    // 1. Initial game state arrives
    useGameStore.getState().updateGameState(mockGameView);

    // 2. User clicks Sort
    useGameStore.getState().autoSortHand();

    const sortedGroups = useGameStore.getState().groups;
    // Should have 4 suit groups
    expect(sortedGroups.length).toBeGreaterThan(1);
    const suitGroupCount = sortedGroups.length;

    // 3. Simulate browser page refresh:
    // In-memory zustand store resets to groups = [] and gameState = null
    useGameStore.setState({
      groups: [],
      gameState: null,
    });

    // 4. Server sends GAME_VIEW on reconnect
    useGameStore.getState().updateGameState(mockGameView);

    // 5. Verify that groups did NOT collapse to 1 unsorted group!
    const reconnectedGroups = useGameStore.getState().groups;
    expect(reconnectedGroups.length).toBe(suitGroupCount);
    // Total cards should still be 13
    const totalCards = reconnectedGroups.reduce((acc, g) => acc + g.cards.length, 0);
    expect(totalCards).toBe(13);
  });

  it('keeps manual groups intact when page refreshes', () => {
    useGameStore.getState().updateGameState(mockGameView);

    // Select first 3 cards and group them
    useGameStore.setState({ selectedCardIds: ['c1', 'c2', 'c3'] });
    useGameStore.getState().groupSelectedCards();

    const groupsBeforeRefresh = useGameStore.getState().groups;
    expect(groupsBeforeRefresh.length).toBe(2);

    // Simulate page refresh
    useGameStore.setState({
      groups: [],
      gameState: null,
    });

    // Reconnect
    useGameStore.getState().updateGameState(mockGameView);

    const groupsAfterRefresh = useGameStore.getState().groups;
    expect(groupsAfterRefresh.length).toBe(2);
    // One group should contain c1, c2, c3
    const customGroup = groupsAfterRefresh.find((g) => g.cards.some((c) => c.instanceId === 'c1'));
    expect(customGroup).toBeDefined();
    expect(customGroup!.cards.map((c) => c.instanceId)).toEqual(['c1', 'c2', 'c3']);
  });

  it('deals fresh cards cleanly on Deal 2 without retaining Deal 1 groups', () => {
    // 1. Deal 1 starts
    useGameStore.getState().updateGameState({ ...mockGameView, dealNumber: 1 });
    useGameStore.getState().autoSortHand();
    expect(useGameStore.getState().groups.length).toBeGreaterThan(1);

    // 2. Deal 1 finishes
    useGameStore.getState().updateGameState({
      ...mockGameView,
      dealNumber: 1,
      gameStatus: 'COMPLETED',
      winnerId: 'P1',
    });

    // 3. Deal 2 starts with fresh hand of cards
    const freshDeal2Hand: CardInstance[] = [
      { instanceId: 'd2_c1', suit: 'HEARTS', rank: 'ACE', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c2', suit: 'HEARTS', rank: 'TWO', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c3', suit: 'HEARTS', rank: 'THREE', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c4', suit: 'SPADES', rank: 'FIVE', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c5', suit: 'SPADES', rank: 'SIX', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c6', suit: 'SPADES', rank: 'SEVEN', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c7', suit: 'CLUBS', rank: 'TEN', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c8', suit: 'CLUBS', rank: 'JACK', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c9', suit: 'CLUBS', rank: 'QUEEN', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c10', suit: 'DIAMONDS', rank: 'TWO', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c11', suit: 'DIAMONDS', rank: 'THREE', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c12', suit: 'DIAMONDS', rank: 'FOUR', deckIndex: 1, printedJoker: false },
      { instanceId: 'd2_c13', suit: 'DIAMONDS', rank: 'FIVE', deckIndex: 1, printedJoker: false },
    ];

    useGameStore.getState().updateGameState({
      ...mockGameView,
      dealNumber: 2,
      gameStatus: 'IN_PROGRESS',
      hand: freshDeal2Hand,
    });

    const deal2Groups = useGameStore.getState().groups;
    // Total cards in Deal 2 groups must be 13 and all must be from freshDeal2Hand
    const deal2Cards = deal2Groups.flatMap((g) => g.cards);
    expect(deal2Cards).toHaveLength(13);
    expect(deal2Cards.every((c) => c.instanceId.startsWith('d2_'))).toBe(true);
    // None of Deal 1 cards should remain
    expect(deal2Cards.some((c) => c.instanceId === 'c1')).toBe(false);
  });
});
