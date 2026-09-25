import type { CardInstance, GroupValidationType, Rank, Suit } from '../types/game';

const RANK_VALUES: Record<Rank, number> = {
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
  ACE: 14,
  JOKER: 0,
};

export function getCardScore(card: CardInstance, wildJoker: CardInstance | null): number {
  if (isJoker(card, wildJoker)) {
    return 0;
  }
  switch (card.rank) {
    case 'ACE':
    case 'KING':
    case 'QUEEN':
    case 'JACK':
    case 'TEN':
      return 10;
    case 'NINE':
      return 9;
    case 'EIGHT':
      return 8;
    case 'SEVEN':
      return 7;
    case 'SIX':
      return 6;
    case 'FIVE':
      return 5;
    case 'FOUR':
      return 4;
    case 'THREE':
      return 3;
    case 'TWO':
      return 2;
    default:
      return 0;
  }
}

export function isJoker(card: CardInstance, wildJoker: CardInstance | null): boolean {
  if (card.printedJoker || card.rank === 'JOKER') {
    return true;
  }
  if (wildJoker && card.rank === wildJoker.rank) {
    return true;
  }
  return false;
}

export function isTunnela(cards: CardInstance[]): boolean {
  if (cards.length !== 3) return false;
  const allPrintedJokers = cards.every(c => c.printedJoker || c.rank === 'JOKER');
  if (allPrintedJokers) return true;
  if (cards.some(c => c.printedJoker || c.rank === 'JOKER')) return false;
  const first = cards[0];
  return cards.every(c => c.suit === first.suit && c.rank === first.rank);
}

export function validatePureSequence(cards: CardInstance[], wildJoker: CardInstance | null): boolean {
  if (cards.length < 3) return false;

  // In 21-Card Rummy, a Tunnela (3 identical cards of same suit and rank, or 3 printed jokers) is a Pure Sequence
  if (isTunnela(cards)) {
    return true;
  }

  // Pure sequences cannot use wild jokers or printed jokers as substitutes
  for (const c of cards) {
    if (isJoker(c, wildJoker)) return false;
  }

  const suit = cards[0].suit;
  if (cards.some(c => c.suit !== suit)) return false;

  const ranks = cards.map(c => RANK_VALUES[c.rank]).sort((a, b) => a - b);
  // Check duplicates
  for (let i = 0; i < ranks.length - 1; i++) {
    if (ranks[i] === ranks[i + 1]) return false;
  }

  // Check Ace-high sequence (e.g. 11, 12, 13, 14)
  let isAceHighSeq = true;
  for (let i = 0; i < ranks.length - 1; i++) {
    if (ranks[i + 1] - ranks[i] !== 1) {
      isAceHighSeq = false;
      break;
    }
  }
  if (isAceHighSeq) return true;

  // Check Ace-low sequence (A-2-3... -> 1, 2, 3...)
  if (cards.some(c => c.rank === 'ACE')) {
    const aceLowRanks = cards
      .map(c => (c.rank === 'ACE' ? 1 : RANK_VALUES[c.rank]))
      .sort((a, b) => a - b);

    let isAceLowSeq = true;
    for (let i = 0; i < aceLowRanks.length - 1; i++) {
      if (aceLowRanks[i + 1] - aceLowRanks[i] !== 1) {
        isAceLowSeq = false;
        break;
      }
    }
    if (isAceLowSeq) return true;
  }

  return false;
}

export function validateImpureSequence(cards: CardInstance[], wildJoker: CardInstance | null): boolean {
  if (cards.length < 3) return false;

  const naturals: CardInstance[] = [];
  let jokerCount = 0;

  for (const c of cards) {
    if (isJoker(c, wildJoker)) {
      jokerCount++;
    } else {
      naturals.push(c);
    }
  }

  // Must have at least 1 natural card and at least 1 joker to be an impure sequence
  if (naturals.length === 0 || jokerCount === 0) return false;

  const suit = naturals[0].suit;
  if (naturals.some(c => c.suit !== suit)) return false;

  // Check Ace-high orientation
  if (checkSequenceWithJokers(naturals.map(c => RANK_VALUES[c.rank]), jokerCount)) {
    return true;
  }

  // Check Ace-low orientation
  if (naturals.some(c => c.rank === 'ACE')) {
    const aceLow = naturals.map(c => (c.rank === 'ACE' ? 1 : RANK_VALUES[c.rank]));
    if (checkSequenceWithJokers(aceLow, jokerCount)) {
      return true;
    }
  }

  return false;
}

function checkSequenceWithJokers(rawRanks: number[], jokers: number): boolean {
  const sorted = [...rawRanks].sort((a, b) => a - b);
  // Any duplicate natural ranks fail
  for (let i = 0; i < sorted.length - 1; i++) {
    if (sorted[i] === sorted[i + 1]) return false;
  }

  const min = sorted[0];
  const max = sorted[sorted.length - 1];
  const span = max - min + 1;
  const missingCards = span - sorted.length;

  if (missingCards > jokers) return false;

  const remainingJokers = jokers - missingCards;
  // If span + remainingJokers <= 14, we can pad at start or end
  return span + remainingJokers <= 14;
}

