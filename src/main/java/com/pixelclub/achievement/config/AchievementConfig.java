package com.pixelclub.achievement.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Server configuration for Pixelclub Achievements.
 * Stored at config/pxc-achievements/config.json.
 */
public class AchievementConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path configPath;

    @SerializedName("currency_command")
    private String currencyCommand = "money give {player} {amount}";

    @SerializedName("currency_fallback_command")
    private String currencyFallbackCommand = "eco give {player} {amount}";

    @SerializedName("raid_coin_command")
    private String raidCoinCommand = "raidcoin give {player} {amount}";

    @SerializedName("title_command")
    private String titleCommand = "lp user {player} meta setprefix 100 \"[{title}] \"";

    @SerializedName("crate_key_command")
    private String crateKeyCommand = "crate key give {player} {key} {amount}";

    @SerializedName("item_give_command")
    private String itemGiveCommand = "give {player} {item} {amount}";

    @SerializedName("notify_player_on_payout")
    private boolean notifyPlayerOnPayout = true;

    @SerializedName("disabled_categories")
    private Set<String> disabledCategories = new LinkedHashSet<>();

    public static AchievementConfig loadOrCreate(Path file) {
        AchievementConfig config = new AchievementConfig();
        if (Files.exists(file)) {
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                AchievementConfig loaded = GSON.fromJson(reader, AchievementConfig.class);
                if (loaded != null) {
                    config = loaded;
                    config.configPath = file;
                    if (config.disabledCategories == null) {
                        config.disabledCategories = new LinkedHashSet<>();
                    }
                    LOGGER.info("Loaded configuration from {}", file);
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read configuration from {}, using defaults", file, e);
            }
        }

        // Save default file if missing or failed to parse
        config.configPath = file;
        config.save(file);
        return config;
    }

    public boolean save() {
        if (this.configPath != null) {
            return save(this.configPath);
        }
        return false;
    }

    public boolean save(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            LOGGER.info("Saved configuration to {}", file);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to save configuration to {}", file, e);
            return false;
        }
    }

    public boolean isCategoryEnabled(String categoryId) {
        if (categoryId == null || disabledCategories == null) return true;
        return !disabledCategories.contains(categoryId.toLowerCase());
    }

    public boolean setCategoryEnabled(String categoryId, boolean enabled) {
        if (categoryId == null) return false;
        if (disabledCategories == null) {
            disabledCategories = new LinkedHashSet<>();
        }
        String id = categoryId.toLowerCase();
        boolean changed;
        if (enabled) {
            changed = disabledCategories.remove(id);
        } else {
            changed = disabledCategories.add(id);
        }
        return changed;
    }

    public Set<String> getDisabledCategories() {
        if (disabledCategories == null) return Collections.emptySet();
        return Collections.unmodifiableSet(disabledCategories);
    }

    public String getCurrencyCommand() {
        return currencyCommand != null && !currencyCommand.isEmpty() ? currencyCommand : "money give {player} {amount}";
    }

    public String getCurrencyFallbackCommand() {
        return currencyFallbackCommand != null && !currencyFallbackCommand.isEmpty() ? currencyFallbackCommand : "eco give {player} {amount}";
    }

    public String getRaidCoinCommand() {
        return raidCoinCommand != null && !raidCoinCommand.isEmpty() ? raidCoinCommand : "raidcoin give {player} {amount}";
    }

    public String getTitleCommand() {
        return titleCommand != null && !titleCommand.isEmpty() ? titleCommand : "lp user {player} meta setprefix 100 \"[{title}] \"";
    }

    public String getCrateKeyCommand() {
        return crateKeyCommand != null && !crateKeyCommand.isEmpty() ? crateKeyCommand : "crate key give {player} {key} {amount}";
    }

    public String getItemGiveCommand() {
        return itemGiveCommand != null && !itemGiveCommand.isEmpty() ? itemGiveCommand : "give {player} {item} {amount}";
    }

    public boolean isNotifyPlayerOnPayout() {
        return notifyPlayerOnPayout;
    }
}
