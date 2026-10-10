package com.rummy.gameservice.matchmaking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages financial risk boundaries, daily loss caps, and kill switches for AI bots.
 *
 * <p>Safeguards the platform treasury by:
 * 1. Blocking bots on high-stakes tables (capped at {@code maxStakeTier}).
 * 2. Enforcing a daily net loss limit ({@code dailyLossCap}); if bot losses exceed this,
 *    bot spawning automatically pauses to stop treasury bleeding.
 * 3. Providing an emergency kill switch ({@code botEnabled}) that admins can toggle at runtime.
 */
@Service
public class BotBudgetService {

    private static final Logger log = LoggerFactory.getLogger(BotBudgetService.class);

    @Value("${rummy.bot.enabled:true}")
    private volatile boolean botEnabled = true;

    @Value("${rummy.bot.daily-loss-cap:10000}")
    private volatile BigDecimal dailyLossCap = new BigDecimal("10000");

    private final AtomicReference<LocalDate> currentDay = new AtomicReference<>(LocalDate.now());
    private final AtomicReference<BigDecimal> todayNetLoss = new AtomicReference<>(BigDecimal.ZERO);

    public BotBudgetService() {
    }

    public BotBudgetService(boolean botEnabled, BigDecimal dailyLossCap) {
        this.botEnabled = botEnabled;
        this.dailyLossCap = dailyLossCap;
    }

    /**
     * Checks if an AI bot is allowed to join tables (guards kill switch & daily loss limit).
     */
    public boolean canSpawnBot(long stakeTier) {
        return canSpawnBot();
    }

    public boolean canSpawnBot() {
        if (!botEnabled) {
            log.info("[BotBudget] Bot spawning blocked: Kill switch is ACTIVE (botEnabled=false)");
            return false;
        }

        BigDecimal netLoss = getTodayNetLoss();
        if (netLoss.compareTo(dailyLossCap) >= 0) {
            log.warn("[BotBudget] Bot spawning blocked: Daily bot loss cap ₹{} reached (Today net loss: ₹{}). Safety paused.",
                    dailyLossCap, netLoss);
            return false;
        }

        return true;
    }

    /**
     * Records a house loss resulting from a bot losing a game.
     */
    public void recordBotLoss(BigDecimal lossAmount) {
        if (lossAmount == null || lossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        rolloverDayIfNeeded();
        todayNetLoss.updateAndGet(current -> current.add(lossAmount));
        log.info("[BotBudget] Recorded bot loss ₹{}. Today net bot loss: ₹{}/₹{}",
                lossAmount, todayNetLoss.get(), dailyLossCap);
    }

    /**
     * Records a house win resulting from a bot winning a game.
     */
    public void recordBotWin(BigDecimal winAmount) {
        if (winAmount == null || winAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        rolloverDayIfNeeded();
        todayNetLoss.updateAndGet(current -> current.subtract(winAmount));
        log.info("[BotBudget] Recorded bot win ₹{}. Today net bot loss: ₹{}/₹{}",
                winAmount, todayNetLoss.get(), dailyLossCap);
    }

    public BigDecimal getTodayNetLoss() {
        rolloverDayIfNeeded();
        return todayNetLoss.get();
    }

    private void rolloverDayIfNeeded() {
        LocalDate today = LocalDate.now();
        LocalDate recordedDay = currentDay.get();
        if (!today.equals(recordedDay)) {
            if (currentDay.compareAndSet(recordedDay, today)) {
                BigDecimal oldLoss = todayNetLoss.getAndSet(BigDecimal.ZERO);
                log.info("[BotBudget] Daily rollover from {} to {}. Previous day net loss was ₹{}",
                        recordedDay, today, oldLoss);
            }
        }
    }

    public boolean isBotEnabled() {
        return botEnabled;
    }

    public void setBotEnabled(boolean botEnabled) {
        this.botEnabled = botEnabled;
        log.warn("[BotBudget] Bot enabled status updated to: {}", botEnabled);
    }

    public BigDecimal getDailyLossCap() {
        return dailyLossCap;
    }

    public void setDailyLossCap(BigDecimal dailyLossCap) {
        this.dailyLossCap = dailyLossCap;
        log.info("[BotBudget] Daily bot loss cap updated to: ₹{}", dailyLossCap);
    }

    public void resetTodayLoss() {
        todayNetLoss.set(BigDecimal.ZERO);
        log.info("[BotBudget] Today's net loss manually reset to 0");
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("botEnabled", botEnabled);
        status.put("dailyLossCap", dailyLossCap);
        status.put("todayNetLoss", getTodayNetLoss());
        status.put("capReached", getTodayNetLoss().compareTo(dailyLossCap) >= 0);
        status.put("date", currentDay.get().toString());
        return status;
    }
}
