package com.pixelclub.achievement.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

public record UIPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UIPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("pixelclub", "ui"));

    public static final StreamCodec<ByteBuf, UIPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            byte[] bytes = payload.json().getBytes(StandardCharsets.UTF_8);
            buf.writeBytes(bytes);
        },
        buf -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new UIPayload(new String(bytes, StandardCharsets.UTF_8));
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
