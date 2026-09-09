import { describe, it, expect } from 'vitest';
import {
  validatePureSequence,
  validateImpureSequence,
  validateSet,
  evaluateCardGroup,
  checkOverallDeclaration,
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

  it('rejects pure sequence containing jokers or suit mismatch', () => {
    // 6-7(wild)-8 of Spades has wild joker 7 of Spades
    const withWild = [makeCard('SPADES', 'SIX'), makeCard('SPADES', 'SEVEN'), makeCard('SPADES', 'EIGHT')];
    expect(validatePureSequence(withWild, wildJoker)).toBe(false);

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
  });

  it('validates sets with distinct suits and rejects duplicate suits', () => {
    // 8H, 8D, 8C
    const validSet = [makeCard('HEARTS', 'EIGHT'), makeCard('DIAMONDS', 'EIGHT'), makeCard('CLUBS', 'EIGHT')];
    expect(validateSet(validSet, wildJoker)).toBe(true);
    expect(evaluateCardGroup(validSet, wildJoker)).toBe('SET');

    // Duplicate suits from 2 decks: 8H, 8H, 8D is invalid
    const dupSuitSet = [makeCard('HEARTS', 'EIGHT'), makeCard('HEARTS', 'EIGHT'), makeCard('DIAMONDS', 'EIGHT')];
    expect(validateSet(dupSuitSet, wildJoker)).toBe(false);
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
  });
});
