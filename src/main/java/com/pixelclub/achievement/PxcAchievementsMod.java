package com.pixelclub.achievement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelclub.achievement.command.AchievementCommands;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.listener.BuiltInListeners;
import com.pixelclub.achievement.network.UIPayload;
import com.pixelclub.achievement.storage.PlayerStore;
import com.pixelclub.achievement.ui.AchievementGui;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mod entrypoint for Pixelclub Achievements (MC 1.21.1).
 * Runs on both dedicated servers and integrated singleplayer/dev clients.
 */
public class PxcAchievementsMod implements ModInitializer {
    public static final String MOD_ID = "pxcachievements";
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements");
    private static PxcAchievementsMod instance;

    private AchievementRegistry registry;
    private PlayerStore playerStore;

    @Override
    public void onInitialize() {
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

        // 5. Install polymorphic UIPayload networking channel & client callback receiver
        installPolymorphicUiChannel();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            installPolymorphicUiChannel();
        });

        // 6. Register Disconnect & Shutdown Flush
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null && playerStore != null) {
                playerStore.flushPlayerSync(player.getUUID());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Flushing all achievement dirty data on server shutdown...");
            if (playerStore != null) {
                playerStore.shutdown();
            }
        });

        LOGGER.info("Pixelclub Achievements initialized successfully with {} achievements registered.",
                registry.getTotalCount());
    }

    private static ServerPlayNetworking.PlayPayloadHandler<?> originalExternalHandler = null;
    private static ServerPlayNetworking.PlayPayloadHandler<CustomPacketPayload> ourChainedHandler = null;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void installPolymorphicUiChannel() {
        try {
            installPolymorphicForRegistry(PayloadTypeRegistry.playS2C(), "playS2C");
            installPolymorphicForRegistry(PayloadTypeRegistry.playC2S(), "playC2S");

            CustomPacketPayload.Type<CustomPacketPayload> canonicalType = (CustomPacketPayload.Type<CustomPacketPayload>) (Object) UIPayload.getCanonicalType();

            // Capture any existing global receiver before installing our chained receiver
            try {
                ServerPlayNetworking.PlayPayloadHandler<?> existing = ServerPlayNetworking.unregisterGlobalReceiver(canonicalType.id());
                if (existing != null && existing != ourChainedHandler) {
                    originalExternalHandler = existing;
                    LOGGER.info("Captured existing global receiver for channel {} to chain with QuestBook", canonicalType.id());
                }
            } catch (Throwable t) {
                LOGGER.debug("No existing receiver to unregister for channel {}", canonicalType.id());
            }

            final ServerPlayNetworking.PlayPayloadHandler fallback = originalExternalHandler;

            ourChainedHandler = new ServerPlayNetworking.PlayPayloadHandler<CustomPacketPayload>() {
                @Override
                public void receive(CustomPacketPayload payload, ServerPlayNetworking.Context context) {
                    ServerPlayer player = context.player();
                    String json = UIPayload.extractJsonReflectively(payload);

                    if (isQuestBookPacket(json) || fallback == null) {
                        context.server().execute(() -> {
                            AchievementGui.handleClientPacket(player, json);
                        });
                    } else {
                        try {
                            fallback.receive(payload, context);
                        } catch (Throwable t) {
                            LOGGER.error("Failed to delegate packet to chained external UI handler", t);
                        }
                    }
                }
            };

            boolean registered = ServerPlayNetworking.registerGlobalReceiver(canonicalType, ourChainedHandler);
            LOGGER.info("Registered polymorphic global receiver for channel {} (success={}, hasChainedHandler={})",
                    canonicalType, registered, fallback != null);
        } catch (Throwable t) {
            LOGGER.error("Failed to install polymorphic UIPayload channel", t);
        }
    }

    private static boolean isQuestBookPacket(String json) {
        if (json == null || json.isEmpty()) return false;
        if (json.contains("quest_book")) return true;
        if (json.contains("select_category:") || json.contains("claim:") || json.contains("view_category_rewards:")) return true;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String screenId = obj.has("screenId") ? obj.get("screenId").getAsString()
                    : (obj.has("screen") ? obj.get("screen").getAsString() : (obj.has("id") ? obj.get("id").getAsString() : ""));
            if ("quest_book".equalsIgnoreCase(screenId)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installPolymorphicForRegistry(Object registryObj, String name) {
        try {
            Field f = registryObj.getClass().getDeclaredField("packetTypes");
            f.setAccessible(true);
            Map packetTypes = (Map) f.get(registryObj);

            Object existingTypeAndCodec = packetTypes.get(UIPayload.ID);
            CustomPacketPayload.Type canonicalType = UIPayload.TYPE;
            StreamCodec existingCodec = null;

            if (existingTypeAndCodec != null) {
                try {
                    Method typeMethod = existingTypeAndCodec.getClass().getMethod("type");
                    canonicalType = (CustomPacketPayload.Type) typeMethod.invoke(existingTypeAndCodec);
                } catch (Throwable ignored) {}

                try {
                    Method codecMethod = existingTypeAndCodec.getClass().getMethod("codec");
                    existingCodec = (StreamCodec) codecMethod.invoke(existingTypeAndCodec);
                } catch (Throwable ignored) {}
            }

            UIPayload.setCanonicalType(canonicalType);
            StreamCodec polymorphicCodec = UIPayload.createPolymorphicCodec(existingCodec);
            CustomPacketPayload.TypeAndCodec replacement = new CustomPacketPayload.TypeAndCodec(canonicalType, polymorphicCodec);
            packetTypes.put(UIPayload.ID, replacement);
            LOGGER.info("Installed polymorphic TypeAndCodec for pixelclub:ui on {} (canonicalType: {})", name, canonicalType);
        } catch (Throwable t) {
            LOGGER.warn("Could not patch packetTypes on {} reflectively, attempting fallback register", name, t);
            try {
                if (registryObj instanceof PayloadTypeRegistry reg) {
                    reg.register(UIPayload.TYPE, UIPayload.createPolymorphicCodec(null));
                }
            } catch (Throwable ignored) {}
        }
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
