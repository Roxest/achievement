package com.pixelclub.achievement.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.pixelclub.achievement.watchdog.AchievementWatchdog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe JSON player storage and retroactive synchronization engine.
 * Writes each player's progress atomically to config/pxc-achievements/players/<UUID>.json.
 */
public class PlayerStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Store");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, PlayerProgress>>() {}.getType();

    private final Path storageDir;
    private final AchievementRegistry registry;
    private final Map<UUID, Map<String, PlayerProgress>> cache = new ConcurrentHashMap<>();

    public PlayerStore(Path storageDir, AchievementRegistry registry) {
        this.storageDir = storageDir;
        this.registry = registry;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create player achievement storage directory: {}", storageDir, e);
        }
    }

    public Map<String, PlayerProgress> getPlayerData(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadFromDisk);
    }

    public PlayerProgress getProgress(UUID uuid, String achievementId) {
        Map<String, PlayerProgress> map = getPlayerData(uuid);
        return map.computeIfAbsent(achievementId, k -> new PlayerProgress());
    }

    /**
     * Atomically increments progress by amount.
     */
    public boolean increment(UUID uuid, String achievementId, int amount) {
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        boolean newlyCompleted = progress.addProgress(amount, ach.getTarget());
        saveToDiskAsync(uuid);
        return newlyCompleted;
    }

    /**
     * Atomically sets progress if greater than current value.
     */
    public boolean setMax(UUID uuid, String achievementId, int value) {
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        boolean newlyCompleted = progress.setMaxProgress(value, ach.getTarget());
        saveToDiskAsync(uuid);
        return newlyCompleted;
    }

    /**
     * Administrative override for progress.
     */
    public boolean setProgress(UUID uuid, String achievementId, int value) {
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        boolean newlyCompleted = progress.setProgressExplicit(value, ach.getTarget());
        saveToDiskAsync(uuid);
        return newlyCompleted;
    }

    /**
     * Claims an achievement atomically (COMPLETED -> CLAIMED).
     */
    public boolean claim(UUID uuid, String achievementId) {
        PlayerProgress progress = getProgress(uuid, achievementId);
        if (progress.tryClaim()) {
            saveToDiskAsync(uuid);
            return true;
        } else {
            AchievementWatchdog.alertDuplicateClaim(uuid, achievementId, progress.getState().getId());
            return false;
        }
    }

    /**
     * Resets progress for an achievement.
     */
    public void reset(UUID uuid, String achievementId) {
        PlayerProgress progress = getProgress(uuid, achievementId);
        progress.reset();
        saveToDiskAsync(uuid);
    }

    /**
     * Resets all achievements for a player.
     */
    public void resetAll(UUID uuid) {
        Map<String, PlayerProgress> map = getPlayerData(uuid);
        map.clear();
        saveToDiskAsync(uuid);
    }

    /**
     * Retroactively synchronizes progress from vanilla statistics and player data upon join.
     */
    public int performRetroactiveSync(ServerPlayer player) {
        UUID uuid = player.getUUID();
        int updatedCount = 0;

        // 1. Playtime (20 ticks = 1 sec, 72000 ticks = 1 hour)
        int ticks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        int hours = ticks / 72000;
        if (setMax(uuid, "daily_time_served", Math.min(hours, 100))) updatedCount++;
        if (setMax(uuid, "daily_no_life_like_it", Math.min(hours, 500))) updatedCount++;
        if (setMax(uuid, "daily_veteran", Math.min(hours, 1000))) updatedCount++;

        // 2. Distance Traveled (cm to blocks: 1 block = 100 cm)
        int walkCm = player.getStats().getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM));
        int sprintCm = player.getStats().getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM));
        int flyCm = player.getStats().getValue(Stats.CUSTOM.get(Stats.FLY_ONE_CM));
        int blocks = (walkCm + sprintCm + flyCm) / 100;
        if (setMax(uuid, "exploring_far_from_home", Math.min(blocks, 50000))) updatedCount++;

        // 3. Fish Caught
        int fish = player.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
        if (setMax(uuid, "fishing_nibble", Math.min(fish, 10))) updatedCount++;
        if (setMax(uuid, "fishing_centurion_angler", Math.min(fish, 100))) updatedCount++;
        if (setMax(uuid, "fishing_leviathan", Math.min(fish, 500))) updatedCount++;
        if (setMax(uuid, "fishing_master_angler", Math.min(fish, 1000))) updatedCount++;

        // 4. Mined Stones / Blocks
        int stoneMined = player.getStats().getValue(Stats.BLOCK_MINED.get(Blocks.STONE));
        if (setMax(uuid, "mining_quarry_worker", Math.min(stoneMined, 1000))) updatedCount++;
        if (setMax(uuid, "mining_strip_miner", Math.min(stoneMined, 10000))) updatedCount++;

        LOGGER.info("Retroactive sync for {} completed with {} updated achievements.", player.getName().getString(), updatedCount);
        return updatedCount;
    }

    private Map<String, PlayerProgress> loadFromDisk(UUID uuid) {
        Path file = storageDir.resolve(uuid.toString() + ".json");
        if (!Files.exists(file)) {
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, PlayerProgress> loaded = GSON.fromJson(reader, MAP_TYPE);
            return loaded != null ? new ConcurrentHashMap<>(loaded) : new ConcurrentHashMap<>();
        } catch (Exception e) {
            LOGGER.error("Failed to read player achievement file for {}", uuid, e);
            return new ConcurrentHashMap<>();
        }
    }

    public synchronized void saveToDisk(UUID uuid) {
        Map<String, PlayerProgress> map = cache.get(uuid);
        if (map == null) return;

        Path file = storageDir.resolve(uuid.toString() + ".json");
        Path tempFile = storageDir.resolve(uuid.toString() + ".tmp");

        try {
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(map, writer);
            }
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("Failed to write player achievement file for {}", uuid, e);
        }
    }

    public void saveToDiskAsync(UUID uuid) {
        // Synchronous write guarantees atomic file replacement without concurrency lag
        saveToDisk(uuid);
    }
}
