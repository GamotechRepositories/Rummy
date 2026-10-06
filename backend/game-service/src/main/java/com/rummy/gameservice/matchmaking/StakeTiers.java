package com.rummy.gameservice.matchmaking;

import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.engine.rules.RummyRules;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The paid tables players can join: which variants are offered, at which entry stakes (rupees) and table sizes.
 * Points tables take the stake as the most a player can lose (80 points), so point value = stake / 80.
 */
public final class StakeTiers {

    private static final List<Integer> POINTS = List.of(4, 8, 20, 40, 80, 160, 400);
    private static final List<Integer> POOL = List.of(10, 25, 50, 100, 250);
    private static final List<Integer> DEALS = List.of(10, 25, 50, 100);
    private static final List<Integer> RUMMY_21 = List.of(25, 50, 100, 250);

    private static final Map<String, List<Integer>> BY_RULESET = Map.of(
            "POINTS_13", POINTS,
            "POOL_101", POOL,
            "POOL_201", POOL,
            "DEALS_RUMMY", DEALS,
            "DEALS_2", DEALS,
            "DEALS_3", DEALS,
            "RUMMY_21", RUMMY_21);

    private static final Set<Integer> TABLE_SIZES = Set.of(2, 6);

    private StakeTiers() {
    }

    /** The canonical ruleset id for an offered variant (aliases resolved), or empty if it is not offered. */
    public static Optional<String> offeredRuleset(String rulesetId) {
        if (rulesetId == null || rulesetId.isBlank()) {
            return Optional.empty();
        }
        return RulesetRegistry.getRuleset(rulesetId)
                .map(RummyRules::getRulesetId)
                .filter(BY_RULESET::containsKey);
    }

    public static boolean isOffered(String canonicalRulesetId, int stake, int maxPlayers) {
        List<Integer> stakes = BY_RULESET.get(canonicalRulesetId);
        return stakes != null && stakes.contains(stake) && TABLE_SIZES.contains(maxPlayers);
    }

    public static List<Integer> stakesFor(String canonicalRulesetId) {
        return BY_RULESET.getOrDefault(canonicalRulesetId, List.of());
    }
}
