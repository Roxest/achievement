package com.pixelclub.achievement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelclub.achievement.command.AchievementCommands;
import com.pixelclub.achievement.config.AchievementConfig;
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
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
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
    private AchievementConfig config;
    private PlayerStore playerStore;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("Initializing Pixelclub Achievements (Fabric 1.21.1)...");

        // 1. Initialize registry and load config & schema
        this.registry = new AchievementRegistry();
        loadConfigAndSchema();

        // 2. Initialize storage
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("pxc-achievements");
        this.playerStore = new PlayerStore(configDir.resolve("players"), registry, config);

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

            // Wrap ServerNetworkingImpl.PLAY.handlers with NonCollidingHandlersMap
            try {
                Class<?> serverNetworkingImpl = Class.forName("net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl");
                Field playField = serverNetworkingImpl.getDeclaredField("PLAY");
                playField.setAccessible(true);
                Object globalRegistry = playField.get(null);
                if (globalRegistry != null) {
                    Field handlersField = globalRegistry.getClass().getDeclaredField("handlers");
                    handlersField.setAccessible(true);
                    Map handlers = (Map) handlersField.get(globalRegistry);
                    if (!(handlers instanceof NonCollidingHandlersMap)) {
                        NonCollidingHandlersMap nonCollidingHandlers = new NonCollidingHandlersMap(handlers);
                        handlersField.set(globalRegistry, nonCollidingHandlers);
                        LOGGER.info("Wrapped ServerNetworkingImpl.PLAY.handlers with NonCollidingHandlersMap");
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not wrap ServerNetworkingImpl.PLAY reflectively: {}", t.getMessage());
            }

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

            NonCollidingPacketTypesMap nonCollidingMap;
            if (packetTypes instanceof NonCollidingPacketTypesMap existingMap) {
                nonCollidingMap = existingMap;
            } else {
                nonCollidingMap = new NonCollidingPacketTypesMap(packetTypes);
                f.set(registryObj, nonCollidingMap);
            }

            nonCollidingMap.putDirect(UIPayload.ID, replacement);
            LOGGER.info("Installed non-colliding polymorphic TypeAndCodec for pixelclub:ui on {} (canonicalType: {})", name, canonicalType);
        } catch (Throwable t) {
            LOGGER.warn("Could not patch packetTypes on {} reflectively, attempting fallback register", name, t);
            try {
                if (registryObj instanceof PayloadTypeRegistry reg) {
                    reg.register(UIPayload.TYPE, UIPayload.createPolymorphicCodec(null));
                }
            } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class NonCollidingPacketTypesMap extends HashMap {
        public NonCollidingPacketTypesMap(Map existing) {
            if (existing != null) {
                putAll(existing);
            }
        }

        public Object putDirect(Object key, Object value) {
            return super.put(key, value);
        }

        @Override
        public boolean containsKey(Object key) {
            if (UIPayload.ID.equals(key)) {
                // Return false when checked by PayloadTypeRegistry.register() so subsequent mods
                // (outbreak, Rew_Legendary) never get IllegalArgumentException!
                for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
                    if ("register".equals(el.getMethodName()) && el.getClassName().contains("PayloadTypeRegistry")) {
                        return false;
                    }
                }
            }
            return super.containsKey(key);
        }

        @Override
        public Object put(Object key, Object value) {
            if (UIPayload.ID.equals(key) && value != null) {
                try {
                    Method typeMethod = value.getClass().getMethod("type");
                    CustomPacketPayload.Type externalType = (CustomPacketPayload.Type) typeMethod.invoke(value);
                    Method codecMethod = value.getClass().getMethod("codec");
                    StreamCodec externalCodec = (StreamCodec) codecMethod.invoke(value);

                    LOGGER.info("[PixelclubNetworking] Intercepted pixelclub:ui registration by sibling mod: type={}", externalType);
                    UIPayload.setCanonicalType(externalType);
                    StreamCodec polymorphicCodec = UIPayload.createPolymorphicCodec(externalCodec);
                    CustomPacketPayload.TypeAndCodec replacement = new CustomPacketPayload.TypeAndCodec(externalType, polymorphicCodec);
                    return super.put(key, replacement);
                } catch (Throwable t) {
                    LOGGER.warn("[PixelclubNetworking] Failed to wrap sibling TypeAndCodec reflectively", t);
                }
            }
            return super.put(key, value);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class NonCollidingHandlersMap extends HashMap {
        public NonCollidingHandlersMap(Map existing) {
            if (existing != null) {
                putAll(existing);
            }
        }

        @Override
        public Object putIfAbsent(Object key, Object value) {
            if (UIPayload.ID.equals(key) && value != null && value != ourChainedHandler) {
                LOGGER.info("[PixelclubNetworking] Chaining C2S global receiver from sibling mod: {}", value.getClass().getName());
                originalExternalHandler = (ServerPlayNetworking.PlayPayloadHandler<?>) value;
                super.put(key, ourChainedHandler);
                return null; // Return null so putIfAbsent signals successful registration to Fabric
            }
            return super.putIfAbsent(key, value);
        }
    }

    public static PxcAchievementsMod getInstance() {
        return instance;
    }

    public AchievementRegistry getRegistry() {
        return registry;
    }

    public AchievementConfig getConfig() {
        return config;
    }

    public PlayerStore getPlayerStore() {
        return playerStore;
    }

    public void loadConfigAndSchema() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("pxc-achievements");
        this.config = AchievementConfig.loadOrCreate(configDir.resolve("config.json"));

        Path schemaFile = configDir.resolve("achievements.json");
        if (!Files.exists(schemaFile)) {
            try {
                Files.createDirectories(configDir);
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json")) {
                    if (in != null) {
                        Files.copy(in, schemaFile, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Exported default achievements schema to {}", schemaFile);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to export default achievements schema to {}", schemaFile, e);
            }
        }

        boolean loaded = false;
        if (Files.exists(schemaFile)) {
            try (InputStream in = Files.newInputStream(schemaFile)) {
                loaded = this.registry.loadFromStream(in);
                if (loaded) {
                    LOGGER.info("Loaded {} achievements across {} categories from schema file: {}",
                            registry.getTotalCount(), registry.getCategories().size(), schemaFile);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load achievements schema from {}, falling back to classpath", schemaFile, e);
            }
        }

        if (!loaded) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json")) {
                if (in != null) {
                    this.registry.loadFromStream(in);
                    LOGGER.info("Loaded {} achievements across {} categories from classpath resource.",
                            registry.getTotalCount(), registry.getCategories().size());
                }
            } catch (Exception e) {
                LOGGER.error("CRITICAL: Failed to load achievements schema from classpath", e);
            }
        }
    }
}
