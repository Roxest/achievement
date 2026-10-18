package com.pixelclub.achievement.api;

import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Public Developer API Facade for Pixelclub Achievements.
 * Custom mods call these methods to advance, query, or trigger player achievements.
 */
public final class PxcAchievementApi {
    private PxcAchievementApi() {}

    /**
     * Increments achievement progress by amount.
     * Returns true if this update triggered an achievement completion.
     */
    public static boolean increment(UUID playerUuid, String achievementId, int amount) {
        if (PxcAchievementsMod.getInstance() == null) return false;
        return PxcAchievementsMod.getInstance().getPlayerStore().increment(playerUuid, achievementId, amount);
    }

    public static boolean increment(ServerPlayer player, String achievementId, int amount) {
        return increment(player.getUUID(), achievementId, amount);
    }

    /**
     * Sets progress to the maximum of current and new value.
     */
    public static boolean setMax(UUID playerUuid, String achievementId, int value) {
        if (PxcAchievementsMod.getInstance() == null) return false;
        return PxcAchievementsMod.getInstance().getPlayerStore().setMax(playerUuid, achievementId, value);
    }

    public static boolean setMax(ServerPlayer player, String achievementId, int value) {
        return setMax(player.getUUID(), achievementId, value);
    }

    /**
     * Explicitly sets progress value.
     */
    public static boolean setProgress(UUID playerUuid, String achievementId, int value) {
        if (PxcAchievementsMod.getInstance() == null) return false;
        return PxcAchievementsMod.getInstance().getPlayerStore().setProgress(playerUuid, achievementId, value);
    }

    /**
     * Checks if player has claimed this achievement.
     */
    public static boolean isClaimed(UUID playerUuid, String achievementId) {
        if (PxcAchievementsMod.getInstance() == null) return false;
        PlayerProgress progress = PxcAchievementsMod.getInstance().getPlayerStore().getProgress(playerUuid, achievementId);
        return progress != null && progress.isClaimed();
    }

    /**
     * Gets current progress integer.
     */
    public static int getProgress(UUID playerUuid, String achievementId) {
        if (PxcAchievementsMod.getInstance() == null) return 0;
        PlayerProgress progress = PxcAchievementsMod.getInstance().getPlayerStore().getProgress(playerUuid, achievementId);
        return progress != null ? progress.getProgress() : 0;
    }

    /**
     * Gets claim state (0=LOCKED, 1=COMPLETED, 2=CLAIMED).
     */
    public static ClaimState getState(UUID playerUuid, String achievementId) {
        if (PxcAchievementsMod.getInstance() == null) return ClaimState.LOCKED;
        PlayerProgress progress = PxcAchievementsMod.getInstance().getPlayerStore().getProgress(playerUuid, achievementId);
        return progress != null ? progress.getState() : ClaimState.LOCKED;
    }
}