export function validateSet(cards: CardInstance[], wildJoker: CardInstance | null): boolean {
  if (cards.length < 3 || cards.length > 4) return false;

  const naturals: CardInstance[] = [];
  for (const c of cards) {
    if (!isJoker(c, wildJoker)) {
      naturals.push(c);
    }
  }

  if (naturals.length < 2) return false;

  const targetRank = naturals[0].rank;
  if (naturals.some(c => c.rank !== targetRank)) return false;

  // Naturals must have unique suits (no 2 spades of same rank in a set)
  const suits = new Set<Suit>();
  for (const n of naturals) {
    if (suits.has(n.suit)) return false;
    suits.add(n.suit);
  }

  return true;
}

export function evaluateCardGroup(cards: CardInstance[], wildJoker: CardInstance | null): GroupValidationType {
  if (validatePureSequence(cards, wildJoker)) {
    return 'PURE_SEQUENCE';
  }
  if (validateImpureSequence(cards, wildJoker)) {
    return 'IMPURE_SEQUENCE';
  }
  if (validateSet(cards, wildJoker)) {
    return 'SET';
  }
  return 'INVALID';
}

export function checkOverallDeclaration(
  groups: { cards: CardInstance[] }[],
  wildJoker: CardInstance | null,
  rulesetId?: string
): { isValid: boolean; reason?: string } {
  const isRummy21 = rulesetId?.includes('21') || rulesetId === 'RUMMY_21';
  const minPure = isRummy21 ? 3 : 1;
  const minTotal = isRummy21 ? 3 : 2;

  let pureSeqCount = 0;
  let totalSeqCount = 0;
  let allGroupsValid = true;

  for (const g of groups) {
    const type = evaluateCardGroup(g.cards, wildJoker);
    if (type === 'PURE_SEQUENCE') {
      pureSeqCount++;
      totalSeqCount++;
    } else if (type === 'IMPURE_SEQUENCE') {
      totalSeqCount++;
    } else if (type === 'SET') {
      // Valid set
    } else {
      allGroupsValid = false;
    }
  }

  if (pureSeqCount < minPure) {
    return {
      isValid: false,
      reason: `Requires at least ${minPure} Pure Sequence${minPure > 1 ? 's or Tunnelas' : ' without jokers'}`,
    };
  }
  if (totalSeqCount < minTotal) {
    return { isValid: false, reason: `Requires at least ${minTotal} Sequences (found ${totalSeqCount})` };
  }
  if (!allGroupsValid) {
    return { isValid: false, reason: 'All cards must belong to a valid sequence, tunnela, or set' };
  }

  return { isValid: true };
}

export function calculateHandPenalty(
  groups: { cards: CardInstance[]; groupType?: GroupValidationType }[],
  wildJoker: CardInstance | null,
  maxPenalty = 80,
  rulesetId?: string
): number {
  if (!groups || groups.length === 0) return 0;
  const isRummy21 = rulesetId?.includes('21') || rulesetId === 'RUMMY_21';
  const effectiveMaxPenalty = isRummy21 ? 120 : maxPenalty;

  let pureSeqCount = 0;
  let totalSeqCount = 0;
  const pureGroups: typeof groups = [];
  const invalidGroups: typeof groups = [];

  for (const g of groups) {
    const type = g.groupType ?? evaluateCardGroup(g.cards, wildJoker);
    if (type === 'PURE_SEQUENCE') {
      pureSeqCount++;
      totalSeqCount++;
      pureGroups.push(g);
    } else if (type === 'IMPURE_SEQUENCE') {
      totalSeqCount++;
    } else if (type === 'INVALID') {
      invalidGroups.push(g);
    }
  }

  // CASE 1: No pure sequence -> all ungrouped cards count (cap effectiveMaxPenalty)
  if (pureSeqCount === 0) {
    let total = 0;
    for (const g of groups) {
      for (const c of g.cards) {
        total += getCardScore(c, wildJoker);
      }
    }
    return Math.min(total, effectiveMaxPenalty);
  }

  // CASE 2 (21-Card): < 3 pure sequences -> ONLY pure sequences exempt, rest count!
  if (isRummy21 && pureSeqCount < 3) {
    let total = 0;
    for (const g of groups) {
      if (!pureGroups.includes(g)) {
        for (const c of g.cards) {
          total += getCardScore(c, wildJoker);
        }
      }
    }
    return Math.min(total, effectiveMaxPenalty);
  }

  // CASE 2 (13-Card): 1 pure sequence, but less than 2 sequences
  // Pure sequence is exempt (0 pts); all other cards count (cap 80)
  if (!isRummy21 && totalSeqCount < 2) {
    let total = 0;
    for (const g of groups) {
      if (!pureGroups.includes(g)) {
        for (const c of g.cards) {
          total += getCardScore(c, wildJoker);
        }
      }
    }
    return Math.min(total, effectiveMaxPenalty);
  }

  // CASE 3: All valid sequences and sets are exempt (0 pts); only cards in invalid groups count
  let total = 0;
  for (const g of invalidGroups) {
    for (const c of g.cards) {
      total += getCardScore(c, wildJoker);
    }
  }
  return Math.min(total, effectiveMaxPenalty);
}
