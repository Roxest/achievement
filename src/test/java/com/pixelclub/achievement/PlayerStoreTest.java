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
        if (store != null) {
            store.shutdown();
        }
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

        // Flush dirty writes to disk
        store.flushAllSync();

        // Verify disk file created
        Path file = tempDir.resolve(testPlayer.toString() + ".json");
        assertTrue(Files.exists(file), "Player data file must exist on disk");

        // Reload fresh store from disk
        PlayerStore freshStore = new PlayerStore(tempDir, registry);
        PlayerProgress reloaded = freshStore.getProgress(testPlayer, achId);
        assertEquals(1, reloaded.getProgress());
        assertEquals(ClaimState.COMPLETED, reloaded.getState());
        freshStore.shutdown();
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
    public void testNegativeIncrementBlockedByWatchdog() {
        String achId = "catching_gotcha";
        store.increment(testPlayer, achId, 1);
        long beforeIllegal = AchievementWatchdog.getIllegalProgressAttempts();

        boolean result = store.increment(testPlayer, achId, -1);
        assertFalse(result, "Negative increment must be rejected");
        assertEquals(beforeIllegal + 1, AchievementWatchdog.getIllegalProgressAttempts(),
                "Watchdog non-monotonic alert counter must increment");
        assertEquals(1, store.getProgress(testPlayer, achId).getProgress(), "Progress must not decrease");
    }

    @Test
    public void testDirtyFlagAndFlushSync() {
        String achId = "catching_gotcha";
        store.increment(testPlayer, achId, 1);
        assertTrue(store.getDirtyPlayers().contains(testPlayer), "Player must be marked dirty after progress update");

        store.flushAllSync();
        assertFalse(store.getDirtyPlayers().contains(testPlayer), "Dirty players set must be clear after flush");

        Path file = tempDir.resolve(testPlayer.toString() + ".json");
        assertTrue(Files.exists(file), "File must be flushed to disk");
    }

    @Test
    public void testSetMaxPartialProgressMarksDirty() {
        String achId = "catching_a_good_start"; // target 50
        store.flushAllSync();
        assertFalse(store.getDirtyPlayers().contains(testPlayer));

        // Partial progress: 10 < 50
        boolean completed = store.setMax(testPlayer, achId, 10);
        assertFalse(completed, "Partial progress should not complete achievement");
        assertEquals(10, store.getProgress(testPlayer, achId).getProgress());
        assertTrue(store.getDirtyPlayers().contains(testPlayer), "Player UUID must be added to dirty players on partial setMax");
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

    @Test
    public void testOnPlayerDisconnectFlushesAndEvicts() {
        String achId = "catching_gotcha";
        store.increment(testPlayer, achId, 1);
        assertTrue(store.getDirtyPlayers().contains(testPlayer), "Player must be dirty before disconnect");

        // Disconnect player
        store.onPlayerDisconnect(testPlayer);

        assertFalse(store.getDirtyPlayers().contains(testPlayer), "Dirty set must be empty after disconnect flush");

        Path file = tempDir.resolve(testPlayer.toString() + ".json");
        assertTrue(Files.exists(file), "Player data file must be saved to storage on disconnect");

        // Subsequent getProgress reloads from storage because in-memory cache was evicted
        PlayerProgress reloaded = store.getProgress(testPlayer, achId);
        assertEquals(1, reloaded.getProgress());
        assertEquals(ClaimState.COMPLETED, reloaded.getState());
    }

    @Test
    public void testAdminSetProgressAndResetOverwrite() {
        String achId = "catching_a_good_start"; // target 50
        store.setMax(testPlayer, achId, 40);
        assertEquals(40, store.getProgress(testPlayer, achId).getProgress());
        store.flushPlayerSync(testPlayer);

        // Admin override to lower value (bypassing monotonic check)
        store.setProgress(testPlayer, achId, 10);
        assertEquals(10, store.getProgress(testPlayer, achId).getProgress(), "Admin setProgress must overwrite value");
        store.flushPlayerSync(testPlayer);

        // Verify fresh store reloaded from disk reflects explicit override
        PlayerStore freshStore = new PlayerStore(tempDir, registry);
        assertEquals(10, freshStore.getProgress(testPlayer, achId).getProgress(), "Storage must hold explicit override value 10");
        freshStore.shutdown();

        // Admin reset
        store.reset(testPlayer, achId);
        assertEquals(0, store.getProgress(testPlayer, achId).getProgress());
        assertEquals(ClaimState.LOCKED, store.getProgress(testPlayer, achId).getState());
        store.flushPlayerSync(testPlayer);

        PlayerStore freshStore2 = new PlayerStore(tempDir, registry);
        assertEquals(0, freshStore2.getProgress(testPlayer, achId).getProgress());
        assertEquals(ClaimState.LOCKED, freshStore2.getProgress(testPlayer, achId).getState());
        freshStore2.shutdown();
    }
}
