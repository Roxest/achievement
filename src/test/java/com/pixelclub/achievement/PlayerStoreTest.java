package com.pixelclub.achievement;

import com.pixelclub.achievement.data.AchievementRegistry;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.pixelclub.achievement.storage.PlayerStore;
import com.pixelclub.achievement.watchdog.AchievementWatchdog;
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

public class PlayerStoreTest {

    private Path tempDir;
    private AchievementRegistry registry;
    private PlayerStore store;
    private UUID testPlayer;

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("pxc_ach_test_");
        registry = new AchievementRegistry();
        InputStream stream = getClass().getClassLoader().getResourceAsStream("data/pxc-achievements/achievements.json");
        registry.loadFromStream(stream);

        store = new PlayerStore(tempDir, registry);
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
    public void testIncrementAndSave() {
        String achId = "catching_gotcha"; // target 1
        boolean completed = store.increment(testPlayer, achId, 1);
        assertTrue(completed);

        PlayerProgress progress = store.getProgress(testPlayer, achId);
        assertEquals(1, progress.getProgress());
        assertEquals(ClaimState.COMPLETED, progress.getState());

        // Verify disk file created
        Path file = tempDir.resolve(testPlayer.toString() + ".json");
        assertTrue(Files.exists(file), "Player data file must exist on disk");

        // Reload fresh store from disk
        PlayerStore freshStore = new PlayerStore(tempDir, registry);
        PlayerProgress reloaded = freshStore.getProgress(testPlayer, achId);
        assertEquals(1, reloaded.getProgress());
        assertEquals(ClaimState.COMPLETED, reloaded.getState());
    }

    @Test
    public void testClaimAndWatchdogAlert() {
        String achId = "catching_gotcha";
        store.increment(testPlayer, achId, 1);

        long beforeAttempts = AchievementWatchdog.getDoubleClaimAttempts();

        // Legitimate claim
        boolean claimed = store.claim(testPlayer, achId);
        assertTrue(claimed);
        assertEquals(ClaimState.CLAIMED, store.getProgress(testPlayer, achId).getState());

        // Duplicate claim attempt -> Watchdog must catch and record it
        boolean duplicateClaimed = store.claim(testPlayer, achId);
        assertFalse(duplicateClaimed);
        assertEquals(beforeAttempts + 1, AchievementWatchdog.getDoubleClaimAttempts(),
                "Watchdog duplicate claim counter must increment");
    }

    @Test
    public void testReset() {
        String achId = "catching_gotcha";
        store.increment(testPlayer, achId, 1);
        store.claim(testPlayer, achId);

        store.reset(testPlayer, achId);
        PlayerProgress progress = store.getProgress(testPlayer, achId);
        assertEquals(0, progress.getProgress());
        assertEquals(ClaimState.LOCKED, progress.getState());
    }
}
