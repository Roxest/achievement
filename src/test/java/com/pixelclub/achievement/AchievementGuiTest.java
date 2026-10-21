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

        JsonObject root = scene.getAsJsonObject("root");
        JsonObject bookFrame = root.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals(560, bookFrame.get("w").getAsInt(), "Book frame width must be 560");
        assertEquals(355, bookFrame.get("h").getAsInt(), "Book frame height must be 355");
        assertEquals("#EED9B3", bookFrame.get("fill").getAsString(), "Outer frame must be parchment color #EED9B3");
        assertEquals("#5C2612", bookFrame.get("border").getAsString(), "Outer border must be leather brown #5C2612");
        assertEquals(3, bookFrame.get("border_w").getAsInt());

        // Verify top bar ribbon and close button centering
        JsonObject topBar = bookFrame.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonArray topChildren = topBar.getAsJsonArray("children");
        JsonObject ribbon = topChildren.get(2).getAsJsonObject();
        assertEquals(140, ribbon.get("w").getAsInt());
        assertEquals(22, ribbon.get("h").getAsInt());
        assertEquals("#B71C1C", ribbon.get("fill").getAsString());
        JsonObject ribbonText = ribbon.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("center", ribbonText.get("align_text").getAsString());
        assertEquals("fill", ribbonText.get("w").getAsString());

        JsonObject closeBtn = topChildren.get(4).getAsJsonObject();
        assertEquals(20, closeBtn.get("w").getAsInt());
        assertEquals(20, closeBtn.get("h").getAsInt());
        JsonObject closeText = closeBtn.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("center", closeText.get("align_text").getAsString());
        assertEquals("fill", closeText.get("w").getAsString());
    }

    @Test
    public void testLeftPageHasAll16Categories() {
        UUID uuid = UUID.randomUUID();
        JsonObject scene = AchievementGui.buildSceneJson(uuid, null, categories, categories.get(0));

        JsonObject root = scene.getAsJsonObject("root");
        JsonObject bookFrame = root.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonObject pagesRow = bookFrame.getAsJsonArray("children").get(1).getAsJsonObject();
        JsonObject leftPage = pagesRow.getAsJsonArray("children").get(0).getAsJsonObject();

        assertEquals(185, leftPage.get("w").getAsInt(), "Left page width must be 185");
        assertEquals("#2B170A", leftPage.get("fill").getAsString());

        JsonArray catRows = leftPage.getAsJsonArray("children");
        assertEquals(16, catRows.size(), "Left page must render all 16 categories simultaneously (no pagination arrows)");

        // Verify category row proportions and right-aligned counter without spacer
        JsonObject firstRow = catRows.get(0).getAsJsonObject();
        assertEquals(18, firstRow.get("h").getAsInt());
        JsonArray rowChildren = firstRow.getAsJsonArray("children");
        JsonObject title = rowChildren.get(2).getAsJsonObject();
        assertEquals("fill", title.get("w").getAsString(), "Title must have w: fill to push counter right");
        JsonObject counter = rowChildren.get(3).getAsJsonObject();
        assertEquals("right", counter.get("align_text").getAsString(), "Counter must be right-aligned");

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

        assertEquals("#381F0E", rightPage.get("fill").getAsString());
        assertEquals("#4E2C14", rightPage.get("border").getAsString());

        JsonArray pageItems = rightPage.getAsJsonArray("children");
        assertEquals(2, pageItems.size(), "Right page must have summary banner and scroll viewport");

        // 1. Top banner: 3 rows
        JsonObject banner = pageItems.get(0).getAsJsonObject();
        JsonArray bannerRows = banner.getAsJsonArray("children");
        assertEquals(3, bannerRows.size(), "Top banner must have exactly 3 rows");

        // Row 1: Title (w: fill) + Counter (align_text: right)
        JsonObject bRow1 = bannerRows.get(0).getAsJsonObject();
        JsonArray r1Children = bRow1.getAsJsonArray("children");
        assertEquals("fill", r1Children.get(0).getAsJsonObject().get("w").getAsString());
        assertEquals("right", r1Children.get(1).getAsJsonObject().get("align_text").getAsString());

        // Row 3: Progress bar (w: fill) + Rewards button (w: 68, h: 16)
        JsonObject bRow3 = bannerRows.get(2).getAsJsonObject();
        JsonArray r3Children = bRow3.getAsJsonArray("children");
        assertEquals("progress", r3Children.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject rewBtn = r3Children.get(1).getAsJsonObject();
        assertEquals(68, rewBtn.get("w").getAsInt());
        assertEquals(16, rewBtn.get("h").getAsInt());
        JsonObject rewText = rewBtn.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("center", rewText.get("align_text").getAsString());
        assertEquals("fill", rewText.get("w").getAsString());

        // 2. Scrollable viewport
        JsonObject scroll = pageItems.get(1).getAsJsonObject();
        assertEquals("scroll", scroll.get("type").getAsString());
        assertEquals("y", scroll.get("dir").getAsString());

        JsonObject scrollContent = scroll.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonArray sectionsAndCards = scrollContent.getAsJsonArray("children");
        assertTrue(sectionsAndCards.size() > 0, "Must contain section headers and achievement cards");

        // Verify section header has no spacers and sCount is right-aligned
        JsonObject secHeader = sectionsAndCards.get(0).getAsJsonObject();
        JsonObject secHeaderRow = secHeader.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonArray sRowChildren = secHeaderRow.getAsJsonArray("children");
        assertEquals(2, sRowChildren.size(), "Section header row must have only title and count (no spacer)");
        assertEquals("fill", sRowChildren.get(0).getAsJsonObject().get("w").getAsString());
        assertEquals("right", sRowChildren.get(1).getAsJsonObject().get("align_text").getAsString());

        // Verify achievement card proportions
        JsonObject card = sectionsAndCards.get(1).getAsJsonObject();
        assertEquals("#2A160B", card.get("fill").getAsString());
        assertEquals("#4A2914", card.get("border").getAsString());
        assertEquals(4, card.get("pad").getAsInt());
        assertEquals(4, card.get("gap").getAsInt());

        // Dump sample JSON for verification
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of("build/sample_quest_book.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(scene)
            );
        } catch (Exception ignored) {}
    }
}
