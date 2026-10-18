package com.pixelclub.achievement.watchdog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Invariant monitor for the Pixelclub achievement system.
 * Detects illegal state transitions, duplicate claim spam, and telemetry anomalies.
 */
public final class AchievementWatchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Watchdog");
    private static final AtomicLong DOUBLE_CLAIM_ATTEMPTS = new AtomicLong(0);
    private static final AtomicLong ILLEGAL_PROGRESS_ATTEMPTS = new AtomicLong(0);

    private AchievementWatchdog() {}

    /**
     * Alerts when a player attempts to claim an achievement that is not in COMPLETED state.
     */
    public static void alertDuplicateClaim(UUID playerUuid, String achievementId, int currentState) {
        long total = DOUBLE_CLAIM_ATTEMPTS.incrementAndGet();
        LOGGER.warn("[INVARIANT BREACH] Rejected duplicate/invalid claim attempt #{}: player={}, achievement={}, state={}",
                total, playerUuid, achievementId, currentState);
    }

    /**
     * Alerts when an anomaly attempts to decrease progress non-administratively.
     */
    public static void alertNonMonotonicProgress(UUID playerUuid, String achievementId, int current, int proposed) {
        long total = ILLEGAL_PROGRESS_ATTEMPTS.incrementAndGet();
        LOGGER.warn("[INVARIANT BREACH] Blocked non-monotonic progress decrease #{}: player={}, achievement={}, current={}, proposed={}",
                total, playerUuid, achievementId, current, proposed);
    }

    public static long getDoubleClaimAttempts() {
        return DOUBLE_CLAIM_ATTEMPTS.get();
    }

    public static long getIllegalProgressAttempts() {
        return ILLEGAL_PROGRESS_ATTEMPTS.get();
    }
}
