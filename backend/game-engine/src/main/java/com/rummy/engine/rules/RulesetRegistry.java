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
    }

    private RulesetRegistry() {}

    public static void register(RummyRules ruleset) {
        if (ruleset != null && ruleset.getRulesetId() != null) {
            RULESETS.put(ruleset.getRulesetId().toUpperCase(), ruleset);
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
