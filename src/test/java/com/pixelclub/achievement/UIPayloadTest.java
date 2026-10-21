package com.pixelclub.achievement;

import com.google.gson.JsonObject;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.AchievementCategory;
import com.pixelclub.achievement.network.UIPayload;
import com.pixelclub.achievement.ui.AchievementGui;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class UIPayloadTest {

    private AchievementRegistry registry;
    private List<AchievementCategory> categories;

    @BeforeEach
    public void setup() {
        registry = new AchievementRegistry();
        InputStream stream = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json");
        assertNotNull(stream);
        registry.loadFromStream(stream);
        categories = registry.getCategories();
    }

    @Test
    public void testInstallPolymorphicUiChannel() throws Exception {
        PxcAchievementsMod.installPolymorphicUiChannel();

        Object reg = PayloadTypeRegistry.playS2C();
        Field f = reg.getClass().getDeclaredField("packetTypes");
        f.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) f.get(reg);

        assertTrue(map.containsKey(UIPayload.ID), "pixelclub:ui must be registered in packetTypes");
        Object typeAndCodec = map.get(UIPayload.ID);
        assertNotNull(typeAndCodec);

        // Verify encode of our UIPayload
        StreamCodec polymorphicCodec = UIPayload.createPolymorphicCodec(null);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        UIPayload payload = new UIPayload("{\"action\":\"test_action\"}");
        polymorphicCodec.encode(buf, payload);

        assertTrue(buf.readableBytes() > 0);
        UIPayload decoded = (UIPayload) polymorphicCodec.decode(buf);
        assertEquals("{\"action\":\"test_action\"}", decoded.json());
    }

    @Test
    public void testPolymorphicCodecHandlesForeignPayloadWithoutClassCast() {
        // Record simulating a foreign mod's UIPayload (different class, same JSON payload concept)
        record ForeignUIPayload(String json) implements CustomPacketPayload {
            @Override
            public Type<? extends CustomPacketPayload> type() {
                return UIPayload.TYPE;
            }
        }

        StreamCodec polymorphicCodec = UIPayload.createPolymorphicCodec(null);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ForeignUIPayload foreign = new ForeignUIPayload("{\"action\":\"foreign_ui\"}");

        // Must encode without ClassCastException
        assertDoesNotThrow(() -> polymorphicCodec.encode(buf, foreign));
        assertEquals("{\"action\":\"foreign_ui\"}", UIPayload.extractJsonReflectively(foreign));

        // Decode produces our UIPayload
        UIPayload decoded = (UIPayload) polymorphicCodec.decode(buf);
        assertEquals("{\"action\":\"foreign_ui\"}", decoded.json());
    }

    @Test
    public void testExtractJsonReflectively() {
        UIPayload local = new UIPayload("{\"key\":\"local\"}");
        assertEquals("{\"key\":\"local\"}", UIPayload.extractJsonReflectively(local));

        record AnotherPayload(String json) {}
        assertEquals("{\"test\":1}", UIPayload.extractJsonReflectively(new AnotherPayload("{\"test\":1}")));
        assertEquals("{}", UIPayload.extractJsonReflectively(null));
    }

    @Test
    public void testSceneJsonPacketSizeWithinLimits() {
        UUID uuid = UUID.randomUUID();
        for (AchievementCategory cat : categories) {
            JsonObject scene = AchievementGui.buildSceneJson(uuid, null, categories, cat);
            String json = scene.toString();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            assertTrue(bytes.length < 32767,
                    "Category scene for " + cat.getId() + " is " + bytes.length + " bytes, must be under 32767 bytes");
            assertTrue(bytes.length < 262144, "Scene must be well under 256 KB safety ceiling");
        }
    }
}
