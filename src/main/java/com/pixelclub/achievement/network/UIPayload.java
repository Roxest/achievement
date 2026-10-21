package com.pixelclub.achievement.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Universal polymorphic payload record for channel pixelclub:ui.
 * Avoids Netty EncoderException / ClassCastException by dynamically handling
 * both local UIPayload instances and foreign UIPayload instances from sibling mods.
 */
public record UIPayload(String json) implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("pixelclub", "ui");
    public static final CustomPacketPayload.Type<CustomPacketPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    private static volatile CustomPacketPayload.Type<CustomPacketPayload> canonicalType = TYPE;

    @SuppressWarnings("unchecked")
    public static void setCanonicalType(CustomPacketPayload.Type<?> type) {
        if (type != null) {
            canonicalType = (CustomPacketPayload.Type<CustomPacketPayload>) type;
        }
    }

    public static CustomPacketPayload.Type<CustomPacketPayload> getCanonicalType() {
        return canonicalType != null ? canonicalType : TYPE;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static StreamCodec<FriendlyByteBuf, CustomPacketPayload> createPolymorphicCodec(
            StreamCodec delegateCodec) {
        return new StreamCodec<FriendlyByteBuf, CustomPacketPayload>() {
            @Override
            public void encode(FriendlyByteBuf buf, CustomPacketPayload payload) {
                if (payload instanceof UIPayload ours) {
                    writeJson(buf, ours.json());
                } else if (delegateCodec != null) {
                    try {
                        delegateCodec.encode(buf, payload);
                        return;
                    } catch (Throwable ignored) {}
                    writeJson(buf, extractJsonReflectively(payload));
                } else {
                    writeJson(buf, extractJsonReflectively(payload));
                }
            }

            @Override
            public CustomPacketPayload decode(FriendlyByteBuf buf) {
                return new UIPayload(readJson(buf));
            }
        };
    }

    private static void writeJson(FriendlyByteBuf buf, String json) {
        // Wire format matches pxc-client (raw UTF-8 bytes)
        byte[] bytes = (json != null ? json : "{}").getBytes(StandardCharsets.UTF_8);
        buf.writeBytes(bytes);
    }

    private static String readJson(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String extractJsonReflectively(Object payload) {
        if (payload == null) return "{}";
        if (payload instanceof UIPayload ours) return ours.json();
        try {
            Method m = payload.getClass().getMethod("json");
            m.setAccessible(true);
            Object val = m.invoke(payload);
            if (val instanceof String s) return s;
        } catch (Throwable ignored) {}
        try {
            Field f = payload.getClass().getDeclaredField("json");
            f.setAccessible(true);
            Object val = f.get(payload);
            if (val instanceof String s) return s;
        } catch (Throwable ignored) {}
        return "{}";
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return canonicalType != null ? canonicalType : TYPE;
    }
}
