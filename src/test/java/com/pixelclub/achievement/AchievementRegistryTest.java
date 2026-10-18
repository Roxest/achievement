package com.pixelclub.achievement;

import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AchievementRegistryTest {

    private AchievementRegistry registry;

    @BeforeEach
    public void setup() {
        registry = new AchievementRegistry();
        InputStream stream = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json");
        assertNotNull(stream, "Schema file data/pxc-achievements/achievements.json must be present in classpath");
        boolean success = registry.loadFromStream(stream);
        assertTrue(success, "Schema must parse cleanly without errors");
    }

    @Test
    public void testTotalCounts() {
        assertEquals(16, registry.getCategories().size(), "Must contain exactly 16 categories");
        assertEquals(316, registry.getTotalCount(), "Must contain exactly 316 achievements");
    }

    @Test
    public void testUniqueIdsAndNonEmptyFields() {
        Set<String> seenIds = new HashSet<>();
        for (AchievementCategory cat : registry.getCategories()) {
            assertNotNull(cat.getId(), "Category ID cannot be null");
            assertFalse(cat.getId().isEmpty(), "Category ID cannot be empty");
            assertNotNull(cat.getName(), "Category Name cannot be null");

            for (Achievement ach : cat.getAchievements()) {
                assertNotNull(ach.getId(), "Achievement ID cannot be null");
                assertFalse(ach.getId().isEmpty(), "Achievement ID cannot be empty");
                assertTrue(seenIds.add(ach.getId()), "Duplicate achievement ID detected: " + ach.getId());

                assertNotNull(ach.getName(), "Achievement name cannot be null: " + ach.getId());
                assertFalse(ach.getName().isEmpty(), "Achievement name cannot be empty: " + ach.getId());

                assertNotNull(ach.getDescription(), "Achievement description cannot be null: " + ach.getId());
                assertFalse(ach.getDescription().isEmpty(), "Achievement description cannot be empty: " + ach.getId());
                assertFalse(ach.getDescription().contains("cut off in UI scroll"), "Description must not contain raw cut-off text: " + ach.getId());

                assertTrue(ach.getTarget() > 0, "Target must be positive integer: " + ach.getId());
                assertNotNull(ach.getRewards(), "Rewards list cannot be null: " + ach.getId());
                assertNotNull(ach.getTriggerBucket(), "Trigger bucket cannot be null: " + ach.getId());
            }
        }
    }

    @Test
    public void testCategoryLookups() {
        assertNotNull(registry.getCategory("catching"));
        assertNotNull(registry.getCategory("gyms"));
        assertNotNull(registry.getCategory("fishing"));
        assertNotNull(registry.getCategory("raids"));

        assertNotNull(registry.getAchievement("catching_gotcha"));
        assertEquals("Gotcha!", registry.getAchievement("catching_gotcha").getName());
        assertEquals(1, registry.getAchievement("catching_gotcha").getTarget());
    }
}
