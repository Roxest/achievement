package com.pixelclub.achievement;

import com.pixelclub.achievement.command.AchievementCommands;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.listener.BuiltInListeners;
import com.pixelclub.achievement.storage.PlayerStore;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Dedicated server mod entrypoint for Pixelclub Achievements.
 */
public class PxcAchievementsMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "pxcachievements";
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements");
    private static PxcAchievementsMod instance;

    private AchievementRegistry registry;
    private PlayerStore playerStore;

    @Override
    public void onInitializeServer() {
        instance = this;
        LOGGER.info("Initializing Pixelclub Achievements (Fabric 1.21.1)...");

        // 1. Initialize registry and load schema
        this.registry = new AchievementRegistry();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json")) {
            if (stream != null) {
                this.registry.loadFromStream(stream);
            } else {
                LOGGER.error("CRITICAL: achievements.json schema resource not found in classpath!");
            }
        } catch (Exception e) {
            LOGGER.error("CRITICAL: Failed to load achievements schema", e);
        }

        // 2. Initialize storage
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("pxc-achievements");
        this.playerStore = new PlayerStore(configDir.resolve("players"), registry);

        // 3. Register Brigadier Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AchievementCommands.register(dispatcher);
        });

        // 4. Register Built-in Event Listeners
        BuiltInListeners.register();

        // 5. Register Disconnect & Shutdown Flush
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            net.minecraft.server.level.ServerPlayer player = handler.getPlayer();
            if (player != null && playerStore != null) {
                playerStore.flushPlayerSync(player.getUUID());
            }
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Flushing all achievement dirty data on server shutdown...");
            if (playerStore != null) {
                playerStore.shutdown();
            }
        });

        LOGGER.info("Pixelclub Achievements initialized successfully with {} achievements registered.",
                registry.getTotalCount());
    }

    public static PxcAchievementsMod getInstance() {
        return instance;
    }

    public AchievementRegistry getRegistry() {
        return registry;
    }

    public PlayerStore getPlayerStore() {
        return playerStore;
    }
}
