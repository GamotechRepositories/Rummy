package com.rummy.gameservice.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PoolSplitTest {

    @Test
    @DisplayName("Drops left: how many first drops a player can still take without reaching the elimination score")
    void dropsRemaining() {
        assertThat(PoolSplit.dropsRemaining(0, 101, 20)).isEqualTo(5);
        assertThat(PoolSplit.dropsRemaining(79, 101, 20)).isEqualTo(1);
        assertThat(PoolSplit.dropsRemaining(80, 101, 20)).isEqualTo(1);
        assertThat(PoolSplit.dropsRemaining(81, 101, 20)).isEqualTo(0);
        assertThat(PoolSplit.dropsRemaining(0, 201, 25)).isEqualTo(8);
        assertThat(PoolSplit.dropsRemaining(176, 201, 25)).isEqualTo(0);
    }

    @Test
    @DisplayName("Split allowed: 2-3 left on a table of 4+, 2 left on a table of 3, never on a 2-player table")
    void tableSize() {
        assertThat(PoolSplit.tableSizeAllows(6, 3)).isTrue();
        assertThat(PoolSplit.tableSizeAllows(6, 2)).isTrue();
        assertThat(PoolSplit.tableSizeAllows(6, 4)).isFalse();
        assertThat(PoolSplit.tableSizeAllows(3, 2)).isTrue();
        assertThat(PoolSplit.tableSizeAllows(3, 3)).isFalse();
        assertThat(PoolSplit.tableSizeAllows(2, 2)).isFalse();
    }

    @Test
    @DisplayName("Extra drops are paid at one entry fee each, the rest is shared equally, to the paisa")
    void payoutsFollowDrops() {
        Map<String, Integer> drops = new LinkedHashMap<>();
        drops.put("A", 3);
        drops.put("B", 1);
        drops.put("C", 0);

        Map<String, BigDecimal> shares = PoolSplit.payouts(drops, BigDecimal.valueOf(25), new BigDecimal("150.00")).orElseThrow();

        assertThat(shares.get("A")).isEqualByComparingTo("91.67");
        assertThat(shares.get("B")).isEqualByComparingTo("41.67");
        assertThat(shares.get("C")).isEqualByComparingTo("16.66");
        assertThat(shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("No split when less than one entry fee would be left to share")
    void tooLittleLeft() {
        Map<String, Integer> drops = new LinkedHashMap<>();
        drops.put("A", 5);
        drops.put("B", 0);
        assertThat(PoolSplit.payouts(drops, BigDecimal.valueOf(25), new BigDecimal("140.00"))).isEmpty();
        assertThat(PoolSplit.payouts(drops, BigDecimal.valueOf(25), new BigDecimal("150.00"))).isPresent();
    }

    @Test
    @DisplayName("Prize after the 15% fee matches settlement")
    void netPrize() {
        assertThat(PoolSplit.netPrize(BigDecimal.valueOf(100))).isEqualByComparingTo("85.00");
        assertThat(PoolSplit.netPrize(BigDecimal.valueOf(30))).isEqualByComparingTo("25.50");
    }
}
