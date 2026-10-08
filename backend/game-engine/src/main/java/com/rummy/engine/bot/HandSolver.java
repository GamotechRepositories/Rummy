package com.rummy.engine.bot;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import com.rummy.engine.rules.CardGroup;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact arrangement search over one hand of up to {@link #MAX_CARDS} cards. Every sub-hand (the hand less
 * any one card, say) is a bitmask over the same cards and shares the solver's tables, so weighing all
 * fourteen possible discards costs little more than weighing one.
 */
final class HandSolver {

    static final int MAX_CARDS = 16;
    /** Charged to a hand with no pure sequence: nothing else counts toward a declaration without one. */
    static final int NO_PURE_COST = 20;
    /** Charged to a hand with fewer than two sequences, the other must-have of a declaration. */
    static final int NO_SECOND_SEQUENCE_COST = 10;

    private static final int INFEASIBLE = Integer.MAX_VALUE / 4;
    private static final byte INVALID = 0, SET = 1, IMPURE = 2, PURE = 3;

    private final List<CardInstance> cards;
    private final int full;
    private final int[] cardPoints;
    /** Valid melds grouped by their lowest card, so each search step only tries melds it can start with. */
    private final int[][] meldsByLowest;
    private final byte[][] meldTypesByLowest;
    /** Memoised {@link #cost} plus one, so the zeroed array needs no filling. */
    private final int[] costMemo;
    private byte[] declareMemo;

    static boolean fits(List<CardInstance> hand) {
        return hand != null && hand.size() <= MAX_CARDS;
    }

