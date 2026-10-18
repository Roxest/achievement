package com.pixelclub.achievement.model;

/**
 * Representation of an individual reward associated with an achievement completion.
 */
public class AchievementReward {
    private String type;         // CURRENCY, ITEM, CRATE_KEY, TITLE, COMMAND
    private String currency;     // POKECOIN, RAID_COIN
    private String id;           // Item identifier or key id
    private String display;      // Human-readable display label
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

    public String getType() { return type != null ? type : "COMMAND"; }
    public String getCurrency() { return currency; }
    public String getId() { return id; }
    public String getDisplay() { return display != null ? display : ""; }
    public int getAmount() { return amount; }
    public String getCommand() { return command; }
}
