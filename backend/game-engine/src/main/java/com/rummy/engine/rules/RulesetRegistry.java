package com.rummy.engine.rules;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry & Factory for resolving game variants to their respective authoritative rulesets.
 */
public final class RulesetRegistry {

    private static final Map<String, RummyRules> RULESETS = new ConcurrentHashMap<>();

    static {
        register(new PointsRummyRules());
        register(new Pool101Rules());
        register(new Pool201Rules());
        register(new DealsRummyRules());
        register(new TwentyOneCardRummyRules());
        register(new GinRummyRules());
        register(new Rummy500Rules());
        register(new KalookiRules());
        register(new CanastaRules());

        registerAlias("INDIAN_POINTS", "POINTS_13");
        registerAlias("POINTS", "POINTS_13");
        registerAlias("DEALS_2", "DEALS_RUMMY");
        registerAlias("DEALS", "DEALS_RUMMY");
        registerAlias("21_CARD", "RUMMY_21");
        registerAlias("TWENTY_ONE", "RUMMY_21");
    }

    private RulesetRegistry() {}

    public static void register(RummyRules ruleset) {
        if (ruleset != null && ruleset.getRulesetId() != null) {
            RULESETS.put(ruleset.getRulesetId().toUpperCase(), ruleset);
        }
    }

    public static void registerAlias(String alias, String targetRulesetId) {
        if (alias != null && targetRulesetId != null) {
            RummyRules target = RULESETS.get(targetRulesetId.toUpperCase());
            if (target != null) {
                RULESETS.put(alias.toUpperCase(), target);
            }
        }
    }

    public static Optional<RummyRules> getRuleset(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return Optional.ofNullable(RULESETS.get("POINTS_13"));
        }
        return Optional.ofNullable(RULESETS.get(variantId.toUpperCase()));
    }

    public static RummyRules requireRuleset(String variantId) {
        return getRuleset(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or unsupported variant: " + variantId));
    }

    public static int getRegisteredRulesetCount() {
        return RULESETS.size();
    }
}