    HandSolver(List<CardInstance> hand, Card cutJoker) {
        if (!fits(hand)) {
            throw new IllegalArgumentException("HandSolver supports at most " + MAX_CARDS + " cards");
        }
        this.cards = List.copyOf(hand);
        int n = cards.size();
        this.full = (1 << n) - 1;
        this.cardPoints = new int[n];

        int jokerMask = 0;
        int[] suitMask = new int[Suit.values().length];
        int[] rankMask = new int[Rank.values().length];
        for (int i = 0; i < n; i++) {
            CardInstance c = cards.get(i);
            cardPoints[i] = c.getCard().points(cutJoker);
            if (c.isPrintedJoker() || c.getCard().isWildJoker(cutJoker)) {
                jokerMask |= 1 << i;
            }
            if (!c.isPrintedJoker()) {
                suitMask[c.getCard().suit().ordinal()] |= 1 << i;
                rankMask[c.getCard().rank().ordinal()] |= 1 << i;
            }
        }

        // A meld's natural cards all share a suit (sequence) or a rank (set), so only submasks of one suit or
        // one rank plus the jokers can be melds.
        Set<Integer> candidates = new HashSet<>();
        for (int s : suitMask) {
            addSubmasks(s | jokerMask, candidates);
        }
        for (int r : rankMask) {
            addSubmasks(r | jokerMask, candidates);
        }

        List<List<int[]>> byLowest = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byLowest.add(new ArrayList<>());
        }
        List<CardInstance> members = new ArrayList<>(n);
        for (int mask : candidates) {
            members.clear();
            for (int m = mask; m != 0; m &= m - 1) {
                members.add(cards.get(Integer.numberOfTrailingZeros(m)));
            }
            byte t = switch (HandEvaluator.classifyCandidateGroup(CardGroup.of(members), cutJoker)) {
                case PURE_SEQUENCE -> PURE;
                case IMPURE_SEQUENCE -> IMPURE;
                case SET -> SET;
                default -> INVALID;
            };
            if (t != INVALID) {
                byLowest.get(Integer.numberOfTrailingZeros(mask)).add(new int[] {mask, t});
            }
        }
        this.meldsByLowest = new int[n][];
        this.meldTypesByLowest = new byte[n][];
        for (int i = 0; i < n; i++) {
            List<int[]> melds = byLowest.get(i);
            meldsByLowest[i] = new int[melds.size()];
            meldTypesByLowest[i] = new byte[melds.size()];
            for (int k = 0; k < melds.size(); k++) {
                meldsByLowest[i][k] = melds.get(k)[0];
                meldTypesByLowest[i][k] = (byte) melds.get(k)[1];
            }
        }
        this.costMemo = new int[(full + 1) * 6];
    }

    private static void addSubmasks(int group, Set<Integer> out) {
        if (Integer.bitCount(group) < 3) {
            return;
        }
        for (int sub = group; sub != 0; sub = (sub - 1) & group) {
            if (Integer.bitCount(sub) >= 3) {
                out.add(sub);
            }
        }
    }

    List<CardInstance> cards() {
        return cards;
    }

    int fullMask() {
        return full;
    }

    int without(int index) {
        return full & ~(1 << index);
    }

    /**
     * How far the cards in {@code mask} are from a declaration: the fewest points left outside melds over
     * every arrangement, plus {@link #NO_PURE_COST} / {@link #NO_SECOND_SEQUENCE_COST} when the best
     * arrangement still lacks a pure sequence or a second sequence. Lower is better; 0 means declarable.
     */
    int cost(int mask) {
        return cost(mask, 0, 0);
    }

    private int cost(int mask, int pure, int sequences) {
        if (mask == 0) {
            return (pure == 1 ? 0 : NO_PURE_COST) + (sequences >= 2 ? 0 : NO_SECOND_SEQUENCE_COST);
        }
        int key = mask * 6 + pure * 3 + sequences;
        if (costMemo[key] > 0) {
            return costMemo[key] - 1;
        }
        int low = Integer.numberOfTrailingZeros(mask);
        int best = cardPoints[low] + cost(mask ^ (1 << low), pure, sequences);
        int[] melds = meldsByLowest[low];
        byte[] types = meldTypesByLowest[low];
        for (int k = 0; k < melds.length; k++) {
            int meld = melds[k];
            if ((meld & ~mask) != 0) {
                continue;
            }
            byte t = types[k];
            best = Math.min(best, cost(mask ^ meld, t == PURE ? 1 : pure, t >= IMPURE ? Math.min(2, sequences + 1) : sequences));
        }
        costMemo[key] = best + 1;
        return best;
    }

    /**
     * Groups covering every card in {@code mask} with at least one pure sequence and two sequences, or null
     * when the cards cannot be arranged that way.
     */
    List<CardGroup> declaration(int mask) {
        if (!declarable(mask, 0, 0)) {
            return null;
        }
        List<CardGroup> groups = new ArrayList<>();
        int pure = 0;
        int sequences = 0;
        while (mask != 0) {
            int low = Integer.numberOfTrailingZeros(mask);
            int[] melds = meldsByLowest[low];
            for (int k = 0; k < melds.length; k++) {
                int meld = melds[k];
                if ((meld & ~mask) != 0) {
                    continue;
                }
                byte t = meldTypesByLowest[low][k];
                int nextPure = t == PURE ? 1 : pure;
                int nextSequences = t >= IMPURE ? Math.min(2, sequences + 1) : sequences;
                if (declarable(mask ^ meld, nextPure, nextSequences)) {
                    groups.add(group(meld));
                    mask ^= meld;
                    pure = nextPure;
                    sequences = nextSequences;
                    break;
                }
            }
        }
        return groups;
    }

    private boolean declarable(int mask, int pure, int sequences) {
        if (mask == 0) {
            return pure == 1 && sequences >= 2;
        }
        if (declareMemo == null) {
            declareMemo = new byte[(full + 1) * 6];
        }
        int key = mask * 6 + pure * 3 + sequences;
        if (declareMemo[key] != 0) {
            return declareMemo[key] == 1;
        }
        boolean ok = false;
        int low = Integer.numberOfTrailingZeros(mask);
        int[] melds = meldsByLowest[low];
        for (int k = 0; k < melds.length; k++) {
            int meld = melds[k];
            if ((meld & ~mask) != 0) {
                continue;
            }
            byte t = meldTypesByLowest[low][k];
            if (declarable(mask ^ meld, t == PURE ? 1 : pure, t >= IMPURE ? Math.min(2, sequences + 1) : sequences)) {
                ok = true;
                break;
            }
        }
        declareMemo[key] = (byte) (ok ? 1 : 2);
        return ok;
    }

    private CardGroup group(int meld) {
        List<CardInstance> members = new ArrayList<>(Integer.bitCount(meld));
        for (int m = meld; m != 0; m &= m - 1) {
            members.add(cards.get(Integer.numberOfTrailingZeros(m)));
        }
        return CardGroup.of(members);
    }
}
