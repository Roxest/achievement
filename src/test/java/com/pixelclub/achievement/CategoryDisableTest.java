package com.pixelclub.achievement;

import com.pixelclub.achievement.config.AchievementConfig;
import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.pixelclub.achievement.storage.PlayerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryDisableTest {

    private Path tempDir;
    private AchievementRegistry registry;
    private UUID testPlayer;

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("pxc_cat_test_");
        registry = new AchievementRegistry();
        InputStream stream = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json");
        registry.loadFromStream(stream);
        testPlayer = UUID.randomUUID();
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testConfigSaveAndLoadDisabledCategories() {
        Path configFile = tempDir.resolve("config.json");
        AchievementConfig config = AchievementConfig.loadOrCreate(configFile);

        assertTrue(config.isCategoryEnabled("raids"));
        assertTrue(config.isCategoryEnabled("gyms"));

        config.setCategoryEnabled("raids", false);
        config.setCategoryEnabled("gyms", false);
        assertFalse(config.isCategoryEnabled("raids"));
        assertFalse(config.isCategoryEnabled("gyms"));
        assertTrue(config.isCategoryEnabled("catching"));

        config.save();

        // Reload fresh config from disk
        AchievementConfig reloaded = AchievementConfig.loadOrCreate(configFile);
        assertFalse(reloaded.isCategoryEnabled("raids"));
        assertFalse(reloaded.isCategoryEnabled("gyms"));
        assertTrue(reloaded.isCategoryEnabled("catching"));

        // Enable again
        reloaded.setCategoryEnabled("raids", true);
        assertTrue(reloaded.isCategoryEnabled("raids"));
    }

    @Test
    public void testPlayerStoreBlocksLockedCategory() {
        Path configFile = tempDir.resolve("config.json");
        AchievementConfig config = AchievementConfig.loadOrCreate(configFile);
        config.setCategoryEnabled("catching", false);

        PlayerStore store = new PlayerStore(tempDir.resolve("players"), registry, config);

        String achId = "catching_gotcha"; // category is 'catching'

        // 1. Increment blocked
        boolean incremented = store.increment(testPlayer, achId, 1);
        assertFalse(incremented, "Increment on disabled category must be rejected");
        PlayerProgress progress = store.getProgress(testPlayer, achId);
        assertEquals(0, progress.getProgress());

        // 2. SetMax blocked
        boolean maxed = store.setMax(testPlayer, achId, 1);
        assertFalse(maxed, "SetMax on disabled category must be rejected");
        assertEquals(0, store.getProgress(testPlayer, achId).getProgress());

        // 3. Claim blocked
        boolean claimed = store.claim(testPlayer, achId);
        assertFalse(claimed, "Claim on disabled category must be rejected");

        // 4. Enable category -> actions allowed
        config.setCategoryEnabled("catching", true);
        boolean incrementedAfterUnlock = store.increment(testPlayer, achId, 1);
        assertTrue(incrementedAfterUnlock, "Increment after unlock must succeed");
        assertEquals(1, store.getProgress(testPlayer, achId).getProgress());
        assertEquals(ClaimState.COMPLETED, store.getProgress(testPlayer, achId).getState());

        // 5. Disable again -> claim blocked even if completed
        config.setCategoryEnabled("catching", false);
        boolean claimedWhileDisabled = store.claim(testPlayer, achId);
        assertFalse(claimedWhileDisabled, "Claim when disabled must be blocked even if completed");

        // 6. Re-enable -> claim succeeds
        config.setCategoryEnabled("catching", true);
        boolean claimedAfterUnlock = store.claim(testPlayer, achId);
        assertTrue(claimedAfterUnlock, "Claim after unlock must succeed");
        assertEquals(ClaimState.CLAIMED, store.getProgress(testPlayer, achId).getState());

        store.shutdown();
    }
}
