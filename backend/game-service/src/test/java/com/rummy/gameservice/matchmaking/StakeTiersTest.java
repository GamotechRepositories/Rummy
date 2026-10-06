package com.rummy.gameservice.matchmaking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StakeTiersTest {

    @Test
    void onlyOfferedVariantsStakesAndTableSizesAreAccepted() {
        assertThat(StakeTiers.offeredRuleset("POINTS")).contains("POINTS_13");
        assertThat(StakeTiers.offeredRuleset("GIN_RUMMY")).isEmpty();
        assertThat(StakeTiers.offeredRuleset("NOPE")).isEmpty();

        assertThat(StakeTiers.isOffered("POINTS_13", 8, 2)).isTrue();
        assertThat(StakeTiers.isOffered("POINTS_13", 400, 6)).isTrue();
        assertThat(StakeTiers.isOffered("POINTS_13", 0, 2)).isFalse();
        assertThat(StakeTiers.isOffered("POINTS_13", 7, 2)).isFalse();
        assertThat(StakeTiers.isOffered("POINTS_13", 8, 4)).isFalse();
        assertThat(StakeTiers.isOffered("POOL_101", 25, 6)).isTrue();
        assertThat(StakeTiers.isOffered("DEALS_2", 250, 2)).isFalse();
    }
}
