import { describe, it, expect } from 'vitest';
import {
  validatePureSequence,
  validateImpureSequence,
  validateSet,
  evaluateCardGroup,
  checkOverallDeclaration,
  calculateHandPenalty,
} from './clientValidator';
import type { CardInstance } from '../types/game';

function makeCard(suit: 'HEARTS' | 'DIAMONDS' | 'CLUBS' | 'SPADES' | 'NONE', rank: any, printedJoker = false): CardInstance {
  return {
    instanceId: `${suit}_${rank}_${Math.random()}`,
    suit,
    rank,
    deckIndex: 0,
    printedJoker,
  };
}

describe('clientValidator Unit Tests', () => {
  const wildJoker = makeCard('SPADES', 'SEVEN');

  it('validates Ace-high and Ace-low pure sequences', () => {
    // Ace-low: A-2-3 of Hearts
    const aceLow = [makeCard('HEARTS', 'ACE'), makeCard('HEARTS', 'TWO'), makeCard('HEARTS', 'THREE')];
    expect(validatePureSequence(aceLow, wildJoker)).toBe(true);

    // Ace-high: Q-K-A of Diamonds
    const aceHigh = [makeCard('DIAMONDS', 'QUEEN'), makeCard('DIAMONDS', 'KING'), makeCard('DIAMONDS', 'ACE')];
    expect(validatePureSequence(aceHigh, wildJoker)).toBe(true);

    // Mid sequence: 8-9-10-J of Clubs
    const midSeq = [
      makeCard('CLUBS', 'EIGHT'),
      makeCard('CLUBS', 'NINE'),
      makeCard('CLUBS', 'TEN'),
      makeCard('CLUBS', 'JACK'),
    ];
    expect(validatePureSequence(midSeq, wildJoker)).toBe(true);
  });

  it('validates natural wild card in pure sequence and rejects wild joker substitution', () => {
    // 6-7-8 of Spades has wild joker 7 of Spades in its natural position -> Valid Pure Sequence
    const naturalRun = [makeCard('SPADES', 'SIX'), makeCard('SPADES', 'SEVEN'), makeCard('SPADES', 'EIGHT')];
    expect(validatePureSequence(naturalRun, wildJoker)).toBe(true);

    // 6S - 7D(wild) - 8S has 7 of Diamonds substituted -> Invalid Pure Sequence (it is an Impure Sequence)
    const withSubstitutedWild = [makeCard('SPADES', 'SIX'), makeCard('DIAMONDS', 'SEVEN'), makeCard('SPADES', 'EIGHT')];
    expect(validatePureSequence(withSubstitutedWild, wildJoker)).toBe(false);

    // Sequence containing printed joker is strictly invalid for pure sequence
    const withPrinted = [makeCard('SPADES', 'SIX'), makeCard('NONE', 'JOKER', true), makeCard('SPADES', 'EIGHT')];
    expect(validatePureSequence(withPrinted, wildJoker)).toBe(false);

    // Suit mismatch: 4H, 5D, 6H
    const suitMismatch = [makeCard('HEARTS', 'FOUR'), makeCard('DIAMONDS', 'FIVE'), makeCard('HEARTS', 'SIX')];
    expect(validatePureSequence(suitMismatch, wildJoker)).toBe(false);
  });

  it('validates impure sequences using wild and printed jokers', () => {
    // 4H, 7S(wild as 5H), 6H
    const withWild = [makeCard('HEARTS', 'FOUR'), makeCard('SPADES', 'SEVEN'), makeCard('HEARTS', 'SIX')];
    expect(validateImpureSequence(withWild, wildJoker)).toBe(true);
    expect(evaluateCardGroup(withWild, wildJoker)).toBe('IMPURE_SEQUENCE');

    // 10D, JKR(printed as JD), QD
    const withPrinted = [makeCard('DIAMONDS', 'TEN'), makeCard('NONE', 'JOKER', true), makeCard('DIAMONDS', 'QUEEN')];
    expect(validateImpureSequence(withPrinted, wildJoker)).toBe(true);

    // Printed Joker as Cut Card makes all Aces Wild Jokers (3S, AS as 4S, 5S)
    const printedCutJoker = makeCard('NONE', 'JOKER', true);
    const aceWildSeq = [makeCard('SPADES', 'THREE'), makeCard('SPADES', 'ACE'), makeCard('SPADES', 'FIVE')];
    expect(validateImpureSequence(aceWildSeq, printedCutJoker)).toBe(true);
    expect(evaluateCardGroup(aceWildSeq, printedCutJoker)).toBe('IMPURE_SEQUENCE');
  });

  it('validates sets with distinct suits and rejects duplicate suits', () => {
    // 8H, 8D, 8C
    const validSet = [makeCard('HEARTS', 'EIGHT'), makeCard('DIAMONDS', 'EIGHT'), makeCard('CLUBS', 'EIGHT')];
    expect(validateSet(validSet, wildJoker)).toBe(true);
    expect(evaluateCardGroup(validSet, wildJoker)).toBe('SET');

    // Duplicate suits from 2 decks: 8H, 8H, 8D is invalid
    const dupSuitSet = [makeCard('HEARTS', 'EIGHT'), makeCard('HEARTS', 'EIGHT'), makeCard('DIAMONDS', 'EIGHT')];
    expect(validateSet(dupSuitSet, wildJoker)).toBe(false);

    // 1 natural card + 2 jokers is a valid set
    const setWithTwoJokers = [makeCard('HEARTS', 'EIGHT'), makeCard('SPADES', 'SEVEN'), makeCard('NONE', 'JOKER', true)];
    expect(validateSet(setWithTwoJokers, wildJoker)).toBe(true);

    // 2 natural cards of different suits + 1 joker evaluates to SET
    const setWithJoker = [makeCard('HEARTS', 'EIGHT'), makeCard('DIAMONDS', 'EIGHT'), makeCard('SPADES', 'SEVEN')];
    expect(validateSet(setWithJoker, wildJoker)).toBe(true);
    expect(evaluateCardGroup(setWithJoker, wildJoker)).toBe('SET');

    // 3 jokers (3 wild/printed jokers) is a valid set
    const allJokersSet = [makeCard('SPADES', 'SEVEN'), makeCard('HEARTS', 'SEVEN'), makeCard('NONE', 'JOKER', true)];
    expect(validateSet(allJokersSet, wildJoker)).toBe(true);
  });

  it('evaluates complete winning declaration', () => {
    const pureSeq = { cards: [makeCard('HEARTS', 'FOUR'), makeCard('HEARTS', 'FIVE'), makeCard('HEARTS', 'SIX')] };
    const impureSeq = { cards: [makeCard('SPADES', 'TWO'), makeCard('SPADES', 'SEVEN'), makeCard('SPADES', 'FOUR')] }; // 7S wild as 3S
    const set1 = { cards: [makeCard('HEARTS', 'KING'), makeCard('DIAMONDS', 'KING'), makeCard('CLUBS', 'KING')] };
    const set2 = { cards: [makeCard('HEARTS', 'NINE'), makeCard('DIAMONDS', 'NINE'), makeCard('CLUBS', 'NINE'), makeCard('SPADES', 'NINE')] };

    const winResult = checkOverallDeclaration([pureSeq, impureSeq, set1, set2], wildJoker);
    expect(winResult.isValid).toBe(true);

    // Missing pure sequence (only impure sequences)
    const noPureResult = checkOverallDeclaration([impureSeq, impureSeq, set1, set2], wildJoker);
    expect(noPureResult.isValid).toBe(false);
    expect(noPureResult.reason).toContain('Pure Sequence');

    // Test exact scenario from live screenshot where cut card is a printed joker
    const printedCutJoker = makeCard('NONE', 'JOKER', true);
    const g1 = { cards: [makeCard('CLUBS', 'JACK'), makeCard('CLUBS', 'QUEEN'), makeCard('CLUBS', 'KING')] }; // Pure
    const g2 = { cards: [makeCard('SPADES', 'THREE'), makeCard('SPADES', 'ACE'), makeCard('SPADES', 'FIVE')] }; // Impure (AS wild)
    const g3 = { cards: [makeCard('HEARTS', 'FOUR'), makeCard('CLUBS', 'ACE'), makeCard('HEARTS', 'SIX'), makeCard('HEARTS', 'SEVEN')] }; // Impure (AC wild)
    const g4 = { cards: [makeCard('SPADES', 'TWO'), makeCard('CLUBS', 'TWO'), makeCard('CLUBS', 'ACE')] }; // Set of 2s (AC wild)

    const liveGameResult = checkOverallDeclaration([g1, g2, g3, g4], printedCutJoker);
    expect(liveGameResult.isValid).toBe(true);
  });

  it('validates Tunnela as Pure Sequence and enforces 3 pure sequences for 21-Card Rummy', () => {
    // 3 identical King of Spades
    const tunnelaCards = [
      makeCard('SPADES', 'KING'),
      makeCard('SPADES', 'KING'),
      makeCard('SPADES', 'KING'),
    ];
    expect(validatePureSequence(tunnelaCards, wildJoker)).toBe(true);
    expect(evaluateCardGroup(tunnelaCards, wildJoker)).toBe('PURE_SEQUENCE');

    const pure1 = { cards: [makeCard('HEARTS', 'ACE'), makeCard('HEARTS', 'TWO'), makeCard('HEARTS', 'THREE')] };
    const pure2 = { cards: [makeCard('CLUBS', 'EIGHT'), makeCard('CLUBS', 'NINE'), makeCard('CLUBS', 'TEN')] };
    const tunnelaGroup = { cards: tunnelaCards };
    const set1 = { cards: [makeCard('HEARTS', 'FOUR'), makeCard('DIAMONDS', 'FOUR'), makeCard('CLUBS', 'FOUR')] };

    // With 2 pure sequences only in 21-card rummy, it must fail (requires 3 pure sequences)
    const twoPure21Result = checkOverallDeclaration([pure1, pure2, set1], wildJoker, 'RUMMY_21');
    expect(twoPure21Result.isValid).toBe(false);
    expect(twoPure21Result.reason).toContain('at least 3 Pure Sequence');

    // With 3 pure sequences (2 runs + 1 tunnela), it must pass
    const threePure21Result = checkOverallDeclaration([pure1, pure2, tunnelaGroup, set1], wildJoker, 'RUMMY_21');
    expect(threePure21Result.isValid).toBe(true);
  });

  it('calculates 21-Card Rummy penalties correctly (120 cap, 3 pure sequence exemption)', () => {
    const pure1 = { cards: [makeCard('HEARTS', 'ACE'), makeCard('HEARTS', 'TWO'), makeCard('HEARTS', 'THREE')], groupType: 'PURE_SEQUENCE' as const };
    const pure2 = { cards: [makeCard('CLUBS', 'EIGHT'), makeCard('CLUBS', 'NINE'), makeCard('CLUBS', 'TEN')], groupType: 'PURE_SEQUENCE' as const };
    const validSet = { cards: [makeCard('HEARTS', 'KING'), makeCard('DIAMONDS', 'KING'), makeCard('CLUBS', 'KING')], groupType: 'SET' as const }; // 30 pts if not exempt
    const invalidGroup = { cards: [makeCard('SPADES', 'JACK'), makeCard('HEARTS', 'TEN')], groupType: 'INVALID' as const }; // 20 pts

    // With only 2 pure sequences in 21-card rummy, validSet is NOT exempt (30 pts + 20 pts = 50 pts)
    const penaltyWithTwoPure = calculateHandPenalty([pure1, pure2, validSet, invalidGroup], wildJoker, 120, 'RUMMY_21');
    expect(penaltyWithTwoPure).toBe(50);

    // With 3 pure sequences (pure1, pure2, plus a tunnela), validSet IS exempt! Only invalidGroup counts (20 pts)
    const tunnelaPure = { cards: [makeCard('SPADES', 'FOUR'), makeCard('SPADES', 'FOUR'), makeCard('SPADES', 'FOUR')], groupType: 'PURE_SEQUENCE' as const };
    const penaltyWithThreePure = calculateHandPenalty([pure1, pure2, tunnelaPure, validSet, invalidGroup], wildJoker, 120, 'RUMMY_21');
    expect(penaltyWithThreePure).toBe(20);
  });
});
