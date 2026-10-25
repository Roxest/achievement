package com.pixelclub.achievement.storage;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.pokedex.SpeciesDexRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.config.AchievementConfig;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.pixelclub.achievement.reward.RewardDispatcher;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Thread-safe JSON player storage and retroactive synchronization engine.
 * Writes each player's progress atomically to config/pxc-achievements/players/<UUID>.json.
 * Uses a dirty-flag worker queue to eliminate synchronous disk I/O on the main server thread.
 */
public class PlayerStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Store");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, PlayerProgress>>() {}.getType();

    private final Path storageDir;
    private final AchievementRegistry registry;
    private final AchievementConfig config;
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, PlayerProgress>> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PxcAchievements-IO");
        t.setDaemon(true);
        return t;
    });

    public PlayerStore(Path storageDir, AchievementRegistry registry) {
        this(storageDir, registry, null);
    }

    public PlayerStore(Path storageDir, AchievementRegistry registry, AchievementConfig config) {
        this.storageDir = storageDir;
        this.registry = registry;
        this.config = config;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create player achievement storage directory: {}", storageDir, e);
        }

        // Schedule periodic async flush every 2 seconds
        this.ioExecutor.scheduleWithFixedDelay(this::flushDirtyAsync, 2, 2, TimeUnit.SECONDS);
    }

    public ConcurrentHashMap<String, PlayerProgress> getPlayerData(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadFromDisk);
    }

    public PlayerProgress getProgress(UUID uuid, String achievementId) {
        ConcurrentHashMap<String, PlayerProgress> map = getPlayerData(uuid);
        return map.computeIfAbsent(achievementId, k -> new PlayerProgress());
    }

    private boolean isCategoryLocked(String categoryId) {
        if (categoryId == null) return false;
        AchievementConfig cfg = this.config;
        if (cfg == null) {
            PxcAchievementsMod mod = PxcAchievementsMod.getInstance();
            if (mod != null) {
                cfg = mod.getConfig();
            }
        }
        if (cfg != null) {
            return !cfg.isCategoryEnabled(categoryId);
        }
        return false;
    }

    /**
     * Atomically increments progress by amount. Rejects negative increments via watchdog.
     */
    public boolean increment(UUID uuid, String achievementId, int amount) {
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;
        if (isCategoryLocked(ach.getCategoryId())) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        if (amount < 0) {
            AchievementWatchdog.alertNonMonotonicProgress(uuid, achievementId, progress.getProgress(), progress.getProgress() + amount);
            return false;
        }
        if (amount == 0) return false;

        boolean newlyCompleted = progress.addProgress(amount, ach.getTarget());
        markDirty(uuid);
        return newlyCompleted;
    }

    /**
     * Atomically sets progress if greater than current value.
     */
    public boolean setMax(UUID uuid, String achievementId, int value) {
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;
        if (isCategoryLocked(ach.getCategoryId())) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        int previous = progress.getProgress();
        boolean newlyCompleted = progress.setMaxProgress(value, ach.getTarget());
        if (newlyCompleted || progress.getProgress() > previous) {
            markDirty(uuid);
        }
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
        markDirty(uuid);
        return newlyCompleted;
    }

    /**
     * Claims an achievement atomically (COMPLETED -> CLAIMED) and dispatches rewards to the player.
     */
    public boolean claim(ServerPlayer player, String achievementId) {
        UUID uuid = player.getUUID();
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;
        if (isCategoryLocked(ach.getCategoryId())) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        if (progress.tryClaim()) {
            markDirty(uuid);
            RewardDispatcher.dispatchRewards(player, ach);
            return true;
        } else {
            AchievementWatchdog.alertDuplicateClaim(uuid, achievementId, progress.getState().getId());
            return false;
        }
    }

    /**
     * Claims an achievement atomically without player dispatch (used in tests/simulations).
     */
    public boolean claim(UUID uuid, String achievementId) {
        Achievement ach = registry.getAchievement(achievementId);
        if (ach == null) return false;
        if (isCategoryLocked(ach.getCategoryId())) return false;

        PlayerProgress progress = getProgress(uuid, achievementId);
        if (progress.tryClaim()) {
            markDirty(uuid);
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
        markDirty(uuid);
    }

    /**
     * Resets all achievements for a player.
     */
    public void resetAll(UUID uuid) {
        ConcurrentHashMap<String, PlayerProgress> map = getPlayerData(uuid);
        map.clear();
        markDirty(uuid);
    }

    /**
     * Retroactively synchronizes progress from vanilla statistics and Cobblemon player data upon join.
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

        // 4. Mined Stones / Deepslate
        int stoneMined = player.getStats().getValue(Stats.BLOCK_MINED.get(Blocks.STONE));
        int deepslateMined = player.getStats().getValue(Stats.BLOCK_MINED.get(Blocks.DEEPSLATE));
        int totalMined = stoneMined + deepslateMined;
        if (setMax(uuid, "mining_quarry_worker", Math.min(totalMined, 1000))) updatedCount++;
        if (setMax(uuid, "mining_strip_miner", Math.min(totalMined, 10000))) updatedCount++;

        // 5. Cobblemon Pokédex Caught Sync
        try {
            Class.forName("com.cobblemon.mod.common.Cobblemon");
            syncPokedexCaught(uuid);
        } catch (Throwable ignored) {
            // Cobblemon not loaded or inactive
        }

        LOGGER.info("Retroactive sync for {} completed with {} updated achievements.", player.getName().getString(), updatedCount);
        return updatedCount;
    }

    public void syncPokedexCaught(UUID uuid) {
        try {
            PokedexManager dex = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(uuid);
            if (dex != null) {
                int caughtCount = 0;
                for (SpeciesDexRecord record : dex.getSpeciesRecords().values()) {
                    if (record.hasAtLeast(PokedexEntryProgress.CAUGHT)) {
                        caughtCount++;
                    }
                }
                setMax(uuid, "catching_a_good_start", Math.min(caughtCount, 50));
                setMax(uuid, "catching_variety_is_the_spice", Math.min(caughtCount, 25));
                setMax(uuid, "catching_kanto_veteran", Math.min(caughtCount, 151));
                setMax(uuid, "catching_halfway_home", Math.min(caughtCount, 500));
                setMax(uuid, "catching_the_800_club", Math.min(caughtCount, 800));
                setMax(uuid, "catching_living_legend", Math.min(caughtCount, 1000));
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to retroactively sync Pokédex caught count for {}", uuid, t);
        }
    }

    private ConcurrentHashMap<String, PlayerProgress> loadFromDisk(UUID uuid) {
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
        ConcurrentHashMap<String, PlayerProgress> map = cache.get(uuid);
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

    public void markDirty(UUID uuid) {
        dirtyPlayers.add(uuid);
    }

    public Set<UUID> getDirtyPlayers() {
        return dirtyPlayers;
    }

    private void flushDirtyAsync() {
        if (dirtyPlayers.isEmpty()) return;
        Set<UUID> toFlush = new HashSet<>(dirtyPlayers);
        dirtyPlayers.removeAll(toFlush);
        for (UUID uuid : toFlush) {
            saveToDisk(uuid);
        }
    }

    public void flushPlayerSync(UUID uuid) {
        dirtyPlayers.remove(uuid);
        saveToDisk(uuid);
    }

    public synchronized void flushAllSync() {
        if (!dirtyPlayers.isEmpty()) {
            Set<UUID> toFlush = new HashSet<>(dirtyPlayers);
            dirtyPlayers.removeAll(toFlush);
            for (UUID uuid : toFlush) {
                saveToDisk(uuid);
            }
        }
    }

    public void shutdown() {
        flushAllSync();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
