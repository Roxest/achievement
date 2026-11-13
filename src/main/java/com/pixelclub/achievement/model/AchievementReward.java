package com.pixelclub.achievement.model;

/**
 * Representation of an individual reward associated with an achievement completion.
 */
public class AchievementReward {
    private String type;         // CURRENCY, ITEM, CRATE_KEY, TITLE, COMMAND
    private String currency;     // POKECOIN, RAID_COIN
    private String id;           // Item identifier or key id
    private String display;      // Human-readable display label
    private String display_th;   // Human-readable display label in Thai
    private int amount;          // Quantity or coin value
    private String command;      // Console command template if applicable

    public AchievementReward() {}

    public AchievementReward(String type, String currency, String id, String display, int amount, String command) {
        this.type = type;
        this.currency = currency;
        this.id = id;
        this.display = display;
        this.amount = amount;
        this.command = command;
    }

    public AchievementReward(String type, String currency, String id, String display, String display_th, int amount, String command) {
        this.type = type;
        this.currency = currency;
        this.id = id;
        this.display = display;
        this.display_th = display_th;
        this.amount = amount;
        this.command = command;
    }

    public String getType() { return type != null ? type : "COMMAND"; }
    public String getCurrency() { return currency; }
    public String getId() { return id; }
    public String getDisplay() { return display != null ? display : ""; }
    public String getDisplay(boolean isThai) { return (isThai && display_th != null && !display_th.isEmpty()) ? display_th : getDisplay(); }
    public String getDisplayTh() { return display_th != null ? display_th : ""; }
    public int getAmount() { return amount; }
    public String getCommand() { return command; }
}
