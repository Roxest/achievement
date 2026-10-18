package com.pixelclub.achievement;

import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class PlayerProgressTest {

    @Test
    public void testMonotonicProgressAndCompletion() {
        PlayerProgress progress = new PlayerProgress();
        assertEquals(0, progress.getProgress());
        assertEquals(ClaimState.LOCKED, progress.getState());

        // Increment below target
        boolean completed = progress.addProgress(5, 10);
        assertFalse(completed);
        assertEquals(5, progress.getProgress());
        assertEquals(ClaimState.LOCKED, progress.getState());

        // Increment to reach target
        completed = progress.addProgress(5, 10);
        assertTrue(completed);
        assertEquals(10, progress.getProgress());
        assertEquals(ClaimState.COMPLETED, progress.getState());

        // Additional progress beyond target keeps COMPLETED
        completed = progress.addProgress(5, 10);
        assertFalse(completed);
        assertEquals(15, progress.getProgress());
        assertEquals(ClaimState.COMPLETED, progress.getState());
    }

    @Test
    public void testExactlyOnceClaim() {
        PlayerProgress progress = new PlayerProgress();
        progress.addProgress(10, 10);
        assertEquals(ClaimState.COMPLETED, progress.getState());

        // First claim must succeed
        boolean claimedFirst = progress.tryClaim();
        assertTrue(claimedFirst, "First claim attempt must succeed");
        assertEquals(ClaimState.CLAIMED, progress.getState());
        assertTrue(progress.isClaimed());

        // Second claim attempt must fail
        boolean claimedSecond = progress.tryClaim();
        assertFalse(claimedSecond, "Second claim attempt must fail");
        assertEquals(ClaimState.CLAIMED, progress.getState());
    }

    @Test
    public void testConcurrentClaimRaceCondition() throws InterruptedException {
        PlayerProgress progress = new PlayerProgress();
        progress.addProgress(10, 10);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (progress.tryClaim()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent thread must succeed in claiming");
        assertEquals(ClaimState.CLAIMED, progress.getState());
    }

    @Test
    public void testSetProgressExplicitRevertState() {
        PlayerProgress progress = new PlayerProgress();
        progress.addProgress(10, 10);
        assertEquals(ClaimState.COMPLETED, progress.getState());
        progress.tryClaim();
        assertEquals(ClaimState.CLAIMED, progress.getState());

        // Explicitly set below target -> must revert to LOCKED
        progress.setProgressExplicit(5, 10);
        assertEquals(5, progress.getProgress());
        assertEquals(ClaimState.LOCKED, progress.getState());

        // Explicitly set back to target -> becomes COMPLETED
        boolean completed = progress.setProgressExplicit(10, 10);
        assertTrue(completed);
        assertEquals(10, progress.getProgress());
        assertEquals(ClaimState.COMPLETED, progress.getState());
    }
}
