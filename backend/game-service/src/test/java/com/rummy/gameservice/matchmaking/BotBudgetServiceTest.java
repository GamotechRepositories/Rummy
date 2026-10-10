package com.rummy.gameservice.matchmaking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BotBudgetService Tests (Kill Switch & Loss Caps)")
class BotBudgetServiceTest {

    private BotBudgetService budgetService;

    @BeforeEach
    void setUp() {
        // default: enabled, dailyCap = 2000 (no stake limit)
        budgetService = new BotBudgetService(true, new BigDecimal("2000"));
    }

    @Test
    @DisplayName("Allows bots to join tables of any stake tier (no stake cap restriction)")
    void testAnyStakeAllowed() {
        assertThat(budgetService.canSpawnBot(100)).isTrue();
        assertThat(budgetService.canSpawnBot(500)).isTrue();
        assertThat(budgetService.canSpawnBot(5000)).isTrue();
        assertThat(budgetService.canSpawnBot(10000)).isTrue();
    }

    @Test
    @DisplayName("Kill Switch: Instantly blocks all bot spawning when toggled off")
    void testKillSwitch() {
        assertThat(budgetService.canSpawnBot(100)).isTrue();

        budgetService.setBotEnabled(false);
        assertThat(budgetService.canSpawnBot(100)).isFalse();
        assertThat(budgetService.canSpawnBot(10)).isFalse();

        budgetService.setBotEnabled(true);
        assertThat(budgetService.canSpawnBot(100)).isTrue();
    }

    @Test
    @DisplayName("Blocks bot spawning when cumulative daily net loss reaches the cap")
    void testDailyLossCapEnforcement() {
        assertThat(budgetService.canSpawnBot(100)).isTrue();

        // 1. Bot loses 1200
        budgetService.recordBotLoss(new BigDecimal("1200"));
        assertThat(budgetService.getTodayNetLoss()).isEqualByComparingTo(new BigDecimal("1200"));
        assertThat(budgetService.canSpawnBot(100)).isTrue(); // Under 2000 cap

        // 2. Bot wins 400 (reduces net house loss to 800)
        budgetService.recordBotWin(new BigDecimal("400"));
        assertThat(budgetService.getTodayNetLoss()).isEqualByComparingTo(new BigDecimal("800"));
        assertThat(budgetService.canSpawnBot(100)).isTrue();

        // 3. Bot loses another 1300 (total net loss: 2100 >= 2000)
        budgetService.recordBotLoss(new BigDecimal("1300"));
        assertThat(budgetService.getTodayNetLoss()).isEqualByComparingTo(new BigDecimal("2100"));
        assertThat(budgetService.canSpawnBot(100)).isFalse(); // Cap reached, blocked!

        // 4. Resetting restores spawning
        budgetService.resetTodayLoss();
        assertThat(budgetService.canSpawnBot(100)).isTrue();
    }
}
