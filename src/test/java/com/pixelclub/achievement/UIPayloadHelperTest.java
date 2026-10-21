package com.pixelclub.achievement;

import com.google.gson.JsonObject;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.AchievementCategory;
import com.pixelclub.achievement.network.UIPayload;
import com.pixelclub.achievement.network.UIPayloadHelper;
import com.pixelclub.achievement.ui.AchievementGui;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class UIPayloadHelperTest {

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
    public void testUIPayloadHelperInit() {
        UIPayloadHelper.init();
        assertNotNull(UIPayloadHelper.getActiveType());
        assertEquals("pixelclub:ui", UIPayloadHelper.getActiveType().id().toString());
        assertNotNull(UIPayloadHelper.getActivePayloadClass());
    }

    @Test
    public void testExtractJsonFromLocalPayload() {
        String testJson = "{\"action\":\"open_screen\",\"id\":\"quest_book\"}";
        UIPayload payload = new UIPayload(testJson);
        String extracted = UIPayloadHelper.extractJson(payload);
        assertEquals(testJson, extracted);
    }

    @Test
    public void testSceneJsonPacketSizeWithinLimits() {
        UUID uuid = UUID.randomUUID();
        // Test all 16 categories: each scene must be scoped to active category and under 32 KB
        for (AchievementCategory cat : categories) {
            JsonObject scene = AchievementGui.buildSceneJson(uuid, null, categories, cat);
            String json = scene.toString();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            assertTrue(bytes.length < 32767,
                    "Category scene for " + cat.getId() + " is " + bytes.length + " bytes, must be under 32767 bytes (was " + bytes.length + ")");
            assertTrue(bytes.length < 262144, "Scene must be well under 256 KB safety ceiling");
        }
    }
}
