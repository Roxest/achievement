package com.pixelclub.achievement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.AchievementCategory;
import com.pixelclub.achievement.ui.AchievementGui;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AchievementGuiTest {

    private AchievementRegistry registry;
    private List<AchievementCategory> categories;

    @BeforeEach
    public void setup() {
        registry = new AchievementRegistry();
        InputStream stream = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json");
        assertNotNull(stream, "Schema must exist");
        registry.loadFromStream(stream);
        categories = registry.getCategories();
        assertEquals(16, categories.size());
    }

    @Test
    public void testSceneJsonTopLevelStructure() {
        UUID uuid = UUID.randomUUID();
        JsonObject scene = AchievementGui.buildSceneJson(uuid, null, categories, categories.get(0));

        assertNotNull(scene);
        assertEquals("open_screen", scene.get("action").getAsString());
        assertEquals("scene", scene.get("layout").getAsString());
        assertEquals("quest_book", scene.get("id").getAsString());
        assertEquals("QUEST BOOK", scene.get("title").getAsString());
        assertEquals("close", scene.get("esc_action").getAsString());
        assertTrue(scene.get("smooth").getAsBoolean());

        JsonArray vp = scene.getAsJsonArray("viewport");
        assertNotNull(vp);
        assertEquals(800, vp.get(0).getAsInt());
        assertEquals(480, vp.get(1).getAsInt());

        assertTrue(scene.has("root"), "Must contain root node");
    }

    @Test
    public void testLeftPageHasAll16Categories() {
        UUID uuid = UUID.randomUUID();
        JsonObject scene = AchievementGui.buildSceneJson(uuid, null, categories, categories.get(0));

        JsonObject root = scene.getAsJsonObject("root");
        JsonObject bookFrame = root.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonObject pagesRow = bookFrame.getAsJsonArray("children").get(1).getAsJsonObject();
        JsonObject leftPage = pagesRow.getAsJsonArray("children").get(0).getAsJsonObject();

        JsonArray catRows = leftPage.getAsJsonArray("children");
        assertEquals(16, catRows.size(), "Left page must render all 16 categories simultaneously (no pagination arrows)");

        // Verify active category border highlight
        JsonObject activeRow = catRows.get(0).getAsJsonObject();
        assertEquals("#4A2E19", activeRow.get("fill").getAsString());
        assertEquals("#F59E0B", activeRow.get("border").getAsString());
        assertEquals(2, activeRow.get("border_w").getAsInt());

        // Verify other categories have unselected style
        JsonObject inactiveRow = catRows.get(1).getAsJsonObject();
        assertEquals("#180D06", inactiveRow.get("fill").getAsString());
        assertEquals("#2B160A", inactiveRow.get("border").getAsString());
    }

    @Test
    public void testRightPageHasHeaderAndScrollableSections() {
        UUID uuid = UUID.randomUUID();
        AchievementCategory cat = categories.get(0); // Catching Pokémon (25)
        JsonObject scene = AchievementGui.buildSceneJson(uuid, null, categories, cat);

        JsonObject root = scene.getAsJsonObject("root");
        JsonObject bookFrame = root.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonObject pagesRow = bookFrame.getAsJsonArray("children").get(1).getAsJsonObject();
        JsonObject rightPage = pagesRow.getAsJsonArray("children").get(1).getAsJsonObject();

        JsonArray pageItems = rightPage.getAsJsonArray("children");
        assertEquals(2, pageItems.size(), "Right page must have summary banner and scroll viewport");

        // 1. Top banner
        JsonObject banner = pageItems.get(0).getAsJsonObject();
        assertTrue(banner.has("children"));

        // 2. Scrollable viewport
        JsonObject scroll = pageItems.get(1).getAsJsonObject();
        assertEquals("scroll", scroll.get("type").getAsString());
        assertEquals("y", scroll.get("dir").getAsString());

        JsonObject scrollContent = scroll.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonArray sectionsAndCards = scrollContent.getAsJsonArray("children");
        assertTrue(sectionsAndCards.size() > 0, "Must contain section headers and achievement cards");

        // Dump sample JSON for verification
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of("build/sample_quest_book.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(scene)
            );
        } catch (Exception ignored) {}
    }
}
