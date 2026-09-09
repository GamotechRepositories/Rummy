package com.rummy.engine.rules;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Audit result returned after validating a player declaration.
 */
public record DeclarationResult(
        boolean isValid,
        int pureSequencesCount,
        int impureSequencesCount,
        int validSetsCount,
        int invalidGroupsCount,
        List<String> errors,
        List<GroupClassification> classifiedGroups
) implements Serializable {

    public record GroupClassification(
            CardGroup group,
            GroupType type
    ) implements Serializable {}

    public static DeclarationResult valid(int pure, int impure, int sets, List<GroupClassification> groups) {
        return new DeclarationResult(true, pure, impure, sets, 0, Collections.emptyList(), groups);
    }

    public static DeclarationResult invalid(int pure, int impure, int sets, int invalidCount,
                                            List<String> errors, List<GroupClassification> groups) {
        return new DeclarationResult(false, pure, impure, sets, invalidCount, errors, groups);
    }
}
