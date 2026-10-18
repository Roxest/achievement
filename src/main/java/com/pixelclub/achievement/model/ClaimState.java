package com.pixelclub.achievement.model;

/**
 * Atomic state of a player's achievement progress.
 * Transition is strictly monotonic: LOCKED -> COMPLETED -> CLAIMED.
 */
public enum ClaimState {
    LOCKED(0),
    COMPLETED(1),
    CLAIMED(2);

    private final int id;

    ClaimState(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static ClaimState fromId(int id) {
        for (ClaimState state : values()) {
            if (state.id == id) return state;
        }
        return LOCKED;
    }
}
