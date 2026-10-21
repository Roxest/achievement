package com.pixelclub.achievement.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Robust network helper for the pixelclub:ui channel.
 * Prevents Netty EncoderException / ClassCastException by dynamically adapting
 * to whichever UIPayload class (pxc-client or local pxc-achievements) is registered
 * in the active Fabric runtime.
 */
public final class UIPayloadHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Network");
    public static final ResourceLocation UI_CHANNEL = ResourceLocation.fromNamespaceAndPath("pixelclub", "ui");

    private static boolean initialized = false;
    private static Class<? extends CustomPacketPayload> activePayloadClass = null;
    private static Constructor<? extends CustomPacketPayload> activeConstructor = null;
    private static Method activeJsonGetter = null;
    private static CustomPacketPayload.Type<?> activeType = null;

    private UIPayloadHelper() {}

    @SuppressWarnings("unchecked")
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // 1. Check if pxc-client's UIPayload exists on the classpath (e.g. integrated server / client)
        try {
            Class<?> clientClass = Class.forName("com.pixelclub.client.network.UIPayload");
            if (CustomPacketPayload.class.isAssignableFrom(clientClass)) {
                activePayloadClass = (Class<? extends CustomPacketPayload>) clientClass;
                activeConstructor = activePayloadClass.getConstructor(String.class);
                try {
                    activeJsonGetter = activePayloadClass.getMethod("json");
                } catch (NoSuchMethodException e) {
                    activeJsonGetter = activePayloadClass.getMethod("getJson");
                }
                Field typeField = activePayloadClass.getField("TYPE");
                activeType = (CustomPacketPayload.Type<?>) typeField.get(null);
                LOGGER.info("pixelclub:ui channel linked to client runtime payload: {}", activePayloadClass.getName());
                return;
            }
        } catch (Throwable ignored) {
            // Not running alongside pxc-client in the same JVM
        }

        // 2. Default to internal achievement UIPayload (dedicated server runtime)
        activePayloadClass = UIPayload.class;
        activeType = UIPayload.TYPE;
        try {
            activeConstructor = UIPayload.class.getConstructor(String.class);
            activeJsonGetter = UIPayload.class.getMethod("json");
            LOGGER.info("pixelclub:ui channel using local pxc-achievements UIPayload.");
        } catch (Exception e) {
            LOGGER.error("Failed to resolve local UIPayload constructor/method", e);
        }
    }

    /**
     * Registers payload types on playS2C and playC2S if not already registered by another mod.
     */
    public static void registerPayloadTypes() {
        init();

        try {
            PayloadTypeRegistry.playS2C().register(UIPayload.TYPE, UIPayload.CODEC);
            LOGGER.debug("Registered local UIPayload on playS2C");
        } catch (IllegalArgumentException e) {
            LOGGER.info("pixelclub:ui playS2C already registered by host environment.");
        }

        try {
            PayloadTypeRegistry.playC2S().register(UIPayload.TYPE, UIPayload.CODEC);
            LOGGER.debug("Registered local UIPayload on playC2S");
        } catch (IllegalArgumentException e) {
            LOGGER.info("pixelclub:ui playC2S already registered by host environment.");
        }
    }

    /**
     * Registers the global packet receiver on the active registered payload type.
     */
    @SuppressWarnings("unchecked")
    public static void registerReceiver(Consumer<PacketContext> handler) {
        init();

        CustomPacketPayload.Type typeToRegister = (activeType != null) ? activeType : UIPayload.TYPE;

        try {
            ServerPlayNetworking.registerGlobalReceiver(typeToRegister, (payload, context) -> {
                String json = extractJson(payload);
                if (json != null) {
                    ServerPlayer player = context.player();
                    context.server().execute(() -> handler.accept(new PacketContext(player, json)));
                }
            });
            LOGGER.info("Registered global receiver for channel pixelclub:ui using type ID {}", typeToRegister.id());
        } catch (Exception e) {
            LOGGER.error("Failed to register global receiver for pixelclub:ui", e);
        }
    }

    /**
     * Dispatches a pixelclub:ui payload to the target player, matching the registered class.
     */
    public static void send(ServerPlayer player, String json) {
        if (player == null || json == null) return;
        init();

        CustomPacketPayload payload = null;
        if (activeConstructor != null) {
            try {
                payload = activeConstructor.newInstance(json);
            } catch (Exception e) {
                LOGGER.warn("Failed to instantiate payload via active constructor, falling back to local UIPayload", e);
            }
        }

        if (payload == null) {
            payload = new UIPayload(json);
        }

        ServerPlayNetworking.send(player, payload);
    }

    public static String extractJson(CustomPacketPayload payload) {
        if (payload instanceof UIPayload local) {
            return local.json();
        }
        if (activeJsonGetter != null) {
            try {
                return (String) activeJsonGetter.invoke(payload);
            } catch (Exception e) {
                LOGGER.error("Failed to extract JSON from external payload: {}", payload.getClass().getName(), e);
            }
        }
        return null;
    }

    public static CustomPacketPayload.Type<?> getActiveType() {
        init();
        return activeType;
    }

    public static Class<? extends CustomPacketPayload> getActivePayloadClass() {
        init();
        return activePayloadClass;
    }

    public record PacketContext(ServerPlayer player, String json) {}
}
