package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.*;

/**
 * Server-authoritative 13-card Indian Rummy declaration validator.
 * Enforces the core rules:
 * 1. Total cards must equal exactly 13.
 * 2. Every card instance must be unique across all groups (no card shared).
 * 3. Minimum 2 sequences required.
 * 4. Minimum 1 pure sequence required.
 * 5. All groups must be valid sequences or sets.
 */
public final class DeclarationValidator {

    private DeclarationValidator() {}

    /**
     * Validates a declared arrangement of cards.
     *
     * @param groups   list of card groups submitted by the declaring player
     * @param cutJoker cut wild joker card
     * @return DeclarationResult containing validity, classification, and audit errors
     */
    public static DeclarationResult validate(List<CardGroup> groups, Card cutJoker) {
        if (groups == null || groups.isEmpty()) {
            return DeclarationResult.invalid(0, 0, 0, 0,
                    List.of("Declaration contains no card groups"), Collections.emptyList());
        }

        List<String> errors = new ArrayList<>();
        Set<String> seenCardInstanceIds = new HashSet<>();
        int totalCards = 0;

        for (int i = 0; i < groups.size(); i++) {
            CardGroup group = groups.get(i);
            totalCards += group.size();
            for (CardInstance card : group.getCards()) {
                if (!seenCardInstanceIds.add(card.getInstanceId())) {
                    errors.add(String.format("Duplicate card instance %s found across groups", card.getInstanceId()));
                }
            }
        }

        if (totalCards != 13) {
            errors.add(String.format("Declaration must contain exactly 13 cards, but found %d cards", totalCards));
        }

        int pureSequences = 0;
        int impureSequences = 0;
        int validSets = 0;
        int invalidGroups = 0;
        List<DeclarationResult.GroupClassification> classifications = new ArrayList<>(groups.size());

        for (int i = 0; i < groups.size(); i++) {
            CardGroup group = groups.get(i);
            GroupType type = classifyGroup(group, cutJoker);
            classifications.add(new DeclarationResult.GroupClassification(group, type));

            switch (type) {
                case PURE_SEQUENCE -> pureSequences++;
                case IMPURE_SEQUENCE -> impureSequences++;
                case SET -> validSets++;
                case INVALID -> {
                    invalidGroups++;
                    errors.add(String.format("Group %d %s is neither a valid sequence nor a valid set", i + 1, group.getCards()));
                }
            }
        }

        int totalSequences = pureSequences + impureSequences;
        if (pureSequences < 1) {
            errors.add("Declaration must have at least one pure sequence without jokers");
        }
        if (totalSequences < 2) {
            errors.add(String.format("Declaration requires at least 2 sequences, but only %d found", totalSequences));
        }

        if (errors.isEmpty() && invalidGroups == 0) {
            return DeclarationResult.valid(pureSequences, impureSequences, validSets, classifications);
        } else {
            return DeclarationResult.invalid(pureSequences, impureSequences, validSets, invalidGroups, errors, classifications);
        }
    }

    /**
     * Classifies a single group:
     * - SequenceValidator first (returns PURE_SEQUENCE or IMPURE_SEQUENCE)
     * - SetValidator second (returns SET)
     * - Otherwise INVALID
     */
    public static GroupType classifyGroup(CardGroup group, Card cutJoker) {
        GroupType seqType = SequenceValidator.validate(group, cutJoker);
        if (seqType != GroupType.INVALID) {
            return seqType;
        }

        return SetValidator.validate(group, cutJoker);
    }
}
