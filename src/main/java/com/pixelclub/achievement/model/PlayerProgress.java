package com.pixelclub.achievement.model;

/**
 * Thread-safe record of a player's progress on an individual achievement.
 * Guarantees monotonic progression and exactly-once reward claims.
 */
public class PlayerProgress {
    private int p; // progress value
    private int s; // state: 0=LOCKED, 1=COMPLETED, 2=CLAIMED
    private long t; // last transition epoch millis

    public PlayerProgress() {
        this.p = 0;
        this.s = ClaimState.LOCKED.getId();
        this.t = System.currentTimeMillis();
    }

    public PlayerProgress(int progress, ClaimState state) {
        this(progress, state, System.currentTimeMillis());
    }

    public PlayerProgress(int progress, ClaimState state, long timestamp) {
        this.p = Math.max(0, progress);
        this.s = state != null ? state.getId() : ClaimState.LOCKED.getId();
        this.t = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public synchronized int getProgress() {
        return p;
    }

    public synchronized ClaimState getState() {
        return ClaimState.fromId(s);
    }

    public synchronized long getTimestamp() {
        return t;
    }

    /**
     * Atomically increments progress by amount and updates completion state.
     * Progress is monotonic (never decreases).
     * Returns true if this update triggered a new completion.
     */
    public synchronized boolean addProgress(int amount, int target) {
        if (amount <= 0) return false;
        this.p += amount;
        this.t = System.currentTimeMillis();
        return checkCompletion(target);
    }

    /**
     * Atomically sets progress to value if greater than current, updating completion state.
     */
    public synchronized boolean setMaxProgress(int value, int target) {
        if (value > this.p) {
            this.p = value;
            this.t = System.currentTimeMillis();
            return checkCompletion(target);
        }
        return false;
    }

    /**
     * Explicit override for administrative adjustments.
     */
    public synchronized boolean setProgressExplicit(int value, int target) {
        this.p = Math.max(0, value);
        this.t = System.currentTimeMillis();
        if (this.p < target) {
            this.s = ClaimState.LOCKED.getId();
            return false;
        } else if (this.s == ClaimState.LOCKED.getId()) {
            this.s = ClaimState.COMPLETED.getId();
            return true;
        }
        return false;
    }

    private boolean checkCompletion(int target) {
        if (s == ClaimState.LOCKED.getId() && p >= target) {
            this.s = ClaimState.COMPLETED.getId();
            return true;
        }
        return false;
    }

    /**
     * Atomically transitions state from COMPLETED to CLAIMED.
     * Returns true if transition succeeded, or false if already claimed / still locked.
     */
    public synchronized boolean tryClaim() {
        if (s == ClaimState.COMPLETED.getId()) {
            this.s = ClaimState.CLAIMED.getId();
            this.t = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    public synchronized boolean isClaimed() {
        return s == ClaimState.CLAIMED.getId();
    }

    public synchronized void forceState(ClaimState state) {
        if (state != null) {
            this.s = state.getId();
            this.t = System.currentTimeMillis();
        }
    }

    public synchronized boolean isCompleted() {
        return s >= ClaimState.COMPLETED.getId();
    }

    public synchronized void reset() {
        this.p = 0;
        this.s = ClaimState.LOCKED.getId();
        this.t = System.currentTimeMillis();
    }
}
